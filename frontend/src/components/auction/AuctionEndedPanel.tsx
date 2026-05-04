"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { Avatar } from "@/components/ui/Avatar";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { Gavel } from "@/components/ui/icons";
import { userApi, type PublicUserProfile } from "@/lib/user/api";
import { formatRelativeTime } from "@/lib/time/relativeTime";
import type {
  AuctionEndOutcome,
  PublicAuctionResponse,
  SellerAuctionResponse,
} from "@/types/auction";
import type { EscrowState } from "@/types/escrow";
import type { EscrowChipRole } from "@/components/escrow/EscrowChip";
import {
  escrowBannerCopy,
  type BannerTone,
} from "@/components/escrow/escrowBannerCopy";
import { cn } from "@/lib/cn";

export interface AuctionEndedPanelProps {
  auction: PublicAuctionResponse | SellerAuctionResponse;
  currentUser: { publicId: string } | null;
}

/**
 * Cache-widened view of the auction object. The envelope handler in
 * {@code AuctionDetailClient} writes {@code endOutcome}, {@code finalBidAmount},
 * {@code winnerPublicId}, and {@code winnerDisplayName} on the auction cache
 * even when the incoming shape is {@link PublicAuctionResponse}, so the
 * panel needs to read them defensively rather than via the DTO type alone.
 */
type EndedAuctionFields = {
  endOutcome?: AuctionEndOutcome | null;
  finalBidAmount?: number | null;
  winnerPublicId?: string | null;
  winnerDisplayName?: string | null;
  endsAt?: string | null;
};

/**
 * Terminal-state panel for the auction detail page. Replaces the
 * {@link BidPanel} when {@code auction.status === "ENDED"}. Four outcome
 * variants plus three viewer-specific overlays per spec §10.
 *
 * Winner display name falls back to a React Query lookup when the DTO
 * doesn't carry it inline — Task 9's backend enrichment will stamp it on
 * the payload and this fetch becomes a no-op.
 */
export function AuctionEndedPanel({
  auction,
  currentUser,
}: AuctionEndedPanelProps) {
  const ended = auction as (
    | PublicAuctionResponse
    | SellerAuctionResponse
  ) &
    EndedAuctionFields;

  // Backend always projects endOutcome on ENDED auctions post Epic 05
  // sub-spec 1. If this field is ever null on an ENDED auction, it's a
  // backend invariant violation — let it surface rather than papering
  // over it with a heuristic.
  if (ended.endOutcome == null) {
    throw new Error(
      `AuctionEndedPanel rendered for auction ${auction.publicId} with null endOutcome — ` +
        "backend enrichment invariant violated (Epic 05 sub-spec 1).",
    );
  }
  const outcome: AuctionEndOutcome = ended.endOutcome;
  const finalBid = ended.finalBidAmount ?? numericHighBid(auction.currentHighBid);
  const winnerPublicId = ended.winnerPublicId ?? null;

  // Viewer-specific flags. We derive before fetching so the overlay copy
  // renders immediately even while the winner profile is loading.
  const viewerWon =
    currentUser != null && winnerPublicId != null && currentUser.publicId === winnerPublicId;
  const viewerIsSeller =
    currentUser != null && currentUser.publicId === auction.sellerPublicId;

  // Task 9 ships server-side enrichment for {@code winnerDisplayName}.
  // Until then, fall back to a profile fetch when we have a winnerPublicId but
  // no display name. {@code enabled} gates the fetch so unsold outcomes
  // never query {@code /api/v1/users/null}.
  const winnerQuery = useQuery<PublicUserProfile>({
    queryKey: ["publicProfile", winnerPublicId],
    queryFn: () => userApi.publicProfile(winnerPublicId as string),
    enabled:
      winnerPublicId != null &&
      (ended.winnerDisplayName == null || ended.winnerDisplayName === ""),
    staleTime: 60_000,
  });

  const winnerDisplayName =
    ended.winnerDisplayName ??
    winnerQuery.data?.displayName ??
    (winnerPublicId != null ? "Winner" : null);
  const winnerAvatarUrl = winnerQuery.data?.profilePicUrl ?? undefined;
  const winnerSlName = winnerQuery.data?.slAvatarName ?? null;

  return (
    <section
      aria-label="Auction ended"
      data-testid="auction-ended-panel"
      data-outcome={outcome}
      className="flex flex-col gap-4 rounded-xl bg-surface-raised p-6 shadow-sm"
    >
      <div className="flex items-center gap-2">
        <Gavel className="size-5 text-fg-muted" aria-hidden="true" />
        <StatusBadge status="ended" />
      </div>

      <OutcomeBlock
        auction={auction}
        outcome={outcome}
        finalBid={finalBid}
        winnerPublicId={winnerPublicId}
        winnerDisplayName={winnerDisplayName}
        winnerAvatarUrl={winnerAvatarUrl}
        winnerSlName={winnerSlName}
      />

      {ended.endsAt ? (
        <p
          className="text-xs text-fg-muted"
          data-testid="auction-ended-time"
        >
          Ended {formatRelativeTime(ended.endsAt)}
        </p>
      ) : null}

      {viewerWon ? (
        <WinnerOverlay
          auctionPublicId={auction.publicId}
          outcome={outcome}
          escrowState={auction.escrowState ?? null}
          transferConfirmedAt={auction.transferConfirmedAt ?? null}
        />
      ) : viewerIsSeller ? (
        <SellerOverlay
          auctionPublicId={auction.publicId}
          outcome={outcome}
          escrowState={auction.escrowState ?? null}
          transferConfirmedAt={auction.transferConfirmedAt ?? null}
        />
      ) : null}
    </section>
  );
}

