package com.slparcelauctions.backend.admin.users.wallet.dto;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.wallet.UserLedgerEntryType;

public record AdminLedgerRowDto(
    Long entryId,
    UserLedgerEntryType entryType,
    long amount,
    long balanceAfter,
    long reservedAfter,
    OffsetDateTime createdAt,
    String description,
    String refType,
    Long refId,
    Long createdByAdminId
) {}
