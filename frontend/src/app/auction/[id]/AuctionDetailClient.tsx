"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import type {
  AuctionTopicEnvelope,
  BidHistoryEntry,
  PublicAuctionResponse,
  SellerAuctionResponse,
} from "@/types/auction";
import type { Page } from "@/types/page";
import { useConnectionState, useStompSubscription } from "@/lib/ws/hooks";
import { useAuth } from "@/lib/auth";
import { useAuction, auctionKey } from "@/hooks/useAuction";
import { useBidHistory, bidHistoryKey } from "@/hooks/useBidHistory";
import { useMyProxy, myProxyKey } from "@/hooks/useMyProxy";
import { userApi, type PublicUserProfile } from "@/lib/user/api";
import { AuctionHero } from "@/components/auction/AuctionHero";
import { BreadcrumbNav } from "@/components/auction/BreadcrumbNav";
import { ParcelInfoPanel } from "@/components/auction/ParcelInfoPanel";
import { ParcelLayoutMapPlaceholder } from "@/components/auction/ParcelLayoutMapPlaceholder";
import {
  SellerProfileCard,
  type SellerProfileCardSeller,
} from "@/components/auction/SellerProfileCard";
import { VisitInSecondLifeBlock } from "@/components/auction/VisitInSecondLifeBlock";
import { BidPanel } from "@/components/auction/BidPanel";
import { BidHistoryList } from "@/components/auction/BidHistoryList";
import { AuctionEndedRow } from "@/components/auction/AuctionEndedRow";
import { formatRemainingLabel } from "@/components/auction/SnipeExtensionBanner";
import { OutbidToastProvider } from "@/components/auction/OutbidToastProvider";
import { StickyBidBar } from "@/components/auction/StickyBidBar";
import { BidSheet } from "@/components/auction/BidSheet";
import { useToast } from "@/components/ui/Toast";

/**
 * Client shell for the auction detail page.
 *
 * Seeds React Query with the server-component fetch, subscribes to
 * {@code /topic/auction/{id}} for real-time settlement + ended envelopes,
 * merges each envelope into the cache (auction, bid page 0, my-proxy
 * invalidation), and reconciles via REST invalidation when the socket
 * transitions from reconnecting → connected.
 *
 * Composes the desktop sidebar BidPanel and the mobile
 * {@link StickyBidBar} + {@link BidSheet} pair via a CSS-only
 * {@code hidden lg:block} / {@code lg:hidden} toggle — no media query
 * hook, no hydration flash. The sheet is closed by default, so at most
 * one BidPanel form is visible per breakpoint (sidebar on desktop,
 * sheet body on mobile).
 *
 * See spec §5 (real-time strategy), §7 (server/client composition), §8
 * (layout composition), §11 (mobile pattern).
 */
interface Props {
  initialAuction: PublicAuctionResponse | SellerAuctionResponse;
  initialBidPage: Page<BidHistoryEntry>;
}

/**
 * Widened cache shape. The WS envelope merger may write fields that live on
 * {@link SellerAuctionResponse} but not {@link PublicAuctionResponse} (e.g.
 * {@code endOutcome}, {@code finalBidAmount}, {@code winnerUserId}) so the
 * cache needs a type that admits both. TypeScript narrows back to each DTO
 * at consumer sites via discriminators like {@code status}.
 *
 * {@code currentBidderId} is persisted from every {@link BidSettlementEnvelope}
 * so {@link OutbidToastProvider.maybeFire} has a reliable pre-settlement
 * snapshot and {@code currentUserIsWinning} can be derived without a
 * separate query. Neither DTO carries this field today — the initial
 * server fetch leaves it {@code undefined}, which is indistinguishable
 * from "no one has bid yet" for the outbid-guard (and that's the correct
 * semantics: the first envelope can never displace the caller).
 */
type AuctionCacheEntry = (
  | PublicAuctionResponse
  | SellerAuctionResponse
  | (PublicAuctionResponse & Partial<SellerAuctionResponse>)
) & {
  currentBidderId?: number | null;
};

