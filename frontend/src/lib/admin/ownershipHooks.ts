"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApi } from "./api";
import { adminQueryKeys } from "./queryKeys";
import type { AdminOwnershipRecheckResponse } from "./infrastructure";

export function useOwnershipRecheck() {
  const qc = useQueryClient();
  return useMutation<AdminOwnershipRecheckResponse, Error, number>({
    mutationFn: (auctionId: number) => adminApi.ownershipRecheck.recheck(auctionId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminQueryKeys.fraudFlags() });
    },
  });
}
