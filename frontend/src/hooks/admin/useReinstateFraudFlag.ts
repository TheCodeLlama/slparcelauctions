"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";
import { useToast } from "@/components/ui/Toast/useToast";
import { isApiError } from "@/lib/api";

export function useReinstateFraudFlag() {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: ({ flagId, adminNotes }: { flagId: number; adminNotes: string }) =>
      adminApi.reinstateFraudFlag(flagId, adminNotes),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminQueryKeys.fraudFlags() });
      qc.invalidateQueries({ queryKey: adminQueryKeys.stats() });
      toast.success("Auction reinstated.");
    },
    onError: (err) => {
      if (isApiError(err)) {
        if (err.problem.code === "ALREADY_RESOLVED") {
          toast.error("Flag was already resolved. Refreshing.");
          qc.invalidateQueries({ queryKey: adminQueryKeys.fraudFlags() });
          return;
        }
        if (err.problem.code === "AUCTION_NOT_SUSPENDED") {
          toast.error("Auction state changed. Refreshing.");
          qc.invalidateQueries({ queryKey: adminQueryKeys.fraudFlags() });
          return;
        }
      }
      toast.error("Couldn't reinstate auction.");
    },
  });
}