export function AuctionDetailClient({ initialAuction, initialBidPage }: Props) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const session = useAuth();
  const currentUserId =
    session.status === "authenticated" ? session.user.id : null;
  const { id } = initialAuction;
  const isSellerViewer =
    currentUserId != null && currentUserId === initialAuction.sellerId;

  const auctionQuery = useAuction(id, initialAuction);
  const bidHistoryQuery = useBidHistory(id, 0, initialBidPage);
  const myProxyQuery = useMyProxy(id, {
    enabled: currentUserId != null && !isSellerViewer,
  });

  // Seller enrichment. Epic 07 sub-spec 1 Task 2 added an inline
  // {@code seller} block to {@code PublicAuctionResponse} — prefer that
  // when present, falling back to the {@code /api/v1/users/{id}} fetch so
  // legacy server builds / fixtures that predate the DTO widening still
  // render a complete card. The client query is only enabled when the
  // inline block is absent, so the normal request path is a single SSR
  // fetch.
  const sellerEnriched = initialAuction.seller ?? null;
  const sellerQuery = useQuery<PublicUserProfile>({
    queryKey: ["publicProfile", initialAuction.sellerId],
    queryFn: () => userApi.publicProfile(initialAuction.sellerId),
    staleTime: 60_000,
    enabled: sellerEnriched == null,
  });

  const auction = auctionQuery.data ?? initialAuction;
  const connectionState = useConnectionState();

  // Server-time offset, refined on every envelope arrival. Held in a ref so
  // the countdown renderer can read the latest value without re-subscribing
  // or re-rendering the whole page on each tick. Task 6's CountdownTimer
  // consumes this.
  const serverTimeOffsetRef = useRef<number>(0);

  // Tracks whether the connection was ever NOT `connected` so that the
  // next `connected` transition triggers a REST reconcile. `useRef` (not
  // state) — changing this never needs to trigger a re-render on its own;
  // the connectionState change is what re-runs the effect.
  const wasReconnectingRef = useRef<boolean>(false);

  // Mobile sheet open state. The desktop BidPanel lives in the sticky
  // sidebar and is always visible at {@code lg:}+; on narrower viewports
  // the sidebar is hidden via {@code hidden lg:block} and the sticky
  // bar's "Bid now" button flips this to true, mounting the real
  // {@link BidPanel} inside a {@link BidSheet}. CSS-only responsive
  // toggle per spec §8 — no {@code useMediaQuery}, no hydration flash.
  const [sheetOpen, setSheetOpen] = useState<boolean>(false);

  // Transient snipe-extension banner signal. The envelope handler
  // detects a snipe-triggering bid in {@code env.newBids} and flips this
  // on; the banner re-mounts via a bumped token so its 4s timer
  // restarts cleanly. {@code onExpire} clears the signal.
  const [snipeSignal, setSnipeSignal] = useState<{
    isVisible: boolean;
    extensionMinutes: number;
    remainingAfterExtension: string;
    token: number;
  }>({
    isVisible: false,
    extensionMinutes: 0,
    remainingAfterExtension: "",
    token: 0,
  });
  const clearSnipeSignal = useCallback(() => {
    setSnipeSignal((prev) => ({ ...prev, isVisible: false }));
  }, []);

  const handleEnvelope = useCallback(
    (env: AuctionTopicEnvelope) => {
      // Refine the server-time offset on every envelope so the countdown
      // stays accurate even if the user's clock drifts mid-session.
      serverTimeOffsetRef.current =
        new Date(env.serverTime).getTime() - Date.now();

      // Escrow envelopes: invalidate only — no DTO merging, per spec §7.2.
      // The dedicated escrow page owns its own cache and will refetch; the
      // auction detail page only needs to know the auction row may have
      // transitioned (e.g. endOutcome changed on a frozen escrow) so the
      // auction cache is also invalidated here.
      if (env.type.startsWith("ESCROW_")) {
        queryClient.invalidateQueries({ queryKey: ["escrow", id] });
        queryClient.invalidateQueries({ queryKey: auctionKey(id) });
        return;
      }

      // Snapshot BEFORE the cache mutation so the outbid-toast hook point
      // (wired in Task 7 via OutbidToastProvider) has the pre-settlement
      // state available — otherwise the was-winning guard always sees the
      // post-settlement values. Assignment preserves the snapshot for the
      // BID_SETTLEMENT branch below.
      const prevAuction = queryClient.getQueryData<AuctionCacheEntry>(
        auctionKey(id),
      );

      queryClient.setQueryData<AuctionCacheEntry>(
        auctionKey(id),
        (prev) => {
          if (!prev) return prev;
          if (env.type === "BID_SETTLEMENT") {
            return {
              ...prev,
              currentHighBid: env.currentBid,
              currentBidderId: env.currentBidderId,
              bidderCount: env.bidCount,
              endsAt: env.endsAt,
              originalEndsAt: env.originalEndsAt,
            };
          }
          // AUCTION_ENDED — transition to terminal state. `endOutcome`,
          // `finalBidAmount`, `winnerUserId` live only on the
          // SellerAuctionResponse shape; the cache is typed wide enough to
          // admit them on the public shape too. Task 6's AuctionEndedPanel
          // reads them back.
          return {
            ...prev,
            status: "ENDED",
            endsAt: env.endsAt,
            endOutcome: env.endOutcome,
            finalBidAmount: env.finalBid,
            winnerUserId: env.winnerUserId,
            bidderCount: env.bidCount,
          };
        },
      );

      if (env.type === "BID_SETTLEMENT") {
        // Prepend the new bids into cached page 0 and dedupe by bidId so
        // a settlement that arrives before the REST fetch completes
        // doesn't double-count. Trimmed to `prev.size` to keep page 0's
        // window stable — older rows fall off into page 1 on the next
        // refetch. `totalElements` grows by the delta so the pager math
        // stays honest.
        queryClient.setQueryData<Page<BidHistoryEntry>>(
          bidHistoryKey(id, 0),
          (prev) => {
            if (!prev) return prev;
            const merged = dedupeByBidId([
              ...env.newBids,
              ...prev.content,
            ]).slice(0, prev.size);
            return {
              ...prev,
              content: merged,
              totalElements: prev.totalElements + env.newBids.length,
            };
          },
        );
        queryClient.invalidateQueries({ queryKey: myProxyKey(id) });

        // Snipe-extension banner trigger. If any newBid in the envelope
        // stamped a {@code snipeExtensionMinutes}, surface the transient
        // banner on the BidPanel with the extension amount and the
        // pre-formatted remaining-time label. Take the last extending
        // bid in the batch — envelopes rarely include more than one,
        // and "last wins" is the least surprising rule if they do.
        const extendingBid = [...env.newBids]
          .reverse()
          .find((b) => b.snipeExtensionMinutes != null);
        if (extendingBid && extendingBid.snipeExtensionMinutes != null) {
          const endsAtMs = new Date(env.endsAt).getTime();
          const remainingMs = endsAtMs - Date.now();
          setSnipeSignal((prev) => ({
            isVisible: true,
            extensionMinutes: extendingBid.snipeExtensionMinutes as number,
            remainingAfterExtension: formatRemainingLabel(remainingMs),
            token: prev.token + 1,
          }));
        }

        // Outbid-toast signal. Guards documented inside
        // {@link OutbidToastProvider.maybeFire}: was-winning + now-
        // losing, with anonymous / first-envelope / still-winning cases
        // short-circuiting. The cache snapshot taken before the merge
        // above is the authoritative pre-settlement state.
        OutbidToastProvider.maybeFire(prevAuction, env, currentUserId, toast);
      }

      if (env.type === "AUCTION_ENDED") {
        queryClient.invalidateQueries({ queryKey: myProxyKey(id) });
      }
    },
    [queryClient, id, currentUserId, toast],
  );

  useStompSubscription<AuctionTopicEnvelope>(
    `/topic/auction/${id}`,
    handleEnvelope,
  );

  // Reconnect reconcile — on the first `connected` edge after any
  // non-connected state, invalidate all auction-scoped queries to re-pull
  // the server truth. Any envelope we missed during the outage lands via
  // this refetch pathway. The ref flip happens AFTER invalidation so a
  // rapid re-disconnect doesn't lose the pending-reconcile signal.
  useEffect(() => {
    const status = connectionState.status;
    if (status === "connected" && wasReconnectingRef.current) {
      queryClient.invalidateQueries({ queryKey: auctionKey(id) });
      queryClient.invalidateQueries({ queryKey: bidHistoryKey(id, 0) });
      queryClient.invalidateQueries({ queryKey: myProxyKey(id) });
      wasReconnectingRef.current = false;
    }
    if (status === "reconnecting" || status === "error") {
      wasReconnectingRef.current = true;
    }
  }, [connectionState.status, queryClient, id]);

  // bidHistoryQuery is still subscribed here so the query stays warm and
  // the shell knows whether page 0 has been hydrated. The concrete list
  // + count UI lives inside {@link BidHistoryList}, which re-subscribes
  // via its own {@code useBidHistory} call (React Query dedupes the
  // fetch).
  void bidHistoryQuery;

  // Map whichever source is available into the card's expected shape.
  // The inline {@code seller} block (sub-spec 1) wins when present; the
  // profile-fetch fallback only runs when it isn't, and the
  // {@code PublicUserProfile} fields are projected into the enriched
  // shape (completionRate / memberSince aren't in the public-profile
  // response, so they stay null → the card renders the "Too new to
  // calculate" and New Seller copy gracefully).
  const sellerCardData: SellerProfileCardSeller = sellerEnriched
    ? {
        id: sellerEnriched.id,
        displayName: sellerEnriched.displayName,
        avatarUrl: sellerEnriched.avatarUrl,
        averageRating: sellerEnriched.averageRating,
        reviewCount: sellerEnriched.reviewCount,
        completedSales: sellerEnriched.completedSales,
        completionRate: sellerEnriched.completionRate,
        memberSince: sellerEnriched.memberSince,
      }
    : sellerQuery.data
      ? {
          id: sellerQuery.data.id,
          displayName: sellerQuery.data.displayName ?? "Seller",
          avatarUrl: sellerQuery.data.profilePicUrl,
          averageRating: sellerQuery.data.avgSellerRating,
          reviewCount: sellerQuery.data.totalSellerReviews,
          completedSales: sellerQuery.data.completedSales,
          completionRate: null,
          memberSince: sellerQuery.data.createdAt,
        }
      : {
          id: auction.sellerId,
          displayName: "Seller",
          completedSales: 0,
        };

  // Hoisted viewer projection + winning-state derivation so the desktop
  // sidebar BidPanel, the mobile sheet BidPanel, and the StickyBidBar
  // all read from the same local values rather than recomputing the
  // session shape per call-site.
  const bidPanelUser =
    session.status === "authenticated"
      ? { id: session.user.id, verified: session.user.verified }
      : null;
  const viewerIsWinning =
    currentUserId != null &&
    (auction as AuctionCacheEntry).currentBidderId === currentUserId;

  return (
    <main className="max-w-7xl mx-auto px-4 lg:px-8 pt-8 lg:pt-24 pb-24 lg:pb-12">
      <BreadcrumbNav
        region={auction.parcel.regionName}
        title={auction.title}
      />
      <div className="mt-6 grid grid-cols-1 lg:grid-cols-12 gap-6 lg:gap-12">
        <div className="lg:col-span-8 space-y-8 lg:space-y-12">
          <AuctionHero
            photos={auction.photos}
            snapshotUrl={auction.parcel.snapshotUrl}
            regionName={auction.parcel.regionName}
          />
          <ParcelInfoPanel auction={auction} />
          <VisitInSecondLifeBlock
            regionName={auction.parcel.regionName}
            positionX={null}
            positionY={null}
            positionZ={null}
          />
          {auction.status === "ENDED" ? (
            <AuctionEndedRow auction={auction} />
          ) : null}
          <ParcelLayoutMapPlaceholder />
          <BidHistoryList auctionId={id} />
          <SellerProfileCard seller={sellerCardData} />
        </div>
        <aside className="hidden lg:block lg:col-span-4">
          <div
            className="sticky top-24"
            data-testid="bid-panel-slot"
            data-ws-state={connectionState.status}
          >
            <BidPanel
              auction={auction}
              currentUser={bidPanelUser}
              existingProxy={myProxyQuery.data ?? null}
              connectionState={connectionState}
              currentUserIsWinning={viewerIsWinning}
              snipeExtension={{ ...snipeSignal, onExpire: clearSnipeSignal }}
            />
          </div>
        </aside>
      </div>
      {/* Mobile chrome — sticky bar + bottom sheet. The wrapper is
          {@code lg:hidden} so both subtrees are absent from the DOM at
          {@code lg:}+ and the desktop sidebar above is the only
          BidPanel mount. Below {@code lg:}, the sidebar itself is
          hidden via {@code hidden lg:block}, so the sheet (closed by
          default) is the only BidPanel mount and form state cannot
          diverge across instances. CSS-only toggle per spec §8. */}
      <div className="lg:hidden" data-testid="auction-mobile-chrome">
        <StickyBidBar
          auction={auction}
          currentUser={bidPanelUser}
          connectionState={connectionState}
          onOpenSheet={() => setSheetOpen(true)}
        />
        <BidSheet isOpen={sheetOpen} onClose={() => setSheetOpen(false)}>
          <BidPanel
            auction={auction}
            currentUser={bidPanelUser}
            existingProxy={myProxyQuery.data ?? null}
            connectionState={connectionState}
            currentUserIsWinning={viewerIsWinning}
            snipeExtension={{ ...snipeSignal, onExpire: clearSnipeSignal }}
          />
        </BidSheet>
      </div>
    </main>
  );
}

/**
 * O(n) dedupe of bid-history entries keyed by {@code bidId}, preserving the
 * first occurrence. Used when merging {@code BidSettlementEnvelope.newBids}
 * into cached page 0 — the envelope can overlap with the REST response on
 * the boundary (e.g. a bid committed during the initial fetch) and we
 * cannot trust that the two code paths produce a bit-identical row even
 * for the same id, so keep the first (newer, from WS).
 */
function dedupeByBidId(bids: BidHistoryEntry[]): BidHistoryEntry[] {
  const seen = new Set<number>();
  const out: BidHistoryEntry[] = [];
  for (const b of bids) {
    if (seen.has(b.bidId)) continue;
    seen.add(b.bidId);
    out.push(b);
  }
  return out;
}
