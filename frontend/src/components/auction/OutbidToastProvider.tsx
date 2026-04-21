"use client";

import type { BidSettlementEnvelope } from "@/types/auction";

/**
 * Minimal toast surface consumed by {@link OutbidToastProvider.maybeFire}.
 * Decoupled from {@code useToast} so the callsite can pass a memoised
 * handle it already has in scope; also keeps the unit tests from having
 * to render a {@code ToastProvider} just to exercise the guard logic.
 *
 * The project's concrete toast hook exposes {@code success} + {@code error}
 * only — spec §15 asks for a "warning" variant but the existing toast
 * infra doesn't have one, so outbid displacements surface via
 * {@code error(message)}. If a warning variant lands later, swap the
 * call site; the guard logic here does not change.
 */
export interface OutbidToastHandle {
  error(message: string): void;
}

/**
 * Anything that carries an optional {@code currentBidderId} — the pre-
 * settlement cache entry. {@link AuctionDetailClient}'s cache is typed
 * as {@code PublicAuctionResponse | SellerAuctionResponse} widened with
 * a partial-seller mixin, both of which now persist the bidder id from
 * each settlement envelope via {@code handleEnvelope}. We accept a
 * structural shape here so the callsite doesn't have to re-import the
 * cache entry type.
 */
export type AuctionSnapshot =
  | { currentBidderId?: number | null }
  | null
  | undefined;

/**
 * In-page outbid signal, per spec §15. Fires a transient toast when an
 * incoming {@link BidSettlementEnvelope} displaces the caller from the
 * current-high position.
 *
 * Guards:
 * <ul>
 *   <li>{@code currentUserId == null} — anonymous viewer.</li>
 *   <li>{@code prevAuction == null} — first envelope on stale cache
 *       (nothing to compare against).</li>
 *   <li>{@code prevAuction.currentBidderId !== currentUserId} — caller
 *       wasn't winning, so they can't be displaced.</li>
 *   <li>{@code env.currentBidderId === currentUserId} — caller is still
 *       winning (e.g. their own proxy counter-bid arrived in an
 *       envelope). A duplicate envelope with the same bidder id also
 *       lands here — no re-fire.</li>
 * </ul>
 *
 * Out of scope: canonical email / SL IM notifications — those ship with
 * Epic 09. This toast is an in-page signal only for an actively viewing
 * caller.
 */
export const OutbidToastProvider = {
  maybeFire(
    prevAuction: AuctionSnapshot,
    env: BidSettlementEnvelope,
    currentUserId: number | null | undefined,
    toast: OutbidToastHandle,
  ): void {
    if (currentUserId == null) return;
    if (!prevAuction) return;
    const wasWinning = prevAuction.currentBidderId === currentUserId;
    const nowLosing = env.currentBidderId !== currentUserId;
    if (!(wasWinning && nowLosing)) return;
    toast.error(
      `You've been outbid — current bid is L$${formatAmount(env.currentBid)}.`,
    );
    scrollToBidPanel();
  },
};

function formatAmount(n: number): string {
  return n.toLocaleString("en-US");
}

/**
 * Scrolls the viewport to the sidebar BidPanel slot (or its mobile
 * equivalent). SSR-guarded — this runs only in the envelope handler,
 * which is itself client-side, but keep the guard so the module stays
 * safe to import from a server component in a future refactor.
 */
function scrollToBidPanel(): void {
  if (typeof window === "undefined" || typeof document === "undefined") return;
  const el =
    document.querySelector('[data-testid="bid-panel-slot"]') ??
    document.querySelector('[data-testid="bid-panel-bidder"]');
  // {@code scrollIntoView} is not implemented in every test environment
  // (JSDOM, happy-dom ≤ 17) — guard so the in-page outbid signal never
  // crashes the envelope handler even when the DOM slot is missing.
  if (
    el instanceof HTMLElement &&
    typeof el.scrollIntoView === "function"
  ) {
    el.scrollIntoView({ behavior: "smooth", block: "start" });
  }
}
