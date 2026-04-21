"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import type {
  AuctionEnvelope,
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
import { ParcelInfoPanel } from "@/components/auction/ParcelInfoPanel";
import {
  SellerProfileCard,
  type SellerProfileCardSeller,
} from "@/components/auction/SellerProfileCard";
import { BidPanel } from "@/components/auction/BidPanel";
import { BidHistoryList } from "@/components/auction/BidHistoryList";
import { AuctionEndedRow } from "@/components/auction/AuctionEndedRow";
import { formatRemainingLabel } from "@/components/auction/SnipeExtensionBanner";

/**
 * Client shell for the auction detail page.
 *
 * Seeds React Query with the server-component fetch, subscribes to
 * {@code /topic/auction/{id}} for real-time settlement + ended envelopes,
 * merges each envelope into the cache (auction, bid page 0, my-proxy
 * invalidation), and reconciles via REST invalidation when the socket
 * transitions from reconnecting → connected.
 *
 * Subcomponents (AuctionHero, BidPanel, BidHistoryList, etc.) ship in
 * Tasks 4-8; this task only renders testid-tagged placeholders that read
 * from the seeded cache so the integration test can verify the plumbing.
 *
 * See spec §5 (real-time strategy), §7 (server/client composition), §8
 * (layout composition).
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
 */
type AuctionCacheEntry =
  | PublicAuctionResponse
  | SellerAuctionResponse
  | (PublicAuctionResponse & Partial<SellerAuctionResponse>);

export function AuctionDetailClient({ initialAuction, initialBidPage }: Props) {
  const queryClient = useQueryClient();
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

  // Task 4: seller profile fetch. The public auction DTO only exposes
  // {@code sellerId} — Task 9 adds server-side enrichment so the seller
  // ships inline with the auction payload. Until then we fetch the public
  // profile here and hand it to {@link SellerProfileCard}. The card copes
  // with a pending / errored profile by rendering a minimal shape (id +
  // display-name fallback) so the page layout never waits on this call.
  const sellerQuery = useQuery<PublicUserProfile>({
    queryKey: ["publicProfile", initialAuction.sellerId],
    queryFn: () => userApi.publicProfile(initialAuction.sellerId),
    staleTime: 60_000,
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
    (env: AuctionEnvelope) => {
      // Refine the server-time offset on every envelope so the countdown
      // stays accurate even if the user's clock drifts mid-session.
      serverTimeOffsetRef.current =
        new Date(env.serverTime).getTime() - Date.now();

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

        // Outbid-toast hook point. The full was-winning-guard + toast
        // plumbing ships in Task 7 via {@code OutbidToastProvider.maybeFire};
        // until then the snapshot above keeps the signature stable so Task 7
        // doesn't need to rewire the envelope flow.
        void prevAuction;
        void currentUserId;
      }

      if (env.type === "AUCTION_ENDED") {
        queryClient.invalidateQueries({ queryKey: myProxyKey(id) });
      }
    },
    [queryClient, id, currentUserId],
  );

  useStompSubscription<AuctionEnvelope>(
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

  // Map the public-profile query into the shape SellerProfileCard expects.
  // When the fetch is pending / errored we still render the card with just
  // the auction.sellerId so the layout slot is stable — the card treats
  // missing enrichment fields as "no data yet" (no ratings, zero sales →
  // "New Seller" badge, which is an acceptable default for the few hundred
  // ms before the profile lands).
  const sellerCardData: SellerProfileCardSeller = sellerQuery.data
    ? {
        id: sellerQuery.data.id,
        displayName: sellerQuery.data.displayName ?? "Seller",
        slAvatarName: sellerQuery.data.slAvatarName,
        profilePicUrl: sellerQuery.data.profilePicUrl,
        avgSellerRating: sellerQuery.data.avgSellerRating,
        totalSellerReviews: sellerQuery.data.totalSellerReviews,
        completedSales: sellerQuery.data.completedSales,
      }
    : {
        id: auction.sellerId,
        displayName: "Seller",
      };

  return (
    <main className="max-w-7xl mx-auto px-4 lg:px-8 pt-8 lg:pt-24 pb-24 lg:pb-12">
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 lg:gap-12">
        <div className="lg:col-span-8 space-y-8 lg:space-y-12">
          <AuctionHero
            photos={auction.photos}
            snapshotUrl={auction.parcel.snapshotUrl}
            regionName={auction.parcel.regionName}
          />
          <ParcelInfoPanel auction={auction} />
          {auction.status === "ENDED" ? (
            <AuctionEndedRow auction={auction} />
          ) : null}
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
              currentUser={
                session.status === "authenticated"
                  ? {
                      id: session.user.id,
                      verified: session.user.verified,
                    }
                  : null
              }
              existingProxy={myProxyQuery.data ?? null}
              connectionState={connectionState}
              // TODO(task-7): derive from BID_SETTLEMENT.currentBidderId snapshot —
              // Task 7 (OutbidToastProvider) already computes prevAuction +
              // currentUserId, so that's the natural place to thread this through.
              currentUserIsWinning={false}
              snipeExtension={{ ...snipeSignal, onExpire: clearSnipeSignal }}
            />
          </div>
        </aside>
      </div>
      <div className="lg:hidden fixed bottom-0 left-0 right-0 bg-surface-container-lowest/90 backdrop-blur px-4 py-3 border-t border-outline-variant">
        <div data-testid="sticky-bid-bar-placeholder" className="text-sm">
          Sticky bar placeholder (Task 8) — L${" "}
          {formatHighBid(auction.currentHighBid)}
        </div>
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

/**
 * Formats the backend {@code currentHighBid} field (BigDecimal → number |
 * string | null) for the placeholder display. Full locale-aware formatting
 * ships with the real BidPanel in Task 5; this is just enough to make the
 * integration test assertions meaningful.
 */
function formatHighBid(value: number | string | null): string {
  if (value == null) return "—";
  const n = typeof value === "string" ? Number(value) : value;
  if (!Number.isFinite(n)) return "—";
  return n.toLocaleString();
}
