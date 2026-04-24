export interface HeadlineInput {
  title?: string | null;
  parcelDescription?: string | null;
  regionName: string;
}

/**
 * Resolves the primary headline for a listing surface.
 *
 * Fallback chain:
 *   1. auction.title (if non-blank)
 *   2. parcel.description (if non-blank) — legacy listings from before
 *      Epic 07 sub-spec 1 Task 2 introduced the title field
 *   3. parcel.regionName (always present server-side)
 *
 * Centralized here so ListingCard, ListingPreviewCard, ListingSummaryRow,
 * and ParcelInfoPanel render the same label for any given auction.
 */
export function resolveListingHeadline(input: HeadlineInput): string {
  return (
    input.title?.trim() ||
    input.parcelDescription?.trim() ||
    input.regionName
  );
}
