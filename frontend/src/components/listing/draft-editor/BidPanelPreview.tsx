"use client";
import { Input } from "@/components/ui/Input";
import { sampleCurrentBid, sampleBidderCount } from "./SampleBidHistory";

/**
 * Read-only sample-data variant of the buyer's BidPanel. Renders the
 * same visual structure — current bid, bidder count, time-remaining,
 * disabled bid input — populated from {@link SAMPLE_BIDS} so the seller
 * sees what the right-rail will look like once the auction is active.
 *
 * No WebSocket subscription, no auth read, no mutations.
 */
export interface BidPanelPreviewProps {
  startingBid: number;
  buyNowPrice: number | null;
  reservePrice: number | null;
  durationHours: number;
}

export function BidPanelPreview({
  startingBid,
  buyNowPrice,
  reservePrice,
  durationHours,
}: BidPanelPreviewProps) {
  const currentBid = sampleCurrentBid();
  const bidders = sampleBidderCount();

  return (
    <div
      data-testid="bid-panel-preview"
      className="flex flex-col gap-4 rounded-lg bg-surface-raised p-5 ring-1 ring-border-subtle"
    >
      <div className="flex items-center gap-2">
        <h3 className="text-sm font-semibold tracking-tight text-fg">
          Bid panel
        </h3>
        <span
          data-testid="bid-panel-preview-sample-pill"
          className="rounded-full bg-brand-soft px-2 py-0.5 text-[10px] font-medium uppercase tracking-wide text-brand"
        >
          Sample
        </span>
      </div>
      <dl className="flex flex-col gap-2">
        <div className="flex items-baseline justify-between">
          <dt className="text-xs font-medium uppercase text-fg-muted">
            Current bid
          </dt>
          <dd className="text-lg font-bold text-fg">
            L${currentBid.toLocaleString()}
          </dd>
        </div>
        <div className="flex items-baseline justify-between text-xs text-fg-muted">
          <dt>Starting bid</dt>
          <dd>L${startingBid.toLocaleString()}</dd>
        </div>
        {buyNowPrice != null && buyNowPrice > 0 && (
          <div className="flex items-baseline justify-between text-xs text-fg-muted">
            <dt>Buy it now</dt>
            <dd>L${buyNowPrice.toLocaleString()}</dd>
          </div>
        )}
        {reservePrice != null && reservePrice > 0 && (
          <div className="flex items-baseline justify-between text-xs text-fg-muted">
            <dt>Reserve</dt>
            <dd>Set</dd>
          </div>
        )}
        <div className="flex items-baseline justify-between text-xs text-fg-muted">
          <dt>Bidders</dt>
          <dd>{bidders}</dd>
        </div>
        <div className="flex items-baseline justify-between text-xs text-fg-muted">
          <dt>Time remaining</dt>
          <dd>Runs for {durationHours}h when activated</dd>
        </div>
      </dl>
      <div className="flex flex-col gap-1">
        <Input
          type="number"
          value=""
          onChange={() => {}}
          disabled
          placeholder={`Min L$${(currentBid + 1).toLocaleString()}`}
          aria-label="Bid amount (preview)"
          data-testid="bid-panel-preview-input"
        />
        <p className="text-[11px] text-fg-muted">
          Listing not yet active.
        </p>
      </div>
    </div>
  );
}
