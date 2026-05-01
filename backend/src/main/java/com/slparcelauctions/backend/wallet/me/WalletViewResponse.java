package com.slparcelauctions.backend.wallet.me;

import java.time.OffsetDateTime;
import java.util.List;

import com.slparcelauctions.backend.wallet.WithdrawalStatus;

/**
 * GET /api/v1/me/wallet response. Surfaces the user's wallet state +
 * recent ledger activity for the dashboard wallet panel.
 */
public record WalletViewResponse(
        long balance,
        long reserved,
        long available,
        long penaltyOwed,
        long queuedForWithdrawal,
        boolean termsAccepted,
        String termsVersion,
        OffsetDateTime termsAcceptedAt,
        List<LedgerEntryDto> recentLedger
) {

    public record LedgerEntryDto(
            Long id,
            String entryType,
            long amount,
            long balanceAfter,
            long reservedAfter,
            String refType,
            Long refId,
            String description,
            OffsetDateTime createdAt,
            WithdrawalStatus withdrawalStatus
    ) { }
}
