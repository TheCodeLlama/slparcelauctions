"use client";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { getGroupWallet } from "@/lib/api/realtyGroupWallet";

export function useGroupWallet(publicId: string) {
  return useQuery({
    queryKey: ["realty", "group", publicId, "wallet"],
    queryFn: () => getGroupWallet(publicId),
    staleTime: 30_000,
    refetchOnWindowFocus: true,
  });
}

/**
 * Returns a stable callback that invalidates both the wallet summary and
 * ledger queries for a group. Use after a successful withdraw or any
 * action that mutates the group balance (e.g. on a WebSocket
 * {@code GROUP_WALLET_BALANCE_CHANGED} event).
 */
export function useInvalidateGroupWallet(publicId: string) {
  const qc = useQueryClient();
  return () => {
    qc.invalidateQueries({ queryKey: ["realty", "group", publicId, "wallet"] });
    qc.invalidateQueries({ queryKey: ["realty", "group", publicId, "ledger"] });
  };
}
