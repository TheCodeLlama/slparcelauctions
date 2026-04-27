"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";
import { useToast } from "@/components/ui/Toast/useToast";
import { isApiError } from "@/lib/api";

export function useDismissFraudFlag() {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: ({ flagId, adminNotes }: { flagId: number; adminNotes: string }) =>
      adminApi.dismissFraudFlag(flagId, adminNotes),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminQueryKeys.fraudFlags() });
      qc.invalidateQueries({ queryKey: adminQueryKeys.stats() });
      toast.success("Flag dismissed.");
    },
    onError: (err) => {
      if (isApiError(err) && err.problem.code === "ALREADY_RESOLVED") {
        toast.error("Flag was already resolved. Refreshing.");
        qc.invalidateQueries({ queryKey: adminQueryKeys.fraudFlags() });
        return;
      }
      toast.error("Couldn't dismiss flag.");
    },
  });
}
