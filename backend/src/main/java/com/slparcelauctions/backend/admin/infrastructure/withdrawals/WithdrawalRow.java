package com.slparcelauctions.backend.admin.infrastructure.withdrawals;

import java.time.OffsetDateTime;

public record WithdrawalRow(
        Long id,
        Long amount,
        String recipientUuid,
        Long adminUserId,
        String notes,
        WithdrawalStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt,
        String failureReason) {
}
