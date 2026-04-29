"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";
import { useToast } from "@/components/ui/Toast/useToast";
import { isApiError } from "@/lib/api";

export function useReinstateAuction(userId: number) {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: ({ auctionId, notes }: { auctionId: number; notes: string }) =>
      adminApi.auctions.reinstate(auctionId, notes),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminQueryKeys.userTab(userId, "listings", { page: 0, size: 25 }) });
      qc.invalidateQueries({ queryKey: adminQueryKeys.stats() });
      toast.success("Auction reinstated.");
    },
    onError: (err) => {
      const code = isApiError(err) ? (err.problem as { code?: string }).code : null;
      if (code === "AUCTION_NOT_SUSPENDED") {
        toast.error("Auction is no longer SUSPENDED. Refreshing.");
        qc.invalidateQueries({ queryKey: adminQueryKeys.userTab(userId, "listings", { page: 0, size: 25 }) });
        return;
      }
      toast.error("Couldn't reinstate auction.");
    },
  });
}
