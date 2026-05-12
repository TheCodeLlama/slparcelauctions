import { api } from "@/lib/api";
import type { Page } from "@/types/page";

/**
 * Single row of the dedicated group-reviews page (sub-project G §13).
 *
 * <p>Mirror of the backend {@code GroupReviewRowDto}. {@code comment} is
 * nullable because the backend allows rating-only reviews. {@code createdAt}
 * is the wire-format ISO-8601 string emitted by Jackson for {@code Instant};
 * callers format it for display.
 */
export interface GroupReviewRow {
  reviewerPublicId: string;
  reviewerDisplayName: string;
  rating: number;
  comment: string | null;
  auctionPublicId: string;
  auctionTitle: string;
  createdAt: string;
}

/**
 * Realty Groups: G — paginated public reviews list for a realty group.
 *
 * <p>Backend: {@code GET /api/v1/realty/groups/{publicId}/reviews?page=N&size=20}.
 * Anonymous-accessible — mirrors the user-side public reviews endpoint.
 *
 * <p>The default page size matches the backend default; smaller sizes are
 * honoured server-side, larger sizes are clamped at 50.
 */
export function fetchGroupReviews(
  groupPublicId: string,
  page = 0,
  size = 20,
): Promise<Page<GroupReviewRow>> {
  return api.get<Page<GroupReviewRow>>(
    `/api/v1/realty/groups/${groupPublicId}/reviews`,
    { params: { page, size } },
  );
}
