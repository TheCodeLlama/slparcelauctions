package com.slparcelauctions.backend.admin.users.wallet.dto;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.escrow.command.TerminalCommandStatus;

/**
 * One pending wallet-withdrawal {@code TerminalCommand} as the admin sees it.
 * {@code canForceFinalize} is {@code true} only when {@code status=QUEUED}
 * (the bot has not yet attempted dispatch); when {@code IN_FLIGHT} the admin
 * must wait for the bot's callback or for the staleness sweep to requeue.
 */
public record AdminPendingWithdrawalDto(
    Long terminalCommandId,
    long amount,
    String recipientUuid,
    OffsetDateTime queuedAt,
    OffsetDateTime dispatchedAt,
    int attemptCount,
    TerminalCommandStatus status,
    boolean canForceFinalize
) {}
