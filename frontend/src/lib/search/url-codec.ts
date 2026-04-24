import type {
  AuctionSearchQuery,
  AuctionSort,
  MaturityRating,
  ReserveStatusFilter,
  SnipeFilter,
  TagsMode,
  VerificationTier,
  SavedStatusFilter,
} from "@/types/search";

/**
 * Baseline query applied when URLSearchParams is empty. Values that match the
 * defaults are stripped on encode so deep links stay short and shareable.
 * sort = newest + size = 24 mirrors the backend's own defaults (spec §3).
 */
export const defaultAuctionSearchQuery: AuctionSearchQuery = {
  sort: "newest",
  page: 0,
  size: 24,
};

const VALID_MATURITY: ReadonlyArray<MaturityRating> = ["GENERAL", "MODERATE", "ADULT"];
const VALID_TIER: ReadonlyArray<VerificationTier> = ["SCRIPT", "BOT", "OWNERSHIP_TRANSFER"];
const VALID_SORT: ReadonlyArray<AuctionSort> = [
  "newest",
  "ending_soonest",
  "most_bids",
  "lowest_price",
  "largest_area",
  "nearest",
];
const VALID_RESERVE: ReadonlyArray<ReserveStatusFilter> = [
  "all",
  "reserve_met",
  "reserve_not_met",
  "no_reserve",
];
const VALID_SNIPE: ReadonlyArray<SnipeFilter> = ["any", "true", "false"];
const VALID_TAGS_MODE: ReadonlyArray<TagsMode> = ["or", "and"];
const VALID_STATUS_FILTER: ReadonlyArray<SavedStatusFilter> = [
  "active_only",
  "all",
  "ended_only",
];

function parseCsv<T extends string>(
  v: string | null,
  valid: ReadonlyArray<T>,
): T[] | undefined {
  if (!v) return undefined;
  const parts = v
    .split(",")
    .map((x) => x.trim())
    .filter((x): x is T => (valid as ReadonlyArray<string>).includes(x));
  return parts.length > 0 ? parts : undefined;
}

function parseInt64(v: string | null): number | undefined {
  if (v === null || v === "") return undefined;
  const n = Number(v);
  return Number.isFinite(n) && Number.isInteger(n) ? n : undefined;
}

function parseEnum<T extends string>(
  v: string | null,
  valid: ReadonlyArray<T>,
): T | undefined {
  if (v === null) return undefined;
  return (valid as ReadonlyArray<string>).includes(v) ? (v as T) : undefined;
}

/**
 * Decode a URLSearchParams into a canonical {@link AuctionSearchQuery}.
 * Unknown keys are dropped silently, invalid values are rejected, and any
 * missing key falls back to {@link defaultAuctionSearchQuery}.
 *
 * Invariant: queryFromSearchParams(searchParamsFromQuery(q)) deep-equals q
 * for any query whose field set is the documented filter surface.
 */
export function queryFromSearchParams(sp: URLSearchParams): AuctionSearchQuery {
  const q: AuctionSearchQuery = { ...defaultAuctionSearchQuery };

  const region = sp.get("region");
  if (region) q.region = region;

  const minArea = parseInt64(sp.get("min_area"));
  if (minArea !== undefined) q.minArea = minArea;
  const maxArea = parseInt64(sp.get("max_area"));
  if (maxArea !== undefined) q.maxArea = maxArea;

  const minPrice = parseInt64(sp.get("min_price"));
  if (minPrice !== undefined) q.minPrice = minPrice;
  const maxPrice = parseInt64(sp.get("max_price"));
  if (maxPrice !== undefined) q.maxPrice = maxPrice;

  const maturity = parseCsv(sp.get("maturity"), VALID_MATURITY);
  if (maturity) q.maturity = maturity;

  const tags = sp
    .get("tags")
    ?.split(",")
    .map((x) => x.trim())
    .filter(Boolean);
  if (tags && tags.length > 0) q.tags = tags;

  const tagsMode = parseEnum(sp.get("tags_mode"), VALID_TAGS_MODE);
  if (tagsMode) q.tagsMode = tagsMode;

  const reserveStatus = parseEnum(sp.get("reserve_status"), VALID_RESERVE);
  if (reserveStatus) q.reserveStatus = reserveStatus;

  const snipeProtection = parseEnum(sp.get("snipe_protection"), VALID_SNIPE);
  if (snipeProtection) q.snipeProtection = snipeProtection;

  const tier = parseCsv(sp.get("verification_tier"), VALID_TIER);
  if (tier) q.verificationTier = tier;

  const endingWithin = parseInt64(sp.get("ending_within"));
  if (endingWithin !== undefined) q.endingWithin = endingWithin;

  const nearRegion = sp.get("near_region");
  if (nearRegion) q.nearRegion = nearRegion;

  const distance = parseInt64(sp.get("distance"));
  if (distance !== undefined) q.distance = distance;

  const sellerId = parseInt64(sp.get("seller_id"));
  if (sellerId !== undefined) q.sellerId = sellerId;

  const sort = parseEnum(sp.get("sort"), VALID_SORT);
  if (sort) q.sort = sort;

  const page = parseInt64(sp.get("page"));
  if (page !== undefined && page > 0) q.page = page;

  const size = parseInt64(sp.get("size"));
  if (size !== undefined && size !== 24) q.size = size;

  const statusFilter = parseEnum(sp.get("status_filter"), VALID_STATUS_FILTER);
  if (statusFilter) q.statusFilter = statusFilter;

  return q;
}

function putIf<T>(
  sp: URLSearchParams,
  key: string,
  value: T | undefined,
  toStr: (v: T) => string,
): void {
  if (value === undefined || value === null) return;
  sp.set(key, toStr(value));
}

/**
 * Encode a {@link AuctionSearchQuery} back into URLSearchParams. Defaults
 * (sort=newest, size=24, page=0, tagsMode=or, reserveStatus=all,
 * snipeProtection=any, statusFilter=active_only) are dropped so the URL
 * stays compact. CSV encoding is used for multi-select arrays.
 */
export function searchParamsFromQuery(q: AuctionSearchQuery): URLSearchParams {
  const sp = new URLSearchParams();
  putIf(sp, "region", q.region, (v) => v);
  putIf(sp, "min_area", q.minArea, String);
  putIf(sp, "max_area", q.maxArea, String);
  putIf(sp, "min_price", q.minPrice, String);
  putIf(sp, "max_price", q.maxPrice, String);
  if (q.maturity?.length) sp.set("maturity", q.maturity.join(","));
  if (q.tags?.length) sp.set("tags", q.tags.join(","));
  if (q.tagsMode && q.tagsMode !== "or") sp.set("tags_mode", q.tagsMode);
  if (q.reserveStatus && q.reserveStatus !== "all")
    sp.set("reserve_status", q.reserveStatus);
  if (q.snipeProtection && q.snipeProtection !== "any")
    sp.set("snipe_protection", q.snipeProtection);
  if (q.verificationTier?.length)
    sp.set("verification_tier", q.verificationTier.join(","));
  putIf(sp, "ending_within", q.endingWithin, String);
  putIf(sp, "near_region", q.nearRegion, (v) => v);
  putIf(sp, "distance", q.distance, String);
  putIf(sp, "seller_id", q.sellerId, String);
  if (q.sort && q.sort !== "newest") sp.set("sort", q.sort);
  if (q.page !== undefined && q.page > 0) sp.set("page", String(q.page));
  if (q.size !== undefined && q.size !== 24) sp.set("size", String(q.size));
  if (q.statusFilter && q.statusFilter !== "active_only")
    sp.set("status_filter", q.statusFilter);
  return sp;
}
