"use client";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";
import { useToast } from "@/components/ui/Toast/useToast";
import { isApiError } from "@/lib/api";
import type {
  AdminWalletAdjustRequest,
  AdminWalletForgivePenaltyRequest,
  AdminWalletNotesRequest,
} from "@/lib/admin/types";

const PAGE_SIZE = 25;

export function useAdminWallet(publicId: string) {
  return useQuery({
    queryKey: adminQueryKeys.wallet(publicId),
    queryFn: () => adminApi.users.wallet.snapshot(publicId),
    staleTime: 5_000,
  });
}

export function useAdminWalletLedger(publicId: string, page: number, size = PAGE_SIZE) {
  return useQuery({
    queryKey: adminQueryKeys.walletLedger(publicId, { page, size }),
    queryFn: () => adminApi.users.wallet.ledger(publicId, page, size),
    staleTime: 5_000,
  });
}

function makeInvalidator(qc: ReturnType<typeof useQueryClient>, publicId: string) {
  return () => {
    qc.invalidateQueries({ queryKey: adminQueryKeys.wallet(publicId) });
    qc.invalidateQueries({ queryKey: adminQueryKeys.user(publicId) });
  };
}

function adminWalletErrorMessage(err: unknown, fallback: string): string {
  if (!isApiError(err)) return fallback;
  const code = (err.problem as { code?: string }).code;
  switch (code) {
    case "AMOUNT_ZERO": return "Amount must be non-zero.";
    case "AMOUNT_EXCEEDS_OWED": return "Amount exceeds the outstanding penalty.";
    case "RESERVATION_FLOOR": return "Adjustment would push balance below the user's bid reservation. Tick the override box if you intend this.";
    case "ALREADY_FROZEN": return "Wallet is already frozen.";
    case "NOT_FROZEN": return "Wallet is not frozen.";
    case "NOT_IN_DORMANCY": return "Wallet is not in dormancy state.";
    case "BOT_PROCESSING": return "Bot is mid-payout — wait for the callback to finish or for the lease to expire.";
    case "WITHDRAWAL_NOT_PENDING": return "Withdrawal already finalized.";
    case "COMMAND_NOT_FOUND": return "Withdrawal not found.";
    case "COMMAND_USER_MISMATCH": return "Withdrawal does not belong to this user.";
    default: return fallback;
  }
}

export function useAdjustBalance(publicId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const invalidate = makeInvalidator(qc, publicId);
  return useMutation({
    mutationFn: (body: AdminWalletAdjustRequest) =>
      adminApi.users.wallet.adjust(publicId, body),
    onSuccess: () => { invalidate(); toast.success("Balance adjusted."); },
    onError: (e) => toast.error(adminWalletErrorMessage(e, "Couldn't adjust balance.")),
  });
}

export function useFreezeWallet(publicId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const invalidate = makeInvalidator(qc, publicId);
  return useMutation({
    mutationFn: (body: AdminWalletNotesRequest) =>
      adminApi.users.wallet.freeze(publicId, body),
    onSuccess: () => { invalidate(); toast.success("Wallet frozen."); },
    onError: (e) => toast.error(adminWalletErrorMessage(e, "Couldn't freeze wallet.")),
  });
}

export function useUnfreezeWallet(publicId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const invalidate = makeInvalidator(qc, publicId);
  return useMutation({
    mutationFn: (body: AdminWalletNotesRequest) =>
      adminApi.users.wallet.unfreeze(publicId, body),
    onSuccess: () => { invalidate(); toast.success("Wallet unfrozen."); },
    onError: (e) => toast.error(adminWalletErrorMessage(e, "Couldn't unfreeze wallet.")),
  });
}

export function useForgivePenalty(publicId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const invalidate = makeInvalidator(qc, publicId);
  return useMutation({
    mutationFn: (body: AdminWalletForgivePenaltyRequest) =>
      adminApi.users.wallet.forgivePenalty(publicId, body),
    onSuccess: () => { invalidate(); toast.success("Penalty forgiven."); },
    onError: (e) => toast.error(adminWalletErrorMessage(e, "Couldn't forgive penalty.")),
  });
}

export function useResetDormancy(publicId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const invalidate = makeInvalidator(qc, publicId);
  return useMutation({
    mutationFn: (body: AdminWalletNotesRequest) =>
      adminApi.users.wallet.resetDormancy(publicId, body),
    onSuccess: () => { invalidate(); toast.success("Dormancy reset."); },
    onError: (e) => toast.error(adminWalletErrorMessage(e, "Couldn't reset dormancy.")),
  });
}

export function useClearTerms(publicId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const invalidate = makeInvalidator(qc, publicId);
  return useMutation({
    mutationFn: (body: AdminWalletNotesRequest) =>
      adminApi.users.wallet.clearTerms(publicId, body),
    onSuccess: () => { invalidate(); toast.success("Terms acceptance cleared."); },
    onError: (e) => toast.error(adminWalletErrorMessage(e, "Couldn't clear terms.")),
  });
}

export function useForceCompleteWithdrawal(publicId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const invalidate = makeInvalidator(qc, publicId);
  return useMutation({
    mutationFn: ({ terminalCommandId, body }: { terminalCommandId: number; body: AdminWalletNotesRequest }) =>
      adminApi.users.wallet.forceCompleteWithdrawal(publicId, terminalCommandId, body),
    onSuccess: () => { invalidate(); toast.success("Withdrawal force-completed."); },
    onError: (e) => toast.error(adminWalletErrorMessage(e, "Couldn't force-complete withdrawal.")),
  });
}

export function useForceFailWithdrawal(publicId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const invalidate = makeInvalidator(qc, publicId);
  return useMutation({
    mutationFn: ({ terminalCommandId, body }: { terminalCommandId: number; body: AdminWalletNotesRequest }) =>
      adminApi.users.wallet.forceFailWithdrawal(publicId, terminalCommandId, body),
    onSuccess: () => { invalidate(); toast.success("Withdrawal failed and refunded."); },
    onError: (e) => toast.error(adminWalletErrorMessage(e, "Couldn't fail withdrawal.")),
  });
}
