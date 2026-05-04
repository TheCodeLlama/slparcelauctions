"use client";

import Link from "next/link";
import { useMemo } from "react";
import type {
  AuctionEndOutcome,
  PublicAuctionResponse,
  SellerAuctionResponse,
} from "@/types/auction";
import type { ConnectionState } from "@/lib/ws/types";
import { Button } from "@/components/ui/Button";
import { CountdownTimer } from "@/components/ui/CountdownTimer";
import { Loader2 } from "@/components/ui/icons";
import { cn } from "@/lib/cn";

/**
 * Viewer context required by {@link StickyBidBar} to pick its variant.
 * Mirrors {@code BidPanelUser} in shape but is redefined locally so the
 * sticky bar can be rendered from pages that don't want a full bid-panel
 * mount (e.g. a future listing preview).
 */
export interface StickyBidBarUser {
  publicId: string;
  verified: boolean;
}

export interface StickyBidBarProps {
  auction: PublicAuctionResponse | SellerAuctionResponse;
  currentUser: StickyBidBarUser | null;
  connectionState: ConnectionState;
  /**
   * Invoked when the viewer taps the primary "Bid now" CTA in the
   * {@code bidder} variant. The parent owns the sheet open state —
   * {@link StickyBidBar} is stateless.
   */
  onOpenSheet: () => void;
}

/**
 * Cache-widened view of the auction object. The envelope handler in
 * {@code AuctionDetailClient} writes {@code endOutcome},
 * {@code finalBidAmount}, {@code winnerUserId}, and
 * {@code winnerDisplayName} even when the incoming DTO is the public
 * shape; the sticky bar reads them defensively for the {@code ended}
 * variant's copy.
 */
type EndedExtensions = {
  endOutcome?: AuctionEndOutcome | null;
  finalBidAmount?: number | null;
  winnerUserId?: number | null;
  winnerDisplayName?: string | null;
};

type Variant = "unauth" | "unverified" | "seller" | "bidder" | "ended";

/**
 * Picks the sticky-bar variant from viewer + auction state. Mirrors
 * {@code deriveBidPanelVariant} in {@link BidPanel} — terminal state
 * trumps viewer-specific variants so a seller viewing their closed
 * auction sees the {@code ended} copy, not the read-only seller row.
 */
function deriveVariant(
  auction: PublicAuctionResponse | SellerAuctionResponse,
  currentUser: StickyBidBarUser | null,
): Variant {
  if (auction.status === "ENDED") return "ended";
  if (!currentUser) return "unauth";
  if (!currentUser.verified) return "unverified";
  if (currentUser.publicId === auction.sellerPublicId) return "seller";
  return "bidder";
}

/**
 * Mobile sticky bid bar — pinned to viewport bottom below the {@code lg}
 * breakpoint via a parent {@code lg:hidden} wrapper in
 * {@code AuctionDetailClient}. Renders a compact current-bid +
 * countdown readout plus a variant-specific CTA; the CTA opens the
 * {@link BidSheet} bottom sheet for the {@code bidder} variant and
 * links out to login / verification for the non-bidder gates.
 *
 * See spec §7 (mobile pattern) and §11 ({@code StickyBidBar} details).
 */
export function StickyBidBar({
  auction,
  currentUser,
  connectionState,
  onOpenSheet,
}: StickyBidBarProps) {
  const variant = deriveVariant(auction, currentUser);
  const connected = connectionState.status === "connected";
  const ended = auction as (
    | PublicAuctionResponse
    | SellerAuctionResponse
  ) &
    EndedExtensions;

  // Memoise the {@code Date} instance so {@link CountdownTimer}'s
  // effect doesn't tear down every render.
  const expiresAt = useMemo(
    () => (auction.endsAt ? new Date(auction.endsAt) : null),
    [auction.endsAt],
  );

  return (
    <div
      data-testid="sticky-bid-bar"
      data-variant={variant}
      data-ws-state={connectionState.status}
      className={cn(
        // Fixed-bottom positioning, glass background via Tailwind 4 alpha
        // modifiers (bg-color/alpha). Safe-area bottom padding falls back
        // to the static {@code env(safe-area-inset-bottom)} expression
        // since this project doesn't have a {@code pb-safe} utility.
        "fixed bottom-0 left-0 right-0 z-40",
        "bg-surface-raised/92",
        "backdrop-blur-md",
        "border-t border-border-subtle/30",
        "px-4 py-3 pb-[calc(env(safe-area-inset-bottom)+0.75rem)]",
      )}
    >
      <div className="flex items-center justify-between gap-3">
        {variant === "seller" ? (
          <SellerLeft auction={auction} />
        ) : variant === "ended" ? (
          <EndedLeft auction={auction} ended={ended} />
        ) : (
          <BidReadout auction={auction} expiresAt={expiresAt} />
        )}

        <RightSlot
          variant={variant}
          auctionPublicId={auction.publicId}
          connected={connected}
          onOpenSheet={onOpenSheet}
        />
      </div>
    </div>
  );
}

