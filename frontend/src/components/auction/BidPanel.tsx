"use client";

import type {
  ProxyBidResponse,
  PublicAuctionResponse,
  SellerAuctionResponse,
} from "@/types/auction";
import type { ConnectionState } from "@/lib/ws/types";
import { AuctionEndedPanel } from "./AuctionEndedPanel";
import { AuthGateMessage } from "./AuthGateMessage";
import { PlaceBidForm } from "./PlaceBidForm";
import { ProxyBidSection } from "./ProxyBidSection";
import { ReconnectingBanner } from "./ReconnectingBanner";
import { ReserveStatusIndicator } from "./ReserveStatusIndicator";
import { SnipeExtensionBanner } from "./SnipeExtensionBanner";
import { SnipeProtectionBadge } from "./SnipeProtectionBadge";

export interface BidPanelUser {
  publicId: string;
  verified: boolean;
}

/**
 * Transient snipe-extension signal wired down from
 * {@code AuctionDetailClient}. The parent inspects each
 * {@code BID_SETTLEMENT} envelope's {@code newBids} for a non-null
 * {@code snipeExtensionMinutes}; when it finds one, it computes the
 * pre-formatted remaining label and flips {@code isVisible}. The banner
 * auto-schedules an {@code onExpire} callback 4s later which the parent
 * uses to clear the signal.
 *
 * The {@code token} is bumped on every new extension so React re-mounts
 * the banner via a {@code key} — this restarts the 4s timer cleanly
 * without stacking separate banners.
 */
export interface SnipeExtensionSignal {
  isVisible: boolean;
  extensionMinutes: number;
  remainingAfterExtension: string;
  token: number;
  onExpire: () => void;
}

export interface BidPanelProps {
  auction: PublicAuctionResponse | SellerAuctionResponse;
  currentUser: BidPanelUser | null;
  existingProxy: ProxyBidResponse | null;
  connectionState: ConnectionState;
  /**
   * Whether the current viewer is the current high bidder. Passed down
   * to {@link ProxyBidSection} so the Cancel button is disabled when the
   * caller is winning — the backend would return 409
   * {@code CANNOT_CANCEL_WINNING_PROXY} otherwise.
   */
  currentUserIsWinning?: boolean;
  /**
   * Transient snipe-extension banner signal. Optional — the ended panel
   * doesn't render the banner, and tests that don't care about snipe
   * extensions can omit it entirely.
   */
  snipeExtension?: SnipeExtensionSignal;
  /**
   * The auth bootstrap is in flight. When true, render the public auction
   * data (current bid, bidder count, buy-now callout) with a spinner in
   * place of the form / sign-in CTA — never flash the "Sign in to bid"
   * card to a viewer whose session is still resolving. Defaults to false.
   *
   * Without this, the loading state collapsed to the {@code unauth}
   * variant and signed-in users saw "Sign in to bid" for the duration of
   * the bootstrap (sub-second on the happy path, but multi-second when
   * the backend refresh path is degraded — see spec
   * 2026-05-16-ownership-only-verification for the related backend race).
   */
  authLoading?: boolean;
}

type Variant =
  | "loading"
  | "unauth"
  | "unverified"
  | "seller"
  | "ended"
  | "bidder";

/**
 * Selects a BidPanel variant from viewer + auction state, per spec §9's
 * variant table. Order matters:
 * <ol>
 *   <li>{@code ended} trumps everything else so a seller viewing their
 *       closed auction sees the ended panel, not the read-only seller
 *       callout.</li>
 *   <li>{@code loading} runs second so an authenticated viewer whose
 *       session is still bootstrapping doesn't briefly see the
 *       {@code unauth} CTA. The loading variant renders the same public
 *       data the unauth variant would, minus the sign-in card.</li>
 *   <li>Everything else falls through to the identity-aware variants.</li>
 * </ol>
 *
 * Exported for unit-level coverage of the dispatch matrix.
 */
