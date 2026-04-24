import type { AuctionEndOutcome } from "@/types/search";

/**
 * Visual tone applied to a chip. Maps to one of the design-system token pairs
 * in {@link StatusChip}. {@code ending_soon} additionally pulses to draw the
 * eye; {@code live} is the urgent-but-calm steady-state.
 */
export type ChipTone = "live" | "ending_soon" | "sold" | "muted" | "warning";

export type StatusChipInfo = { label: string; tone: ChipTone };

type ChipInput = {
  status: string;
  endOutcome: AuctionEndOutcome | null;
  endsAt: string;
};

const ONE_HOUR_MS = 60 * 60 * 1000;

/**
 * Derive the status chip label + tone from the DTO fields. Single source of
 * truth consumed by {@link ListingCard} and the browse page results grid,
 * per Epic 07 sub-spec 2 §8.3.
 *
 * Precedence:
 *   1. ACTIVE + endsAt &lt;= 1h → ENDING SOON (pulse).
 *   2. ACTIVE + endsAt &gt;  1h → LIVE.
 *   3. endOutcome SOLD | BOUGHT_NOW → SOLD.
 *   4. endOutcome RESERVE_NOT_MET → RESERVE NOT MET.
 *   5. endOutcome NO_BIDS → NO BIDS.
 *   6. status CANCELLED → CANCELLED.
 *   7. status SUSPENDED → SUSPENDED.
 *   8. Anything else ended → ENDED.
 */
export function deriveStatusChip(input: ChipInput): StatusChipInfo {
  const { status, endOutcome } = input;
  if (status === "ACTIVE") {
    const remaining = new Date(input.endsAt).getTime() - Date.now();
    return remaining <= ONE_HOUR_MS
      ? { label: "ENDING SOON", tone: "ending_soon" }
      : { label: "LIVE", tone: "live" };
  }
  if (endOutcome === "SOLD" || endOutcome === "BOUGHT_NOW")
    return { label: "SOLD", tone: "sold" };
  if (endOutcome === "RESERVE_NOT_MET")
    return { label: "RESERVE NOT MET", tone: "warning" };
  if (endOutcome === "NO_BIDS") return { label: "NO BIDS", tone: "muted" };
  if (status === "CANCELLED") return { label: "CANCELLED", tone: "muted" };
  if (status === "SUSPENDED") return { label: "SUSPENDED", tone: "warning" };
  return { label: "ENDED", tone: "muted" };
}
