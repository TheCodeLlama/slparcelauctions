import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApi } from "./api";
import { adminQueryKeys } from "./queryKeys";
import type {
  AdminDisputeFilters,
  AdminDisputeResolveRequest,
} from "./disputes";

export function useDisputesQueue(filters: AdminDisputeFilters) {
  return useQuery({
    queryKey: adminQueryKeys.disputesList(filters),
    queryFn: () => adminApi.disputes.list(filters),
  });
}

export function useDispute(escrowId: number) {
  return useQuery({
    queryKey: adminQueryKeys.disputeDetail(escrowId),
    queryFn: () => adminApi.disputes.detail(escrowId),
    staleTime: 30_000,
  });
}

export function useResolveDispute(escrowId: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: AdminDisputeResolveRequest) =>
      adminApi.disputes.resolve(escrowId, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminQueryKeys.disputes() });
      qc.invalidateQueries({ queryKey: adminQueryKeys.stats() });
    },
  });
}
