import { StatusBadge } from "@/components/ui/StatusBadge";

/**
 * Chip indicating reserve-met state. The reserve AMOUNT is never rendered —
 * per DESIGN.md and spec §5 bidders see only whether the current top bid
 * clears the seller's threshold, not the threshold itself.
 *
 * Branch semantics:
 *   reservePrice == null            → no reserve set; render nothing
 *   currentBid  == null             → reserve set, zero bids       → "Reserve not met"
 *   currentBid  <  reservePrice     → reserve set, under threshold → "Reserve not met"
 *   currentBid  >= reservePrice     → reserve cleared              → "Reserve met"
 *
 * Public callers (PublicAuctionResponse) should derive
 * {@code reservePrice} indirectly — the public DTO only exposes
 * {@code hasReserve} + {@code reserveMet} precomputed server-side, which
 * the call site translates to a synthetic reservePrice=1/currentBid=1-or-0
 * pair (or calls this component only on the seller path). Seller callers
 * (SellerAuctionResponse) pass {@code auction.reservePrice} directly.
 */
interface Props {
  reservePrice: number | null;
  currentBid: number | null;
}

export function ReserveStatusIndicator({ reservePrice, currentBid }: Props) {
  if (reservePrice == null) return null;

  const met = currentBid != null && currentBid >= reservePrice;

  if (met) {
    return (
      <StatusBadge
        tone="success"
        data-testid="reserve-status-indicator"
        data-state="met"
      >
        Reserve met
      </StatusBadge>
    );
  }

  return (
    <StatusBadge
      tone="warning"
      data-testid="reserve-status-indicator"
      data-state="not-met"
    >
      Reserve not met
    </StatusBadge>
  );
}