/**
 * Left-hand readout for the live variants ({@code unauth},
 * {@code unverified}, {@code bidder}). Shows the current high bid on
 * top with an inline countdown beneath.
 */
function BidReadout({
  auction,
  expiresAt,
}: {
  auction: PublicAuctionResponse | SellerAuctionResponse;
  expiresAt: Date | null;
}) {
  const high = formatBid(auction.currentHighBid);
  return (
    <div className="flex min-w-0 flex-col gap-0.5">
      <span
        data-testid="sticky-bid-bar-high"
        className="text-sm font-semibold tracking-tight font-bold text-fg"
      >
        L$ {high}
      </span>
      {expiresAt ? (
        <span className="text-[11px] font-medium text-fg-muted flex items-center gap-1">
          <span>Ends in</span>
          <CountdownTimer
            expiresAt={expiresAt}
            format="hh:mm:ss"
            className="!text-[11px] font-medium font-normal"
          />
        </span>
      ) : null}
    </div>
  );
}

/**
 * Seller left block — "Your auction — L$X" read-only copy.
 */
function SellerLeft({
  auction,
}: {
  auction: PublicAuctionResponse | SellerAuctionResponse;
}) {
  const high = formatBid(auction.currentHighBid);
  return (
    <div className="flex min-w-0 flex-col gap-0.5">
      <span className="text-[11px] font-medium text-fg-muted">
        Your auction
      </span>
      <span
        data-testid="sticky-bid-bar-high"
        className="text-sm font-semibold tracking-tight font-bold text-fg"
      >
        L$ {high}
      </span>
    </div>
  );
}

/**
 * Ended left block — outcome-specific copy mirroring the
 * {@link AuctionEndedPanel} headlines at sticky-bar density.
 */
function EndedLeft({
  auction,
  ended,
}: {
  auction: PublicAuctionResponse | SellerAuctionResponse;
  ended: EndedExtensions;
}) {
  const outcome = ended.endOutcome ?? inferOutcome(auction);
  const finalBid = ended.finalBidAmount ?? numericBid(auction.currentHighBid);

  if (outcome === "SOLD" || outcome === "BOUGHT_NOW") {
    const winnerLabel =
      ended.winnerDisplayName && ended.winnerDisplayName.trim().length > 0
        ? `@${ended.winnerDisplayName}`
        : ended.winnerUserId != null
          ? `User ${ended.winnerUserId}`
          : null;
    return (
      <div
        className="flex min-w-0 flex-col gap-0.5"
        data-testid="sticky-bid-bar-ended"
      >
        <span className="text-[11px] font-medium text-fg-muted">
          {outcome === "BOUGHT_NOW" ? "Bought now" : "Sold"}
        </span>
        <span className="truncate text-sm font-semibold tracking-tight font-bold text-fg">
          L${formatAmount(finalBid)}
          {winnerLabel ? (
            <span className="text-[11px] font-medium font-normal text-fg-muted">
              {" "}
              to {winnerLabel}
            </span>
          ) : null}
        </span>
      </div>
    );
  }

  if (outcome === "RESERVE_NOT_MET") {
    return (
      <div
        className="flex min-w-0 flex-col gap-0.5"
        data-testid="sticky-bid-bar-ended"
      >
        <span className="text-[11px] font-medium text-fg-muted">
          Reserve not met
        </span>
        <span className="text-sm font-semibold tracking-tight font-bold text-fg">
          Ended without a sale
        </span>
      </div>
    );
  }

  // NO_BIDS
  return (
    <div
      className="flex min-w-0 flex-col gap-0.5"
      data-testid="sticky-bid-bar-ended"
    >
      <span className="text-[11px] font-medium text-fg-muted">Ended</span>
      <span className="text-sm font-semibold tracking-tight font-bold text-fg">No bids</span>
    </div>
  );
}