export function deriveBidPanelVariant(
  auction: PublicAuctionResponse | SellerAuctionResponse,
  currentUser: BidPanelUser | null,
  authLoading: boolean = false,
): Variant {
  if (auction.status === "ENDED") return "ended";
  if (authLoading) return "loading";
  if (!currentUser) return "unauth";
  if (!currentUser.verified) return "unverified";
  if (currentUser.publicId === auction.sellerPublicId) return "seller";
  return "bidder";
}

/**
 * Bidder-variant panel content. Current bid display, reserve / snipe /
 * buy-now chips, then the two forms. Rendered as a vertical stack inside
 * the sticky sidebar slot.
 */
function BidderPanel({
  auction,
  existingProxy,
  connectionState,
  currentUserIsWinning,
  snipeExtension,
}: {
  auction: PublicAuctionResponse | SellerAuctionResponse;
  existingProxy: ProxyBidResponse | null;
  connectionState: ConnectionState;
  currentUserIsWinning: boolean;
  snipeExtension?: SnipeExtensionSignal;
}) {
  const reservePriceForIndicator =
    "reservePrice" in auction
      ? auction.reservePrice
      : auction.hasReserve
      ? auction.reserveMet
        ? 1
        : Number.POSITIVE_INFINITY
      : null;
  const currentBidForIndicator =
    "reservePrice" in auction
      ? auction.currentBid
      : auction.hasReserve
      ? auction.reserveMet
        ? 1
        : 0
      : null;

  return (
    <div
      data-testid="bid-panel-bidder"
      className="flex flex-col gap-4 rounded-xl bg-surface-raised p-6 shadow-sm"
    >
      <ReconnectingBanner state={connectionState} />
      {snipeExtension ? (
        <SnipeExtensionBanner
          key={snipeExtension.token}
          isVisible={snipeExtension.isVisible}
          extensionMinutes={snipeExtension.extensionMinutes}
          remainingAfterExtension={snipeExtension.remainingAfterExtension}
          onExpire={snipeExtension.onExpire}
        />
      ) : null}
      <CurrentBidDisplay auction={auction} />

      <div className="flex flex-wrap items-center gap-2">
        <ReserveStatusIndicator
          reservePrice={
            reservePriceForIndicator === Number.POSITIVE_INFINITY
              ? 1
              : reservePriceForIndicator
          }
          currentBid={currentBidForIndicator}
        />
        {auction.snipeProtect && auction.snipeWindowMin != null ? (
          <SnipeProtectionBadge minutes={auction.snipeWindowMin} />
        ) : null}
      </div>

      {auction.buyNowPrice != null ? (
        <div
          data-testid="bid-panel-buy-now-callout"
          className="rounded-lg bg-bg-subtle p-3 text-xs text-fg"
        >
          Buy now for{" "}
          <span className="font-semibold">
            L${auction.buyNowPrice.toLocaleString()}
          </span>
          .
        </div>
      ) : null}

      <PlaceBidForm auction={auction} connectionState={connectionState} />

      <ProxyBidSection
        auction={auction}
        existingProxy={existingProxy}
        currentUserIsWinning={currentUserIsWinning}
        connectionState={connectionState}
      />
    </div>
  );
}

/**
 * Current-bid + bidder-count readout. The countdown itself is deferred
 * to Task 6 — this Task 5 version surfaces the raw ends-at timestamp in
 * a data attribute so the layout slot stays stable and Task 6 can slot
 * the {@code CountdownTimer} in without shifting other elements.
 */
function CurrentBidDisplay({
  auction,
}: {
  auction: PublicAuctionResponse | SellerAuctionResponse;
}) {
  const high = formatHighBid(auction.currentHighBid);
  return (
    <div className="flex flex-col gap-1">
      <span className="text-xs uppercase tracking-wider text-fg-muted">
        Current bid
      </span>
      <span
        className="text-3xl font-display font-bold"
        data-testid="bid-panel-current-high"
      >
        L$ {high}
      </span>
      <span className="text-xs text-fg-muted">
        <span data-testid="bid-panel-bidder-count">{auction.bidderCount}</span>{" "}
        bids
      </span>
    </div>
  );
}

