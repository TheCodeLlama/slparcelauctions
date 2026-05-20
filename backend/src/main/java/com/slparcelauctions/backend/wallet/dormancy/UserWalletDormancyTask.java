package com.slparcelauctions.backend.wallet.dormancy;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.escrow.command.TerminalCommand;
import com.slparcelauctions.backend.escrow.command.TerminalCommandAction;
import com.slparcelauctions.backend.escrow.command.TerminalCommandPurpose;
import com.slparcelauctions.backend.escrow.command.TerminalCommandRepository;
import com.slparcelauctions.backend.escrow.command.TerminalCommandStatus;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.wallet.UserLedgerEntry;
import com.slparcelauctions.backend.wallet.UserLedgerEntryType;
import com.slparcelauctions.backend.wallet.UserLedgerRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-user operations for the user-wallet dormancy state machine.
 *
 * <p>Mirrors {@link com.slparcelauctions.backend.realty.wallet.dormancy.GroupWalletDormancyTask}
 * on the user side. Each method is {@code @Transactional} so a single failure
 * in the weekly sweep doesn't block the rest of the eligible users.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-19-user-wallet-dormancy-design.md
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserWalletDormancyTask {

    private final UserRepository userRepository;
    private final UserLedgerRepository ledgerRepository;
    private final TerminalCommandRepository terminalCommandRepository;
    private final NotificationPublisher notificationPublisher;

    // -------------------------------------------------------------------------
    // Phase 1: flag a newly-dormant user
    // -------------------------------------------------------------------------

    /**
     * Stamps {@code walletDormancyStartedAt=now, walletDormancyPhase=1} on a user
     * that has just crossed the inactivity window. Logs the first escalation
     * (SL IM body deferred to the Epic 09 dispatcher refactor; the stub matches
     * the group-side behaviour).
     */
    @Transactional
    public void flag(User user, OffsetDateTime now) {
        user.setWalletDormancyStartedAt(now);
        user.setWalletDormancyPhase(1);
        userRepository.save(user);

        long balance = user.getBalanceLindens();
        log.info("user wallet dormancy flagged: userId={}, balance={}, phase=1",
            user.getId(), balance);
        notificationPublisher.userWalletDormancyFlagged(user.getId(), 1, balance);
    }

    // -------------------------------------------------------------------------
    // Phases 2-4 and COMPLETED (99): escalate or auto-return
    // -------------------------------------------------------------------------

    /**
     * Advances a dormant user to its next phase.
     *
     * <ul>
     *   <li>Phases 1-3: increment the phase, log the next escalation.</li>
     *   <li>Phase 4: queue a {@code TerminalCommand{WITHDRAW,
     *       USER_WALLET_DORMANCY_AUTO_RETURN}} to the user's verified SL avatar,
     *       append {@code DORMANCY_AUTO_RETURN} to the user ledger, debit the
     *       available balance (balance - reserved), and stamp phase 99
     *       (COMPLETED).</li>
     * </ul>
     */
    @Transactional
    public void escalateOrAutoReturn(User user, OffsetDateTime now) {
        Integer currentPhase = user.getWalletDormancyPhase();
        if (currentPhase == null) {
            log.warn("escalateOrAutoReturn called on non-dormant user {}", user.getId());
            return;
        }

        if (currentPhase >= 1 && currentPhase <= 3) {
            int nextPhase = currentPhase + 1;
            user.setWalletDormancyPhase(nextPhase);
            userRepository.save(user);

            long balance = user.getBalanceLindens();
            log.info("user wallet dormancy escalated: userId={}, phase={}, balance={}",
                user.getId(), nextPhase, balance);
            notificationPublisher.userWalletDormancyFlagged(user.getId(), nextPhase, balance);

        } else if (currentPhase == 4) {
            autoReturn(user, now);
        } else {
            log.warn("escalateOrAutoReturn called on user {} with unexpected phase {}",
                user.getId(), currentPhase);
        }
    }

    // -------------------------------------------------------------------------
    // Active-signal reset -- called from login / refresh-token rotation hook
    // -------------------------------------------------------------------------

    /**
     * Clears wallet-dormancy state for a user if they are in an active phase
     * (1-4). Phase 99 (COMPLETED -- auto-return already fired) is intentionally
     * preserved.
     *
     * @param userId the numeric PK of the user to clear
     */
    @Transactional
    public void clearForUser(Long userId) {
        userRepository.findById(userId).ifPresent(user -> {
            Integer phase = user.getWalletDormancyPhase();
            if (phase != null && phase >= 1 && phase <= 4) {
                log.info("clearing user wallet dormancy on login: userId={}, priorPhase={}",
                    userId, phase);
                user.setWalletDormancyPhase(null);
                user.setWalletDormancyStartedAt(null);
                userRepository.save(user);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void autoReturn(User user, OffsetDateTime now) {
        Long userId = user.getId();
        long balance = user.getBalanceLindens();
        long reserved = user.getReservedLindens() == null ? 0L : user.getReservedLindens();
        long autoReturnAmt = Math.max(0L, balance - reserved);

        if (user.getSlAvatarUuid() == null) {
            // Defensive: a user without a verified avatar shouldn't have a
            // positive balance in the first place, but if it ever happens the
            // safer move is to stamp phase 99 without queueing a withdraw the
            // bot can't deliver. Admin can pick up the orphaned balance.
            log.warn("user wallet dormancy auto-return: userId={} has no slAvatarUuid; "
                + "stamping phase 99 without queuing WITHDRAW", userId);
            user.setWalletDormancyPhase(99);
            userRepository.save(user);
            notificationPublisher.userWalletDormancyAutoReturned(userId, 0L);
            return;
        }

        if (autoReturnAmt <= 0L) {
            // Every L$ is reserved against live bids. Stamp phase 99 so the
            // sweep stops escalating; the reserved balance is preserved and
            // will revert to available as the bids resolve. The user can
            // start a new dormancy cycle the next time they go idle with a
            // free balance.
            log.warn("user wallet dormancy auto-return: userId={} has balance={} all "
                + "reserved (reserved={}); stamping phase 99 with no withdraw",
                userId, balance, reserved);
            user.setWalletDormancyPhase(99);
            userRepository.save(user);
            notificationPublisher.userWalletDormancyAutoReturned(userId, 0L);
            return;
        }

        String idempotencyKey = "user-dormancy-" + userId + "-"
            + user.getWalletDormancyStartedAt().toEpochSecond();

        // Queue the TerminalCommand before debiting the balance so the ledger
        // snapshot is meaningful and the command carries the right amount.
        TerminalCommand cmd = terminalCommandRepository.save(TerminalCommand.builder()
            .action(TerminalCommandAction.WITHDRAW)
            .purpose(TerminalCommandPurpose.USER_WALLET_DORMANCY_AUTO_RETURN)
            .recipientUuid(user.getSlAvatarUuid().toString())
            .amount(autoReturnAmt)
            .status(TerminalCommandStatus.QUEUED)
            .idempotencyKey(idempotencyKey)
            .nextAttemptAt(now)
            .attemptCount(0)
            .requiresManualReview(false)
            .build());

        // Debit balance_lindens by the available amount; reserved_lindens is
        // preserved (active bids stay funded).
        user.setBalanceLindens(balance - autoReturnAmt);
        userRepository.save(user);

        ledgerRepository.save(UserLedgerEntry.builder()
            .userId(userId)
            .entryType(UserLedgerEntryType.DORMANCY_AUTO_RETURN)
            .amount(autoReturnAmt)
            .balanceAfter(user.getBalanceLindens())
            .reservedAfter(reserved)
            .refType("TERMINAL_COMMAND")
            .refId(cmd.getId())
            .description("auto-return after 30d inactivity + 4 weekly notices")
            .build());

        user.setWalletDormancyPhase(99);
        userRepository.save(user);

        log.info("user wallet dormancy auto-return queued: userId={}, amount={}, cmdId={}",
            userId, autoReturnAmt, cmd.getId());
        notificationPublisher.userWalletDormancyAutoReturned(userId, autoReturnAmt);
    }
}