/**
 * Right-hand CTA slot. Variant-driven: {@code bidder} renders a primary
 * button that opens the sheet, the gated variants render Links, and the
 * read-only variants ({@code seller}, {@code ended}) render nothing.
 */
function RightSlot({
  variant,
  auctionPublicId,
  connected,
  onOpenSheet,
}: {
  variant: Variant;
  auctionPublicId: string;
  connected: boolean;
  onOpenSheet: () => void;
}) {
  if (variant === "seller" || variant === "ended") {
    return null;
  }

  if (variant === "unauth") {
    const next = `?next=${encodeURIComponent(`/auction/${auctionPublicId}`)}`;
    return (
      <Link
        href={`/login${next}`}
        data-testid="sticky-bid-bar-cta"
        data-kind="signin"
        className="inline-flex h-11 shrink-0 items-center justify-center gap-2 rounded-lg bg-brand px-5 text-sm font-medium text-white shadow-sm transition-all hover:shadow-md"
      >
        Sign in to bid
      </Link>
    );
  }

  if (variant === "unverified") {
    return (
      <Link
        href="/dashboard/overview"
        data-testid="sticky-bid-bar-cta"
        data-kind="verify"
        className="inline-flex h-11 shrink-0 items-center justify-center gap-2 rounded-lg bg-brand px-5 text-sm font-medium text-white shadow-sm transition-all hover:shadow-md"
      >
        Verify to bid
      </Link>
    );
  }

  // bidder
  if (!connected) {
    return (
      <Button
        data-testid="sticky-bid-bar-cta"
        data-kind="bid"
        data-disabled-reason="disconnected"
        disabled
        variant="primary"
        size="md"
        aria-label="Reconnecting"
      >
        <Loader2 className="size-4 animate-spin" aria-hidden="true" />
        <span>Reconnecting…</span>
      </Button>
    );
  }

  return (
    <Button
      data-testid="sticky-bid-bar-cta"
      data-kind="bid"
      type="button"
      variant="primary"
      size="md"
      onClick={onOpenSheet}
    >
      Bid now
    </Button>
  );
}

function formatBid(value: number | string | null): string {
  if (value == null) return "-";
  const n = typeof value === "string" ? Number(value) : value;
  if (!Number.isFinite(n)) return "-";
  return n.toLocaleString();
}

function numericBid(value: number | string | null): number | null {
  if (value == null) return null;
  const n = typeof value === "string" ? Number(value) : value;
  return Number.isFinite(n) ? n : null;
}

function formatAmount(value: number | null): string {
  if (value == null) return "-";
  return value.toLocaleString();
}

/**
 * Local defensive fallback when the cache hasn't yet been written by the
 * envelope handler (e.g. the REST fetch returned an already-{@code ENDED}
 * DTO) — infer outcome from the fields the DTO does carry. See FOOTGUNS
 * §F.85 for why the cache-side {@code inferOutcomeFromDto} and
 * {@code inferEndOutcome} helpers were retired in Epic 05 sub-spec 2;
 * this {@code StickyBidBar}-local helper stays because the sticky bar can
 * render in a narrow window before the envelope handler hydrates the cache.
 */
function inferOutcome(
  auction: PublicAuctionResponse | SellerAuctionResponse,
): AuctionEndOutcome {
  const high = numericBid(auction.currentHighBid);
  if (high == null || auction.bidCount === 0) return "NO_BIDS";
  if (
    "buyNowPrice" in auction &&
    auction.buyNowPrice != null &&
    high >= auction.buyNowPrice
  ) {
    return "BOUGHT_NOW";
  }
  if ("hasReserve" in auction && auction.hasReserve && !auction.reserveMet) {
    return "RESERVE_NOT_MET";
  }
  if (
    "reservePrice" in auction &&
    auction.reservePrice != null &&
    high < auction.reservePrice
  ) {
    return "RESERVE_NOT_MET";
  }
  return "SOLD";
}