/**
 * Renders the outcome-specific headline + body. Each variant mirrors
 * the spec §10 copy table. The winner row is shared between
 * {@code SOLD} and {@code BOUGHT_NOW}; the unsold outcomes skip it.
 */
function OutcomeBlock({
  auction,
  outcome,
  finalBid,
  winnerPublicId,
  winnerDisplayName,
  winnerAvatarUrl,
  winnerSlName,
}: {
  auction: PublicAuctionResponse | SellerAuctionResponse;
  outcome: AuctionEndOutcome;
  finalBid: number | null;
  winnerPublicId: string | null;
  winnerDisplayName: string | null;
  winnerAvatarUrl: string | undefined;
  winnerSlName: string | null;
}) {
  if (outcome === "SOLD" || outcome === "BOUGHT_NOW") {
    const headline =
      outcome === "BOUGHT_NOW"
        ? `Sold at buy-now price L$${formatAmount(finalBid)}`
        : `Sold for L$${formatAmount(finalBid)}`;
    return (
      <div className="flex flex-col gap-3">
        <h2
          className="text-lg font-bold tracking-tight text-fg"
          data-testid="auction-ended-headline"
        >
          {headline}
        </h2>
        {winnerPublicId != null ? (
          <Link
            href={`/users/${winnerPublicId}`}
            className="flex items-center gap-3 rounded-lg bg-bg-subtle p-3 hover:bg-bg-hover"
            data-testid="auction-ended-winner"
          >
            <Avatar
              src={winnerAvatarUrl}
              alt={winnerDisplayName ?? "Winner"}
              name={winnerDisplayName ?? "Winner"}
              size="md"
            />
            <div className="flex min-w-0 flex-col">
              <span className="text-sm font-semibold text-fg truncate">
                {winnerDisplayName ?? "Winner"}
              </span>
              {winnerSlName ? (
                <span className="text-[11px] font-medium text-fg-muted truncate">
                  {winnerSlName}
                </span>
              ) : null}
            </div>
          </Link>
        ) : null}
      </div>
    );
  }

  if (outcome === "RESERVE_NOT_MET") {
    return (
      <div className="flex flex-col gap-2">
        <h2
          className="text-lg font-bold tracking-tight text-fg"
          data-testid="auction-ended-headline"
        >
          Reserve not met. Auction ended without a sale.
        </h2>
        <p className="text-sm text-fg-muted">
          Highest bid was L${formatAmount(numericHighBid(auction.currentHighBid))}
        </p>
      </div>
    );
  }

  // NO_BIDS
  return (
    <div className="flex flex-col gap-2">
      <h2
        className="text-lg font-bold tracking-tight text-fg"
        data-testid="auction-ended-headline"
      >
        Ended with no bids
      </h2>
      <p className="text-sm text-fg-muted">
        Starting bid was L${auction.startingBid.toLocaleString()}
      </p>
    </div>
  );
}

/**
 * Winner-specific overlay — green banner + link to My Bids, plus the
 * role-aware escrow banner when the backend has surfaced an escrow state
 * on the auction DTO (SOLD / BOUGHT_NOW only).
 */
