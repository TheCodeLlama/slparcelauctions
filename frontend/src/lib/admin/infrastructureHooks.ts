import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApi } from "./api";
import { adminQueryKeys } from "./queryKeys";
import type { WithdrawalRequest } from "./infrastructure";

export function useBotPoolHealth() {
  return useQuery({
    queryKey: adminQueryKeys.botPool(),
    queryFn: () => adminApi.botPool.health(),
    refetchInterval: 30_000,
  });
}

export function useTerminalsAdmin() {
  return useQuery({
    queryKey: adminQueryKeys.terminals(),
    queryFn: () => adminApi.terminals.list(),
  });
}

export function useRotateSecret() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => adminApi.terminals.rotateSecret(),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminQueryKeys.terminals() });
    },
  });
}

export function useReconciliationRuns(days: number = 7) {
  return useQuery({
    queryKey: adminQueryKeys.reconciliationRuns(days),
    queryFn: () => adminApi.reconciliation.runs(days),
  });
}

export function useAvailableToWithdraw() {
  return useQuery({
    queryKey: adminQueryKeys.withdrawalsAvailable(),
    queryFn: () => adminApi.withdrawals.available(),
    refetchInterval: 60_000,
  });
}

export function useWithdrawals(page: number = 0, size: number = 20) {
  return useQuery({
    queryKey: adminQueryKeys.withdrawalsList(page, size),
    queryFn: () => adminApi.withdrawals.list(page, size),
  });
}

export function useRequestWithdrawal() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: WithdrawalRequest) => adminApi.withdrawals.create(body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminQueryKeys.withdrawals() });
      qc.invalidateQueries({ queryKey: adminQueryKeys.withdrawalsAvailable() });
    },
  });
}