function formatHighBid(value: number | string | null): string {
  if (value == null) return "-";
  const n = typeof value === "string" ? Number(value) : value;
  if (!Number.isFinite(n)) return "-";
  return n.toLocaleString();
}

/**
 * Auth-bootstrap-in-flight variant. Renders the public auction data
 * (current bid, bidder count, buy-now callout) immediately — that data
 * is in the SSR payload and never depends on viewer identity — but
 * replaces the form / sign-in CTA slot with a quiet spinner. Resolves
 * to one of the identity-aware variants once {@code authLoading}
 * flips to false.
 *
 * Order-of-operations note: callers must pass {@code authLoading=true}
 * during the bootstrap window, not just "currentUser is null". A null
 * currentUser after the bootstrap settled is a real unauthenticated
 * viewer and should see the {@code unauth} sign-in CTA, not this
 * spinner.
 */
function BidPanelAuthLoading({
  auction,
}: {
  auction: PublicAuctionResponse | SellerAuctionResponse;
}) {
  return (
    <div
      data-testid="bid-panel-auth-loading"
      className="flex flex-col gap-4 rounded-xl bg-surface-raised p-6 shadow-sm"
      aria-busy="true"
    >
      <CurrentBidDisplay auction={auction} />

      {auction.buyNowPrice != null ? (
        <div
          data-testid="bid-panel-auth-loading-buy-now"
          className="rounded-lg bg-bg-subtle p-3 text-xs text-fg"
        >
          Buy now for{" "}
          <span className="font-semibold">
            L${auction.buyNowPrice.toLocaleString()}
          </span>
          .
        </div>
      ) : null}

      <div
        className="flex flex-col items-center justify-center gap-2 rounded-lg bg-bg-subtle px-4 py-6 text-xs text-fg-muted"
        role="status"
        aria-live="polite"
      >
        <span
          aria-hidden="true"
          className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-border border-t-brand"
        />
        <span>Checking sign-in&hellip;</span>
      </div>
    </div>
  );
}

/**
 * Variant dispatcher for the auction bid panel. Lives in the sticky
 * sidebar slot of {@code AuctionDetailClient} — one mount per page.
 *
 * Variants per spec §9:
 * <ul>
 *   <li>{@code unauth}     → {@link AuthGateMessage}</li>
 *   <li>{@code unverified} → {@link AuthGateMessage}</li>
 *   <li>{@code seller}     → {@link AuthGateMessage}</li>
 *   <li>{@code ended}      → Task 6 stub (full {@code AuctionEndedPanel}
 *       ships there)</li>
 *   <li>{@code bidder}     → {@link BidderPanel} with the two forms</li>
 * </ul>
 */
export function BidPanel({
  auction,
  currentUser,
  existingProxy,
  connectionState,
  currentUserIsWinning = false,
  snipeExtension,
  authLoading = false,
}: BidPanelProps) {
  const variant = deriveBidPanelVariant(auction, currentUser, authLoading);

  switch (variant) {
    case "loading":
      return <BidPanelAuthLoading auction={auction} />;
    case "unauth":
      return <AuthGateMessage kind="unauth" auctionPublicId={auction.publicId} />;
    case "unverified":
      return <AuthGateMessage kind="unverified" />;
    case "seller":
      return <AuthGateMessage kind="seller" />;
    case "ended":
      return (
        <AuctionEndedPanel
          auction={auction}
          currentUser={currentUser ? { publicId: currentUser.publicId } : null}
        />
      );
    case "bidder":
    default:
      return (
        <BidderPanel
          auction={auction}
          existingProxy={existingProxy}
          connectionState={connectionState}
          currentUserIsWinning={currentUserIsWinning}
          snipeExtension={snipeExtension}
        />
      );
  }
}
