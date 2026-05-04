// Mirrors backend AuctionSearchResultDto (com.slparcelauctions.backend.
// auction.search.AuctionSearchResultDto) and the URL query contract documented
// in Epic 07 sub-spec 1 §3. Re-exports enums from auction.ts where they
// overlap (MaturityRating, VerificationTier, AuctionEndOutcome) so search
// consumers import from a single surface.
//
// Field naming keeps camelCase client-side; the URL codec translates to/from
// snake_case for the wire contract (near_region, min_price, etc.).

import type { ParcelMaturityRating } from "./parcel";
import type { AuctionEndOutcome, VerificationTier } from "./auction";

export type { AuctionEndOutcome, VerificationTier };

/** Echo of the MD3 parcel maturity enum. Same three values server-side. */
export type MaturityRating = ParcelMaturityRating;

/**
 * Sort modes accepted by GET /api/v1/auctions/search. {@code nearest} only
 * validates when {@code near_region} is also present (see spec §3 + the
 * NEAREST_REQUIRES_NEAR_REGION / DISTANCE_REQUIRES_NEAR_REGION error codes).
 */
export type AuctionSort =
  | "newest"
  | "ending_soonest"
  | "most_bids"
  | "lowest_price"
  | "largest_area"
  | "nearest";

export type ReserveStatusFilter = "all" | "reserve_met" | "reserve_not_met" | "no_reserve";

export type SnipeFilter = "any" | "true" | "false";

export type TagsMode = "or" | "and";

/**
 * Client-only filter consumed by the authenticated saved-parcels endpoint
 * (Task 5). The /search endpoint ignores this field — the URL codec keeps it
 * so the Curator Tray can share the same query type.
 */
export type SavedStatusFilter = "active_only" | "all" | "ended_only";

/**
 * Canonical client representation of the browse/search query. The URL codec
 * in @/lib/search/url-codec owns the snake_case wire translation and default
 * stripping. Field optionality matches the backend AuctionSearchQuery shape
 * (all filters optional; only sort/page/size have defaults applied
 * server-side).
 */
export type AuctionSearchQuery = {
  region?: string;
  minArea?: number;
  maxArea?: number;
  minPrice?: number;
  maxPrice?: number;
  maturity?: MaturityRating[];
  tags?: string[];
  tagsMode?: TagsMode;
  reserveStatus?: ReserveStatusFilter;
  snipeProtection?: SnipeFilter;
  verificationTier?: VerificationTier[];
  endingWithin?: number; // hours
  nearRegion?: string;
  distance?: number; // regions
  sellerPublicId?: string;
  sort?: AuctionSort;
  page?: number;
  size?: number;
  // Saved-specific; ignored by /search.
  statusFilter?: SavedStatusFilter;
};

export type AuctionSearchResultSeller = {
  publicId: string;
  displayName: string;
  avatarUrl: string | null;
  averageRating: number | null;
  reviewCount: number | null;
};

export type AuctionSearchResultParcel = {
  auctionPublicId: string;
  name: string;
  region: string;
  area: number;
  maturity: MaturityRating;
  snapshotUrl: string | null;
  gridX: number | null;
  gridY: number | null;
  positionX: number | null;
  positionY: number | null;
  positionZ: number | null;
  tags: string[];
};

/**
 * Single card projection for the browse / featured / Curator Tray surfaces.
 * {@code endOutcome} is null for ACTIVE rows (the only shape the /search
 * endpoint returns today); populated for ended rows on the Saved-Parcels
 * response where the Curator Tray's ended_only filter surfaces SOLD /
 * RESERVE_NOT_MET / NO_BIDS chips.
 */
export type AuctionSearchResultDto = {
  publicId: string;
  title: string;
  status: string;
  endOutcome: AuctionEndOutcome | null;
  parcel: AuctionSearchResultParcel;
  primaryPhotoUrl: string | null;
  seller: AuctionSearchResultSeller;
  verificationTier: VerificationTier;
  currentBid: number;
  startingBid: number;
  reservePrice: number | null;
  reserveMet: boolean;
  buyNowPrice: number | null;
  bidCount: number;
  endsAt: string;
  snipeProtect: boolean;
  snipeWindowMin: number | null;
  distanceRegions: number | null;
};

/**
 * Paged response from GET /api/v1/auctions/search. Mirrors Spring Data's
 * PagedResponse contract — uses {@code page} (0-indexed) + {@code size}
 * rather than the native Spring {@code number} field to match the spec §3
 * body shape the frontend consumes.
 */
export type SearchResponse = {
  content: AuctionSearchResultDto[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
  meta?: {
    sortApplied?: AuctionSort;
    nearRegionResolved?: { name: string; gridX: number; gridY: number };
  };
};

/**
 * Auth-only response from GET /api/v1/users/me/saved/ids — returns the full
 * set of auction publicIds the caller has saved. Used by useSavedAuctions (Task 5)
 * to paint heart-filled state across every ListingCard on the page from a
 * single fetch.
 */
export type SavedIdsResponse = { publicIds: string[] };
