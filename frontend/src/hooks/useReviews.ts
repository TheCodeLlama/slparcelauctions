"use client";

import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from "@tanstack/react-query";
import { ApiError } from "@/lib/api";
import {
  flagReview,
  getAuctionReviews,
  getPendingReviews,
  getUserReviews,
  respondToReview,
  submitReview,
} from "@/lib/api/reviews";
import { useToast } from "@/components/ui/Toast";
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
 * React Query keys for the reviews domain. Exported so tests (and future
 * WebSocket envelope handlers) can invalidate without reaching into the
 * hook internals.
 */
export const reviewsKeys = {
  /** Top-level prefix. Use for wholesale invalidation on mutations. */
  all: ["reviews"] as const,
  auction: (auctionId: number | string) =>
    ["reviews", "auction", String(auctionId)] as const,
  user: (userId: number | string, role: ReviewedRole, page: number) =>
    ["reviews", "user", String(userId), role, page] as const,
  userAll: (userId: number | string) =>
    ["reviews", "user", String(userId)] as const,
  pending: ["reviews", "pending"] as const,
};

/**
 * Read hook for the escrow-page ReviewPanel's one-shot envelope. Gated on
 * a positive numeric auctionId so the panel can render during an SSR pass
 * without triggering a stray fetch before the dynamic segment has
 * hydrated.
 */
export function useAuctionReviews(
  auctionId: number,
): UseQueryResult<AuctionReviewsResponse> {
  return useQuery<AuctionReviewsResponse>({
    queryKey: reviewsKeys.auction(auctionId),
    queryFn: () => getAuctionReviews(auctionId),
    enabled: Number.isFinite(auctionId) && auctionId > 0,
  });
}

/**
 * Paged profile-tab read — one tab per role. The pagination state lives in
 * the caller (URL-synced in Task 6) so tab switches reset the page cleanly.
 */
export function useUserReviews(
  userId: number,
  role: ReviewedRole,
  page: number,
): UseQueryResult<Page<ReviewDto>> {
  return useQuery<Page<ReviewDto>>({
    queryKey: reviewsKeys.user(userId, role, page),
    queryFn: () => getUserReviews(userId, role, { page }),
    enabled: Number.isFinite(userId) && userId > 0,
  });
}

/**
 * Dashboard "pending reviews" list. Refetches every submit so the card
 * disappears as soon as the user catches up.
 */
export function usePendingReviews(): UseQueryResult<PendingReviewDto[]> {
  return useQuery<PendingReviewDto[]>({
    queryKey: reviewsKeys.pending,
    queryFn: getPendingReviews,
  });
}

/**
 * Mutation for submitting a blind review. On success, invalidate the
 * auction envelope (so the pending-state card flips in) and the pending
 * list (so the dashboard card disappears). Toasts are fired here so
 * every callsite — ReviewPanel, future SL-deeplink — gets the same UX
 * without duplicating copy.
 */
export function useSubmitReview(
  auctionId: number,
): UseMutationResult<ReviewDto, unknown, ReviewSubmitRequest> {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation<ReviewDto, unknown, ReviewSubmitRequest>({
    mutationFn: (body) => submitReview(auctionId, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: reviewsKeys.auction(auctionId) });
      qc.invalidateQueries({ queryKey: reviewsKeys.pending });
      toast.success({
        title: "Review submitted",
        description:
          "It appears when the other party submits theirs or on day 14.",
      });
    },
    onError: (err) => {
      const already = err instanceof ApiError && err.status === 409;
      toast.error({
        title: already ? "Already reviewed" : "Could not submit review",
        description: already
          ? "You've already submitted a review for this auction."
          : "Please try again.",
      });
    },
  });
}

/**
 * Mutation for the reviewee's response. Invalidates the whole reviews
 * subtree because the responded-to review might be on the auction page,
 * the reviewee's profile, or both.
 */
export function useRespondToReview(
  reviewId: number,
): UseMutationResult<ReviewResponseDto, unknown, ReviewResponseSubmitRequest> {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation<ReviewResponseDto, unknown, ReviewResponseSubmitRequest>({
    mutationFn: (body) => respondToReview(reviewId, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: reviewsKeys.all });
      toast.success({ title: "Response posted" });
    },
    onError: (err) => {
      const already = err instanceof ApiError && err.status === 409;
      toast.error({
        title: already ? "Already responded" : "Could not post response",
        description: already
          ? "You've already responded to this review."
          : "Please try again.",
      });
    },
  });
}

/**
 * Mutation for flagging a review. No cache invalidation — flag count is
 * admin-only (§11), so from the caller's point of view the toast is the
 * whole UX. 409 → "already flagged" copy; everything else → generic
 * error.
 */
export function useFlagReview(
  reviewId: number,
): UseMutationResult<void, unknown, ReviewFlagRequest> {
  const toast = useToast();
  return useMutation<void, unknown, ReviewFlagRequest>({
    mutationFn: (body) => flagReview(reviewId, body),
    onSuccess: () =>
      toast.success({
        title: "Review flagged",
        description: "Thanks — our team will review it shortly.",
      }),
    onError: (err) => {
      const already = err instanceof ApiError && err.status === 409;
      toast.error({
        title: already ? "Already flagged" : "Could not flag review",
        description: already
          ? "You've already flagged this review."
          : "Please try again.",
      });
    },
  });
}
