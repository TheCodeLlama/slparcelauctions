"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";
import { useToast } from "@/components/ui/Toast/useToast";

export function useSuspendListingFromReport() {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: ({ auctionId, notes }: { auctionId: number; notes: string }) =>
      adminApi.reports.suspendListing(auctionId, notes),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminQueryKeys.reports() });
      qc.invalidateQueries({ queryKey: adminQueryKeys.stats() });
      toast.success("Listing suspended.");
    },
    onError: () => {
      toast.error("Couldn't suspend listing.");
    },
  });
}
