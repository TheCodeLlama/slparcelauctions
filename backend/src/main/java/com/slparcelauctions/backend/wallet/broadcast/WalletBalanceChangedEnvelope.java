package com.slparcelauctions.backend.wallet.broadcast;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.user.User;

/**
 * Published to /user/queue/wallet on every wallet mutation. Frontend
 * subscriber merges into the React Query cache so balance updates appear
 * without polling.
 */
public record WalletBalanceChangedEnvelope(
        long balance,
        long reserved,
        long available,
        long penaltyOwed,
        long queuedForWithdrawal,
        String reason,
        Long ledgerEntryId,
        OffsetDateTime occurredAt
) {
    public static WalletBalanceChangedEnvelope of(User u, String reason,
            Long ledgerEntryId, long queuedForWithdrawal, OffsetDateTime occurredAt) {
        long owed = u.getPenaltyBalanceOwed() == null ? 0L : u.getPenaltyBalanceOwed();
        return new WalletBalanceChangedEnvelope(
                u.getBalanceLindens(),
                u.getReservedLindens(),
                u.availableLindens(),
                owed,
                queuedForWithdrawal,
                reason,
                ledgerEntryId,
                occurredAt);
    }
}
