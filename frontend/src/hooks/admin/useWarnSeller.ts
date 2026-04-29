"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";
import { useToast } from "@/components/ui/Toast/useToast";

export function useWarnSeller() {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: ({ auctionId, notes }: { auctionId: number; notes: string }) =>
      adminApi.reports.warnSeller(auctionId, notes),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminQueryKeys.reports() });
      qc.invalidateQueries({ queryKey: adminQueryKeys.stats() });
      toast.success("Warning sent to seller.");
    },
    onError: () => {
      toast.error("Couldn't send warning.");
    },
  });
}
