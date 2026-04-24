"use client";

import type { BidSettlementEnvelope } from "@/types/auction";
import type { ToastPayload } from "@/components/ui/Toast";

/**
 * Minimal toast surface consumed by {@link OutbidToastProvider.maybeFire}.
 * Decoupled from {@code useToast} so the callsite can pass a memoised
 * handle it already has in scope; also keeps the unit tests from having
 * to render a {@code ToastProvider} just to exercise the guard logic.
 *
 * Epic 07 sub-spec 2 widened the project-level toast primitive with a
 * {@code warning} variant + structured payload, so the outbid signal now
 * fires via {@code warning({ title, description, action })} with an
 * explicit "Place a new bid" action button instead of a plain error
 * string plus an imperative scroll side-effect.
 */
export interface OutbidToastHandle {
  warning(payload: string | ToastPayload): void;
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
 * In-page outbid signal, per spec §15. Fires a transient
 * {@code toast.warning} with a structured "Place a new bid" action
 * button when an incoming {@link BidSettlementEnvelope} displaces the
 * caller from the current-high position. The action button owns the
 * scroll side-effect (previously an imperative {@code scrollIntoView}
 * call on every fire); users who want to stay where they are can simply
 * ignore the button.
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
    toast.warning({
      title: "You've been outbid",
      description: `Current bid is L$${formatAmount(env.currentBid)}.`,
      action: {
        label: "Place a new bid",
        onClick: scrollToBidPanel,
      },
    });
  },
};

function formatAmount(n: number): string {
  return n.toLocaleString("en-US");
}

/**
 * Scrolls the viewport to the sidebar BidPanel slot (or its mobile
 * equivalent). Invoked by the toast's "Place a new bid" action button —
 * the outbid fire itself no longer scrolls, so users who don't want to
 * jump can simply ignore the button.
 *
 * SSR-guarded — this is only reachable from a browser click handler,
 * but keep the guard so the module stays safe to import from a server
 * component in a future refactor.
 */
function scrollToBidPanel(): void {
  if (typeof window === "undefined" || typeof document === "undefined") return;
  const el =
    document.querySelector('[data-testid="bid-panel-slot"]') ??
    document.querySelector('[data-testid="bid-panel-bidder"]');
  // {@code scrollIntoView} is not implemented in every test environment
  // (JSDOM, happy-dom ≤ 17) — guard so the action-button onClick never
  // crashes when the DOM slot is missing.
  if (
    el instanceof HTMLElement &&
    typeof el.scrollIntoView === "function"
  ) {
    el.scrollIntoView({ behavior: "smooth", block: "start" });
  }
}
