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
 * {@code logoUrl} / {@code coverUrl} are relative paths — callers render via
 * {@code apiUrl(...)}. {@code tagline} is backend-truncated (120 chars +
 * ellipsis); the frontend renders it as-is.
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
  logoUrl: string | null;
  coverUrl: string | null;
  foundedAt: string;
  memberCount: number;
  memberSeatLimit: number;
  activeListingsCount: number;
  completedSalesCount: number;
  rating: GroupRating;
}

/**
 * Query inputs for {@link getBrowseGroups}. {@code q} is optional; absent or
 * blank means "no search filter". {@code sort} defaults to {@code RATING}
 * server-side, but we always send it explicitly so the URL params and the
 * wire are 1:1.
 */
export interface BrowseGroupsParams {
  q?: string;
  page?: number;
  size?: number;
  sort?: GroupsSortKey;
}

/**
 * Calls {@code GET /api/v1/realty-groups} — the public browse endpoint
 * (spec section 6.1). Anonymous-accessible; the shared {@code api.get} helper
 * omits the Authorization header when no JWT is present, so this works
 * pre-login.
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
  return api.get(`/api/v1/realty-groups?${search.toString()}`);
}
