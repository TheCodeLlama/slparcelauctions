package com.slparcelauctions.backend.realty.wallet.dormancy;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.escrow.command.TerminalCommand;
import com.slparcelauctions.backend.escrow.command.TerminalCommandAction;
import com.slparcelauctions.backend.escrow.command.TerminalCommandPurpose;
import com.slparcelauctions.backend.escrow.command.TerminalCommandRepository;
import com.slparcelauctions.backend.escrow.command.TerminalCommandStatus;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerEntry;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerEntryType;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-group operations for the group-wallet dormancy state machine.
 *
 * <p>Methods are {@code @Transactional} so each call runs atomically. The job
 * ({@link GroupWalletDormancyJob}) invokes them in a tight forEach loop — each
 * group is its own transaction so a single failure doesn't block the rest of
 * the sweep.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-11-realty-groups-group-wallet-design.md §10
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GroupWalletDormancyTask {

    private final RealtyGroupRepository groupRepository;
    private final RealtyGroupLedgerRepository ledgerRepository;
    private final TerminalCommandRepository terminalCommandRepository;
    private final UserRepository userRepository;
    private final NotificationPublisher notificationPublisher;

    // -------------------------------------------------------------------------
    // Phase 1: flag a newly-dormant group
    // -------------------------------------------------------------------------

    /**
     * Stamps {@code walletDormancyStartedAt=now, walletDormancyPhase=1} on a group
     * that has just crossed the inactivity window. Sends the first IM escalation to
     * the group leader.
     */
    @Transactional
    public void flag(RealtyGroup group, OffsetDateTime now) {
        group.setWalletDormancyStartedAt(now);
        group.setWalletDormancyPhase((short) 1);
        groupRepository.save(group);

        long balance = group.getBalanceLindens();
        log.info("group dormancy flagged: groupId={}, balance={}, phase=1", group.getId(), balance);
        notificationPublisher.groupWalletDormancyFlagged(group.getId(), 1, balance);
    }

    // -------------------------------------------------------------------------
    // Phases 2-4 and COMPLETED (99): escalate or auto-return
    // -------------------------------------------------------------------------

    /**
     * Advances a dormant group to its next phase.
     *
     * <ul>
     *   <li>Phases 1-3: increment the phase, send the next IM to the leader.</li>
     *   <li>Phase 4: queue a {@code TerminalCommand{WITHDRAW, GROUP_WALLET_WITHDRAWAL}} to
     *       the current leader's avatar, append {@code DORMANCY_AUTO_RETURN} to the ledger,
     *       zero the balance, and stamp phase 99 (COMPLETED).</li>
     * </ul>
     */
    @Transactional
    public void escalateOrAutoReturn(RealtyGroup group, OffsetDateTime now) {
        Short currentPhase = group.getWalletDormancyPhase();
        if (currentPhase == null) {
            log.warn("escalateOrAutoReturn called on non-dormant group {}", group.getId());
            return;
        }

        if (currentPhase >= 1 && currentPhase <= 3) {
            short nextPhase = (short) (currentPhase + 1);
            group.setWalletDormancyPhase(nextPhase);
            groupRepository.save(group);

            long balance = group.getBalanceLindens();
            log.info("group dormancy escalated: groupId={}, phase={}, balance={}",
                group.getId(), nextPhase, balance);
            notificationPublisher.groupWalletDormancyFlagged(group.getId(), nextPhase, balance);

        } else if (currentPhase == 4) {
            autoReturn(group, now);
        } else {
            log.warn("escalateOrAutoReturn called on group {} with unexpected phase {}",
                group.getId(), currentPhase);
        }
    }

    // -------------------------------------------------------------------------
    // Active-signal reset — called from login / refresh-token rotation hook
    // -------------------------------------------------------------------------

    /**
     * Clears dormancy state for a group if it is in an active phase (1-4). Phase 99
     * (COMPLETED — auto-return already fired) is intentionally preserved.
     *
     * @param groupId the numeric PK of the group to clear
     */
    @Transactional
    public void clearForGroup(Long groupId) {
        groupRepository.findById(groupId).ifPresent(group -> {
            Short phase = group.getWalletDormancyPhase();
            if (phase != null && phase >= 1 && phase <= 4) {
                log.info("clearing group dormancy on member login: groupId={}, priorPhase={}",
                    groupId, phase);
                group.setWalletDormancyPhase(null);
                group.setWalletDormancyStartedAt(null);
                groupRepository.save(group);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void autoReturn(RealtyGroup group, OffsetDateTime now) {
        long balance = group.getBalanceLindens();
        Long groupId = group.getId();

        User leader = userRepository.findById(group.getLeaderId())
            .orElseThrow(() -> new IllegalStateException(
                "dormancy auto-return: leader missing for group " + group.getPublicId()));

        String idempotencyKey = "group-dormancy-" + groupId + "-"
            + group.getWalletDormancyStartedAt().toEpochSecond();

        // Queue the TerminalCommand before zeroing the balance so the ledger snapshot
        // is meaningful and the command's recipient is the most-current leader.
        TerminalCommand cmd = terminalCommandRepository.save(TerminalCommand.builder()
            .action(TerminalCommandAction.WITHDRAW)
            .purpose(TerminalCommandPurpose.GROUP_WALLET_WITHDRAWAL)
            .recipientUuid(leader.getSlAvatarUuid().toString())
            .amount(balance)
            .status(TerminalCommandStatus.QUEUED)
            .idempotencyKey(idempotencyKey)
            .realtyGroupId(groupId)
            .nextAttemptAt(now)
            .attemptCount(0)
            .requiresManualReview(false)
            .build());

        // Zero the balance first, then append the ledger row so balanceAfter=0.
        group.setBalanceLindens(0L);
        groupRepository.save(group);

        ledgerRepository.save(RealtyGroupLedgerEntry.builder()
            .groupId(groupId)
            .entryType(RealtyGroupLedgerEntryType.DORMANCY_AUTO_RETURN)
            .amount(balance)
            .balanceAfter(0L)
            .reservedAfter(group.getReservedLindens())
            .refType("TERMINAL_COMMAND")
            .refId(cmd.getId())
            .description("auto-return after 30d inactivity + 4 weekly notices")
            .build());

        group.setWalletDormancyPhase((short) 99);
        groupRepository.save(group);

        log.info("group dormancy auto-return queued: groupId={}, amount={}, leader={}, cmdId={}",
            groupId, balance, leader.getPublicId(), cmd.getId());
        notificationPublisher.groupWalletDormancyAutoReturned(groupId, balance);
    }
}
