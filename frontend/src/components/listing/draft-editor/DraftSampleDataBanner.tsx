/**
 * Top-of-page note that the populated bid history + right-rail values on the
 * DRAFT preview are sample data, not real bids. Pairs with the "Sample" pills
 * on {@link BidHistoryList} (sample mode) and {@link BidPanelPreview}.
 */
export function DraftSampleDataBanner() {
  return (
    <div
      role="note"
      data-testid="draft-sample-data-banner"
      className="rounded-lg bg-brand-soft px-4 py-2 text-xs text-brand"
    >
      This is a preview with sample bids and activity. Your live listing will start empty.
    </div>
  );
}
