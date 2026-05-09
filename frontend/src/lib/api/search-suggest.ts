import { api } from "@/lib/api";

export type SuggestListing = {
  publicId: string;
  title: string;
  regionName: string;
  parcelName: string | null;
  /** Relative URL — wrap in {@code apiUrl()} at render. Null when the
   *  listing has no photo. */
  primaryPhotoUrl: string | null;
  currentBid: number;
};

export type SuggestRegion = {
  name: string;
  activeAuctionCount: number;
};

export type SuggestResponse = {
  listings: SuggestListing[];
  regions: SuggestRegion[];
  /** Total ACTIVE auctions matching the query. Powers the popover's
   *  "See all N results" footer; the footer is hidden when this equals
   *  {@code listings.length}. */
  totalListings: number;
};

export const searchSuggestApi = {
  suggest: (q: string) =>
    api.get<SuggestResponse>(
      `/api/v1/search/suggest?q=${encodeURIComponent(q)}`,
    ),
};
