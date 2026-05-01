"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useStompSubscription } from "@/lib/ws/hooks";
import type { WalletView } from "@/types/wallet";
import { walletQueryKey } from "./use-wallet";

/**
 * Mirrors the backend `WalletBalanceChangedEnvelope` published by
 * `WalletEventPublisher.publishBalanceChanged(...)` via Spring's
 * `convertAndSendToUser(userId, "/queue/wallet", envelope)`.
 *
 * `reason` is the `UserLedgerEntryType` string of the mutation that triggered
 * the publish (e.g. `DEPOSIT`, `WITHDRAW_QUEUED`, `BID_RESERVED`). It is
 * informational only — frontend does not branch on it today; the cache patch
 * is a uniform overwrite of the four numeric fields.
 *
 * `ledgerEntryId` is the id of the ledger row appended in the same
 * transaction. May be null for envelopes published from non-ledger paths
 * (none today, but the field is nullable for future-proofing).
 */
export interface WalletBalanceChangedEnvelope {
  balance: number;
  reserved: number;
  available: number;
  penaltyOwed: number;
  queuedForWithdrawal: number;
  reason: string;
  ledgerEntryId: number | null;
  occurredAt: string;
}

/**
 * Subscribe the authenticated, verified user to the per-user wallet topic
 * `/user/queue/wallet`. On each envelope:
 *
 * 1. Patch the four balance fields in the {@link walletQueryKey} cache —
 *    structural merge so the unrelated keys (`termsAccepted`, `recentLedger`,
 *    etc.) are preserved. Fall through unchanged when the cache hasn't been
 *    hydrated yet (no `prev`); the next {@link useWallet} fetch will pick up
 *    the new state authoritatively.
 *
 * 2. Invalidate every `["me", "wallet", "ledger", ...]` query so the activity
 *    table on the wallet page picks up the new ledger row. We don't try to
 *    splice the row in optimistically because the active filter / page might
 *    exclude it — let React Query refetch the visible page.
 *
 * Idempotent across multiple mounted subscribers — both
 * {@link HeaderWalletIndicator} and {@link WalletPanel} can call this hook
 * simultaneously and the cache merge stays correct (each gets the same
 * envelope, both write the same value).
 *
 * @param enabled gate the subscription off until the user is verified.
 * Passing `false` short-circuits {@link useStompSubscription} via the empty
 * destination convention so unverified guests don't hold a STOMP frame open.
 */
export function useWalletWsSubscription(enabled: boolean): void {
  const qc = useQueryClient();

  useStompSubscription<WalletBalanceChangedEnvelope>(
    enabled ? "/user/queue/wallet" : "",
    (env) => {
      qc.setQueryData<WalletView>(walletQueryKey, (prev) => {
        if (!prev) return prev;
        return {
          ...prev,
          balance: env.balance,
          reserved: env.reserved,
          available: env.available,
          penaltyOwed: env.penaltyOwed,
          queuedForWithdrawal: env.queuedForWithdrawal,
        };
      });
      qc.invalidateQueries({ queryKey: ["me", "wallet", "ledger"] });
    },
  );
}
