import { api } from "@/lib/api";
import type { Page } from "@/types/page";
import type { GroupRating } from "@/types/realty";

/**
 * Sort key the {@code /groups} directory emits in the {@code ?sort=...} query
 * param. Must stay in lockstep with the backend {@code GroupsSortKey} enum at
 * {@code backend/.../realty/browse/GroupsSortKey.java}.
 *
 * Mirrored here (rather than imported from {@code @/types/realty}) so Task 10
 * can ship parallel to Task 8 without a race on the shared type file. The
 * import sweep in Task 8 will consolidate these.
 */
export type GroupsSortKey =
  | "RATING"
  | "NEWEST"
  | "MOST_ACTIVE_LISTINGS"
  | "MOST_SALES";

/**
 * Wire shape for one card on the public groups directory. Mirrors the backend
 * {@code RealtyGroupCardDto} record (spec section 6.1).
 *
 * Logo + cover URLs ship as dual light/dark variants (plan
 * `2026-05-21-theme-image-variants`); each is a relative path - callers
 * render via {@code apiUrl(...)}. Either variant may be null; the
 * {@code useThemedImage} helper picks the variant matching the active theme
 * and falls back to the sibling slot. {@code tagline} is backend-truncated
 * (120 chars + ellipsis); the frontend renders it as-is.
 *
 * No {@code hasVerifiedSlGroup} field: the browse endpoint filters unverified
 * groups server-side so the flag would always be true on the wire.
 *
 * Inlined here (not pulled from {@code @/types/realty}) so this module can
 * land in parallel with the Task 8 type consolidation. The two shapes are
 * structurally identical, and Task 8's import sweep replaces this alias.
 */
export interface BrowseGroupCard {
  publicId: string;
  name: string;
  slug: string;
  tagline: string;
  logoLightUrl: string | null;
  logoDarkUrl: string | null;
  coverLightUrl: string | null;
  coverDarkUrl: string | null;
  foundedAt: string;
  memberCount: number;
  memberSeatLimit: number;
  activeListingsCount: number;
  completedSalesCount: number;
  rating: GroupRating;
}

/**
 * Sort direction for the primary sort key. Backend default is {@code DESC};
 * the directory's Asc/Desc toggle next to the sort dropdown flips this. The
 * tiebreaker on group name stays ASC server-side regardless of direction so
 * pagination is stable.
 */
export type SortDirection = "ASC" | "DESC";

/**
 * Query inputs for {@link getBrowseGroups}. {@code q} is optional; absent or
 * blank means "no search filter". {@code sort} defaults to {@code RATING}
 * server-side, but we always send it explicitly so the URL params and the
 * wire are 1:1.
 *
 * {@code direction}, {@code minRating}, {@code minReviews}, and
 * {@code activeOnly} back the template's left-side filter controls. Their
 * defaults ({@code DESC}, {@code 0}, {@code 0}, {@code false}) are no-ops on
 * the server; we omit them from the wire when they match the default so the
 * URL stays clean.
 */
export interface BrowseGroupsParams {
  q?: string;
  page?: number;
  size?: number;
  sort?: GroupsSortKey;
  direction?: SortDirection;
  minRating?: number;
  minReviews?: number;
  activeOnly?: boolean;
}

/**
 * Calls {@code GET /api/v1/realty-groups} — the public browse endpoint
 * (spec section 6.1, extended in the template-1:1 restoration with caller-
 * driven filter and direction params). Anonymous-accessible; the shared
 * {@code api.get} helper omits the Authorization header when no JWT is
 * present, so this works pre-login.
 */
export function getBrowseGroups(
  params: BrowseGroupsParams,
): Promise<Page<BrowseGroupCard>> {
  const search = new URLSearchParams();
  const q = params.q?.trim();
  if (q && q.length > 0) {
    search.set("q", q);
  }
  search.set("page", String(params.page ?? 0));
  search.set("size", String(params.size ?? 20));
  search.set("sort", params.sort ?? "RATING");
  const direction = params.direction ?? "DESC";
  if (direction !== "DESC") search.set("direction", direction);
  const minRating = params.minRating ?? 0;
  if (minRating > 0) search.set("minRating", String(minRating));
  const minReviews = params.minReviews ?? 0;
  if (minReviews > 0) search.set("minReviews", String(minReviews));
  if (params.activeOnly) search.set("activeOnly", "true");
  return api.get(`/api/v1/realty-groups?${search.toString()}`);
}
