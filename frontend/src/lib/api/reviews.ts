import { api } from "@/lib/api";
import type { Page } from "@/types/page";
import type {
  AuctionReviewsResponse,
  PendingReviewDto,
  ReviewDto,
  ReviewFlagRequest,
  ReviewResponseDto,
  ReviewResponseSubmitRequest,
  ReviewSubmitRequest,
  ReviewedRole,
} from "@/types/review";

/**
 * Review read/write client. Mirrors {@code ReviewController} and
 * {@code UserReviewsController} endpoints. Reads are public (anonymous
 * callers get the pre-reveal shape); writes bubble the standard
 * {@code ApiError} surface — 401/403/409/422 — on the documented codes.
 */

/**
 * GET /api/v1/auctions/{auctionId}/reviews — returns the envelope the
 * escrow-page {@code ReviewPanel} needs to pick one of its five states.
 * Public endpoint; anonymous callers see revealed reviews only.
 */
export function getAuctionReviews(
  auctionId: number | string,
): Promise<AuctionReviewsResponse> {
  return api.get<AuctionReviewsResponse>(
    `/api/v1/auctions/${auctionId}/reviews`,
  );
}

/**
 * POST /api/v1/auctions/{auctionId}/reviews — submit the caller's blind
 * review. 422 {@code ReviewIneligibleException} if escrow is not
 * COMPLETED or the window has closed; 409 if the caller already submitted.
 */
export function submitReview(
  auctionId: number | string,
  body: ReviewSubmitRequest,
): Promise<ReviewDto> {
  return api.post<ReviewDto>(`/api/v1/auctions/${auctionId}/reviews`, body);
}

export type GetUserReviewsParams = {
  page?: number;
  size?: number;
};

const DEFAULT_USER_REVIEWS_SIZE = 10;

/**
 * GET /api/v1/users/{userId}/reviews — public, paginated, newest-first.
 * The {@code role} query param is required by the backend — callers must
 * pick SELLER or BUYER; there is no combined "all" endpoint for sub-spec 1.
 */
export function getUserReviews(
  userId: number | string,
  role: ReviewedRole,
  params: GetUserReviewsParams = {},
): Promise<Page<ReviewDto>> {
  const page = params.page ?? 0;
  const size = params.size ?? DEFAULT_USER_REVIEWS_SIZE;
  return api.get<Page<ReviewDto>>(`/api/v1/users/${userId}/reviews`, {
    params: { role, page, size },
  });
}

/**
 * GET /api/v1/users/me/pending-reviews — requires JWT. The backend sorts
 * most-urgent-first; the UI preserves that order.
 */
export function getPendingReviews(): Promise<PendingReviewDto[]> {
  return api.get<PendingReviewDto[]>("/api/v1/users/me/pending-reviews");
}

/**
 * POST /api/v1/reviews/{reviewId}/respond — reviewee-only. 409 if a
 * response already exists.
 */
export function respondToReview(
  reviewId: number | string,
  body: ReviewResponseSubmitRequest,
): Promise<ReviewResponseDto> {
  return api.post<ReviewResponseDto>(
    `/api/v1/reviews/${reviewId}/respond`,
    body,
  );
}

/**
 * POST /api/v1/reviews/{reviewId}/flag — any authenticated non-author. 409
 * on duplicate flag; 204 on success (returned as {@code void}).
 */
export async function flagReview(
  reviewId: number | string,
  body: ReviewFlagRequest,
): Promise<void> {
  await api.post<void>(`/api/v1/reviews/${reviewId}/flag`, body);
}
