import { api } from "@/lib/api";
import type { Page } from "@/types/page";
import type { MyBidSummary } from "@/types/auction";

/**
 * Server-side filter alias for the My Bids dashboard. Values are the four
 * exposed by the spec §12 filter tabs (All / Active / Won / Lost). The value
 * is passed verbatim as {@code ?status=<value>}; the backend parses the
 * string inside {@code MyBidsService} and maps it to a set of
 * {@link MyBidStatus}es before filtering.
 *
 * {@code "all"} is modelled explicitly (rather than {@code undefined}) so the
 * URL-bound {@code ?status=} round-trip is self-describing. The helper sends
 * no {@code status} query parameter when callers pass {@code undefined} —
 * matching the "omit for all" pattern the server already tolerates — so
 * {@code useMyBids()} with no state still gets the full unfiltered list.
 */
export type MyBidsFilter = "active" | "won" | "lost" | "all";

/**
 * Default page size for the My Bids dashboard. Matches spec §12's 20/page
 * budget and the backend's {@code @PageableDefault(size = 20)} on
 * {@link com.slparcelauctions.backend.auction.mybids.MyBidsController}.
 */
const DEFAULT_MY_BIDS_SIZE = 20;

export type GetMyBidsParams = {
  status?: MyBidsFilter;
  page?: number;
  size?: number;
};

/**
 * GET /api/v1/users/me/bids — the bidder dashboard's sole backend surface.
 * Returns a paginated list of {@link MyBidSummary} rows, one per auction the
 * caller has bid on, with a derived {@link MyBidStatus} per spec §10.
 *
 * <p>Authentication is required and handled by the {@code /api/v1/**}
 * catch-all in SecurityConfig — an unauthenticated request surfaces as a
 * 401 and bubbles through the shared api 401 interceptor.
 */
export function getMyBids(
  params: GetMyBidsParams = {},
): Promise<Page<MyBidSummary>> {
  const page = params.page ?? 0;
  const size = params.size ?? DEFAULT_MY_BIDS_SIZE;
  return api.get<Page<MyBidSummary>>("/api/v1/users/me/bids", {
    params: {
      status: params.status,
      page,
      size,
    },
  });
}
