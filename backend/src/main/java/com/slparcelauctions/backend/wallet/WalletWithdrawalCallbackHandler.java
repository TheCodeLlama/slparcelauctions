package com.slparcelauctions.backend.wallet;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.escrow.command.TerminalCommand;
import com.slparcelauctions.backend.escrow.command.TerminalCommandPurpose;
import com.slparcelauctions.backend.notification.NotificationPublisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Handles {@link TerminalCommandPurpose#WALLET_WITHDRAWAL} success / stall
 * callbacks from {@code TerminalCommandService}. Bridges the dispatcher's
 * generic terminal-command lifecycle into the wallet ledger:
 *
 * <ul>
 *   <li>On success — append a {@code WITHDRAW_COMPLETED} ledger row keyed
 *       to the original {@code WITHDRAW_QUEUED} row, and dispatch a
 *       {@code WALLET_WITHDRAWAL_COMPLETED} notification (which fans out to
 *       an SL IM).</li>
 *   <li>On stall (retry exhausted) — append a {@code WITHDRAW_REVERSED} row,
 *       credit the user's balance back, and dispatch a
 *       {@code WALLET_WITHDRAWAL_REVERSED} notification.</li>
 * </ul>
 *
 * <p>The terminal command's {@code idempotencyKey} encodes the originating
 * {@code user_ledger.id} as {@code "WAL-{id}"} (see
 * {@link WalletService#withdrawCommon}); we parse it back out here.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WalletWithdrawalCallbackHandler {

    private final WalletService walletService;
    private final NotificationPublisher notificationPublisher;

    @Transactional(propagation = Propagation.MANDATORY)
    public void onSuccess(TerminalCommand cmd, String slTransactionKey) {
        Long ledgerId = parseLedgerId(cmd);
        if (ledgerId == null) return;
        walletService.recordWithdrawalSuccess(ledgerId, slTransactionKey);
        Long userId = walletService.findUserIdForLedgerEntry(ledgerId);
        if (userId != null) {
            notificationPublisher.walletWithdrawalCompleted(
                    userId, cmd.getAmount(), ledgerId);
        }
        log.info("wallet withdrawal callback success: commandId={}, ledgerId={}, slTxn={}",
                cmd.getId(), ledgerId, slTransactionKey);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void onStall(TerminalCommand cmd, String reason) {
        Long ledgerId = parseLedgerId(cmd);
        if (ledgerId == null) return;
        walletService.recordWithdrawalReversal(ledgerId, reason);
        Long userId = walletService.findUserIdForLedgerEntry(ledgerId);
        if (userId != null) {
            notificationPublisher.walletWithdrawalReversed(
                    userId, cmd.getAmount(), ledgerId,
                    reason == null ? "transport failure" : reason);
        }
        log.warn("wallet withdrawal callback stall: commandId={}, ledgerId={}, reason={}",
                cmd.getId(), ledgerId, reason);
    }

    private Long parseLedgerId(TerminalCommand cmd) {
        String key = cmd.getIdempotencyKey();
        if (key == null || !key.startsWith("WAL-")) {
            log.error("wallet withdrawal command {} has unexpected idempotencyKey: {}",
                    cmd.getId(), key);
            return null;
        }
        try {
            return Long.parseLong(key.substring(4));
        } catch (NumberFormatException e) {
            log.error("wallet withdrawal command {} idempotencyKey not parseable: {}",
                    cmd.getId(), key);
            return null;
        }
    }
}
