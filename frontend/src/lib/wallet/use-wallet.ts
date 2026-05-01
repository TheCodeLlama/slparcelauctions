"use client";

import { useQuery } from "@tanstack/react-query";
import { getWallet } from "@/lib/api/wallet";
import type { WalletView } from "@/types/wallet";

/**
 * Shared React Query key for the authenticated user's wallet view. Exposed so
 * mutations (withdraw, pay-penalty, accept-terms, listing-fee, etc.) and the
 * Phase 8 WebSocket consumer can invalidate the same cache entry that
 * {@link useWallet} hydrates. Treat the tuple as the canonical key — don't
 * inline `["me", "wallet"]` at call sites.
 */
export const walletQueryKey = ["me", "wallet"] as const;

/**
 * Subscribe to the authenticated user's wallet view.
 *
 * Used by:
 * - {@link HeaderWalletIndicator} (verified-only desktop pill + popover)
 * - {@link MobileMenu} (verified-only Wallet row with inline available)
 * - {@link WalletPanel} (the /wallet page surface)
 *
 * Polling: 30s `refetchInterval` + `refetchOnWindowFocus` keep the indicator
 * fresh until Phase 8 wires the wallet WS topic. The 10s `staleTime` lets
 * multiple consumers mounted on the same page share one fetch without
 * thundering the backend.
 *
 * @param enabled gate the query off until the caller knows the user is
 * verified — unverified users have no wallet and the request would 4xx.
 */
export function useWallet(enabled: boolean = true) {
  return useQuery<WalletView>({
    queryKey: walletQueryKey,
    queryFn: getWallet,
    enabled,
    refetchInterval: 30_000,
    refetchOnWindowFocus: true,
    staleTime: 10_000,
  });
}
