package com.slparcelauctions.backend.admin.users.wallet.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record AdminWalletSnapshotDto(
    UUID publicId,
    String username,
    long balanceLindens,
    long reservedLindens,
    long availableLindens,
    long penaltyBalanceOwed,
    OffsetDateTime walletFrozenAt,
    String walletFrozenReason,
    Long walletFrozenByAdminId,
    OffsetDateTime walletDormancyStartedAt,
    Integer walletDormancyPhase,
    OffsetDateTime walletTermsAcceptedAt,
    String walletTermsVersion,
    List<AdminPendingWithdrawalDto> pendingWithdrawals
) {}
