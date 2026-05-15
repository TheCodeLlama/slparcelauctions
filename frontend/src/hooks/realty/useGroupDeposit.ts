"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { depositToGroupWallet } from "@/lib/api/realtyGroupWallet";
import { walletQueryKey } from "@/lib/wallet/use-wallet";
import { realtyQueryKeys } from "./useRealtyGroups";
import type { GroupDepositRequest, GroupDepositResponse } from "@/types/realty";

/**
 * Deposit L$ from the caller's personal SLParcels wallet into a realty
 * group's wallet. Atomic personal-wallet -> group-wallet transfer on the
 * server side; idempotent on {@code idempotencyKey}.
 *
 * <p>Invalidates the whole realty prefix on success (so every cached slice
 * of the group: wallet card, ledger table, manage page header,
 * by-slug view, refetches) and the personal wallet ("me") key (so the
 * depositor's own /wallet view reflects the debit). Mirrors the
 * {@code invalidateAll} pattern used by every other realty mutation.
 */
export function useGroupDeposit(groupPublicId: string) {
  const qc = useQueryClient();
  return useMutation<GroupDepositResponse, Error, GroupDepositRequest>({
    mutationFn: (body) => depositToGroupWallet(groupPublicId, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: realtyQueryKeys.all });
      qc.invalidateQueries({ queryKey: walletQueryKey });
    },
  });
}