function WinnerOverlay({
  auctionPublicId,
  outcome,
  escrowState,
  transferConfirmedAt,
}: {
  auctionPublicId: string;
  outcome: AuctionEndOutcome;
  escrowState: EscrowState | null;
  transferConfirmedAt: string | null;
}) {
  return (
    <div
      data-testid="auction-ended-winner-overlay"
      className={cn(
        "rounded-lg bg-info-bg p-3",
        "flex flex-col gap-1 text-sm text-fg",
      )}
    >
      <span className="font-semibold">You won this auction</span>
      <Link
        href="/dashboard/bids"
        className="text-xs font-medium underline-offset-4 hover:underline"
      >
        View your winning bids
      </Link>
      {escrowState != null &&
      (outcome === "SOLD" || outcome === "BOUGHT_NOW") ? (
        <EscrowBannerForPanel
          auctionPublicId={auctionPublicId}
          escrowState={escrowState}
          transferConfirmedAt={transferConfirmedAt}
          role="winner"
        />
      ) : null}
    </div>
  );
}

/**
 * Seller-specific overlay. For unsold outcomes we explain no escrow will
 * open; for SOLD / BOUGHT_NOW outcomes we delegate the progress / CTA copy
 * to {@link EscrowBannerForPanel}, which reads the role-aware copy table
 * in {@link escrowBannerCopy}.
 */
function SellerOverlay({
  auctionPublicId,
  outcome,
  escrowState,
  transferConfirmedAt,
}: {
  auctionPublicId: string;
  outcome: AuctionEndOutcome;
  escrowState: EscrowState | null;
  transferConfirmedAt: string | null;
}) {
  const showEscrowBanner =
    escrowState != null &&
    (outcome === "SOLD" || outcome === "BOUGHT_NOW");
  const fallbackMessage =
    outcome === "SOLD" || outcome === "BOUGHT_NOW"
      ? "Your auction ended with a winning bid."
      : outcome === "RESERVE_NOT_MET"
        ? "Your auction ended without meeting the reserve. No escrow will open."
        : "Your auction ended without bids. No escrow will open.";

  return (
    <div
      data-testid="auction-ended-seller-overlay"
      className={cn(
        "rounded-lg bg-brand-soft p-3",
        "flex flex-col gap-1 text-sm text-brand",
      )}
    >
      <span className="font-semibold">Your auction</span>
      <p className="text-xs">{fallbackMessage}</p>
      {showEscrowBanner ? (
        <EscrowBannerForPanel
          auctionPublicId={auctionPublicId}
          escrowState={escrowState}
          transferConfirmedAt={transferConfirmedAt}
          role="seller"
        />
      ) : null}
    </div>
  );
}

const bannerToneClasses: Record<BannerTone, string> = {
  action: "bg-brand-soft text-brand",
  waiting: "bg-bg-muted text-fg",
  done: "bg-info-bg text-fg",
  problem: "bg-danger-bg text-danger",
  muted: "bg-bg-muted text-fg-muted",
};

interface EscrowBannerForPanelProps {
  auctionPublicId: string;
  escrowState: EscrowState;
  transferConfirmedAt: string | null;
  role: EscrowChipRole;
}

/**
 * Role- and state-aware escrow banner embedded in the winner/seller
 * overlays on the ended-auction panel. Provides the short CTA + deep link
 * to the full escrow page. {@code fundedAt} is not surfaced on the
 * auction DTO (only escrow DTOs carry it); the current banner copy table
 * does not branch on it — the escrow page's ExpiredStateCard handles the
 * pre/post-fund refund split.
 */
function EscrowBannerForPanel({
  auctionPublicId,
  escrowState,
  transferConfirmedAt,
  role,
}: EscrowBannerForPanelProps) {
  const { headline, detail, tone } = escrowBannerCopy({
    state: escrowState,
    role,
    transferConfirmedAt,
    // fundedAt not surfaced on auction DTO; banner copy doesn't branch on it.
    fundedAt: null,
  });
  return (
    <div
      data-testid="auction-ended-escrow-banner"
      data-tone={tone}
      className={cn(
        "mt-3 flex items-center gap-3 rounded-md px-3 py-2",
        bannerToneClasses[tone],
      )}
    >
      <span className="flex-1 text-sm">
        <strong>{headline}</strong>
        {detail ? <> {detail}</> : null}
      </span>
      <Link
        href={`/auction/${auctionPublicId}/escrow`}
        className="rounded-full bg-brand px-3 py-1 text-xs font-semibold text-white"
      >
        View escrow
      </Link>
    </div>
  );
}

/**
 * Backend sends {@code currentHighBid} as a BigDecimal (number | string);
 * coerce to a finite number or null so the display doesn't fall back to
 * {@code "—"} when the wire format is a string.
 */
function numericHighBid(
  value: number | string | null | undefined,
): number | null {
  if (value == null) return null;
  const n = typeof value === "string" ? Number(value) : value;
  return Number.isFinite(n) ? n : null;
}

function formatAmount(value: number | null): string {
  if (value == null) return "-";
  return value.toLocaleString();
}
