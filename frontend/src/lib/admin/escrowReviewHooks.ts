import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApi } from "./api";
import { adminQueryKeys } from "./queryKeys";
import type {
  AdminEscrowReviewFilters,
  AdminEscrowReviewResolveRequest,
} from "./escrowReviews";

export function useEscrowReviewsQueue(filters: AdminEscrowReviewFilters) {
  return useQuery({
    queryKey: adminQueryKeys.escrowReviewsList(filters),
    queryFn: () => adminApi.escrowReviews.list(filters),
  });
}

export function useEscrowReview(reviewPublicId: string) {
  return useQuery({
    queryKey: adminQueryKeys.escrowReviewDetail(reviewPublicId),
    queryFn: () => adminApi.escrowReviews.detail(reviewPublicId),
    staleTime: 30_000,
  });
}

export function useResolveEscrowReview(reviewPublicId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: AdminEscrowReviewResolveRequest) =>
      adminApi.escrowReviews.resolve(reviewPublicId, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminQueryKeys.escrowReviews() });
      qc.invalidateQueries({ queryKey: adminQueryKeys.stats() });
    },
  });
}
