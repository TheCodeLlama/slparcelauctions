"use client";

import { Gavel } from "@/components/ui/icons";
import type {
  AuctionEndOutcome,
  PublicAuctionResponse,
  SellerAuctionResponse,
} from "@/types/auction";

export interface AuctionEndedRowProps {
  auction: PublicAuctionResponse | SellerAuctionResponse;
}

type EndedAuctionFields = {
  endOutcome?: AuctionEndOutcome | null;
  finalBidAmount?: number | null;
};

/**
 * Banner pinned above {@link BidHistoryList} when an auction has ended.
 * Deliberately lives outside the bid-history React Query cache so the
 * dedupe logic in the envelope merger never has to special-case a
 * synthetic "ended" row — everything in the cache has a real numeric
 * {@code bidId}.
 *
 * Copy:
 * <ul>
 *   <li>{@code SOLD} / {@code BOUGHT_NOW} → "Auction ended — L$N,NNN"</li>
 *   <li>{@code RESERVE_NOT_MET} / {@code NO_BIDS} → "Ended — no winner"</li>
 * </ul>
 *
 * Visually distinct from real bid rows — a bolder palette
 * ({@code secondary-container}) so the transition between live bidding
 * and terminal state is obvious at a glance.
 */
export function AuctionEndedRow({ auction }: AuctionEndedRowProps) {
  const ended = auction as (
    | PublicAuctionResponse
    | SellerAuctionResponse
  ) &
    EndedAuctionFields;

  const outcome = ended.endOutcome;
  const finalBid = ended.finalBidAmount;
  const hasWinner = outcome === "SOLD" || outcome === "BOUGHT_NOW";

  const label = hasWinner
    ? `Auction ended — L$${formatAmount(finalBid)}`
    : "Ended — no winner";

  return (
    <div
      role="note"
      data-testid="auction-ended-row"
      data-outcome={outcome ?? "UNKNOWN"}
      className="flex items-center gap-3 rounded-lg bg-bg-muted px-4 py-3 text-fg"
    >
      <Gavel className="size-5 shrink-0" aria-hidden="true" />
      <span className="text-sm font-semibold">{label}</span>
    </div>
  );
}

function formatAmount(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return "—";
  return value.toLocaleString();
}
