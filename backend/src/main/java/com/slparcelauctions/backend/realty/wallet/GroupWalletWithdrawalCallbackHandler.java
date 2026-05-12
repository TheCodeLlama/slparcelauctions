package com.slparcelauctions.backend.realty.wallet;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.escrow.command.TerminalCommand;
import com.slparcelauctions.backend.notification.NotificationPublisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Handles terminal-callback results for GROUP_WALLET_WITHDRAWAL commands.
 * Parses the "GWAL-{ledgerId}" idempotency key, delegates to
 * {@link RealtyGroupWalletService} for ledger appends, and fires stub
 * notifications. Spec §5.3 / §11.2.
 *
 * <p>Both methods run with {@link Propagation#MANDATORY} — the caller
 * ({@link com.slparcelauctions.backend.escrow.command.TerminalCommandService})
 * already holds a transaction, and the ledger appends must be atomic with
 * the command-status update.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GroupWalletWithdrawalCallbackHandler {

    private static final int GWAL_PREFIX_LENGTH = 5; // "GWAL-"

    private final RealtyGroupWalletService walletService;
    private final NotificationPublisher notificationPublisher;

    /**
     * Called when the terminal confirms the L$ transfer. Records a
     * WITHDRAW_COMPLETED ledger row and notifies the group.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onSuccess(TerminalCommand cmd, String slTransactionKey) {
        Long ledgerId = parseLedgerId(cmd);
        if (ledgerId == null) {
            return;
        }
        walletService.recordWithdrawalSuccess(ledgerId, slTransactionKey);
        Long groupId = walletService.findGroupIdForLedgerEntry(ledgerId);
        if (groupId != null) {
            notificationPublisher.groupWalletWithdrawalCompleted(
                groupId, cmd.getAmount(), ledgerId);
        }
        log.info("group withdrawal callback success: commandId={}, ledgerId={}, slTxn={}",
            cmd.getId(), ledgerId, slTransactionKey);
    }

    /**
     * Called when the terminal fails after retry exhaustion. Credits the L$
     * back to the group balance (WITHDRAW_REVERSED) and notifies the group.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onStall(TerminalCommand cmd, String reason) {
        Long ledgerId = parseLedgerId(cmd);
        if (ledgerId == null) {
            return;
        }
        walletService.recordWithdrawalReversal(ledgerId, reason);
        Long groupId = walletService.findGroupIdForLedgerEntry(ledgerId);
        if (groupId != null) {
            notificationPublisher.groupWalletWithdrawalReversed(
                groupId, cmd.getAmount(), ledgerId,
                reason == null ? "transport failure" : reason);
        }
        log.warn("group withdrawal callback stall: commandId={}, ledgerId={}, reason={}",
            cmd.getId(), ledgerId, reason);
    }

    /**
     * Extracts the ledger entry id from the "GWAL-{n}" idempotency key.
     * Returns {@code null} and logs an error on any parse failure so the
     * caller can skip processing without throwing.
     */
    Long parseLedgerId(TerminalCommand cmd) {
        String key = cmd.getIdempotencyKey();
        if (key == null || !key.startsWith("GWAL-")) {
            log.error("group withdrawal command {} has unexpected idempotencyKey: {}",
                cmd.getId(), key);
            return null;
        }
        try {
            return Long.parseLong(key.substring(GWAL_PREFIX_LENGTH));
        } catch (NumberFormatException e) {
            log.error("group withdrawal command {} idempotencyKey not parseable: {}",
                cmd.getId(), key);
            return null;
        }
    }
}
