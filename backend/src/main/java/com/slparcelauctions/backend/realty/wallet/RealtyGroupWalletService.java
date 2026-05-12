package com.slparcelauctions.backend.realty.wallet;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.escrow.command.TerminalCommandRepository;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.wallet.broadcast.GroupWalletBroadcastPublisher;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Core realty-group wallet operations. All balance-mutating methods run with
 * {@link Propagation#MANDATORY} — callers must have already begun a transaction
 * and hold the relevant locks.
 *
 * <p>See spec docs/superpowers/specs/2026-05-11-realty-groups-group-wallet-design.md
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RealtyGroupWalletService {

    private final RealtyGroupRepository groupRepository;
    private final RealtyGroupLedgerRepository ledgerRepository;
    // Declared now for constructor stability; used in later tasks.
    @SuppressWarnings("unused")
    private final UserRepository userRepository;
    @SuppressWarnings("unused")
    private final TerminalCommandRepository terminalCommandRepository;
    @SuppressWarnings("unused")
    private final NotificationPublisher notificationPublisher;
    private final GroupWalletBroadcastPublisher broadcastPublisher;
    @SuppressWarnings("unused")
    private final Clock clock;

    /* ============================================================ */
    /* AGENT FEE CREDIT (called from AgentFeeDistributor)            */
    /* ============================================================ */

    /**
     * Credit the group wallet with its share of agent_fee_amt. Called from
     * {@code AgentFeeDistributor} inside the escrow-payout-success transaction.
     * Spec §7.2.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void creditAgentFee(Long groupId, Long auctionId, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        RealtyGroup group = groupRepository.findByIdForUpdate(groupId).orElseThrow();
        long newBalance = group.getBalanceLindens() + amount;
        group.setBalanceLindens(newBalance);
        clearDormancyOnActivity(group);
        groupRepository.save(group);

        RealtyGroupLedgerEntry entry = ledgerRepository.save(RealtyGroupLedgerEntry.builder()
            .groupId(groupId)
            .entryType(RealtyGroupLedgerEntryType.AGENT_FEE_CREDIT)
            .amount(amount)
            .balanceAfter(newBalance)
            .reservedAfter(group.getReservedLindens())
            .refType("AUCTION")
            .refId(auctionId)
            .build());

        broadcastPublisher.publish(group.getPublicId(),
            newBalance, group.getReservedLindens(), group.availableLindens(),
            RealtyGroupLedgerEntryType.AGENT_FEE_CREDIT.name(), entry.getPublicId());

        log.info("group agent fee credit: groupId={}, auctionId={}, amount={}, balanceAfter={}",
            groupId, auctionId, amount, newBalance);
    }

    /* ============================================================ */
    /* INTERNAL HELPERS                                              */
    /* ============================================================ */

    /**
     * Clear dormancy phase and start timestamp if a group is in an active
     * dormancy phase (1–4). Phase 99 (COMPLETED — auto-return already fired)
     * is intentionally preserved. Spec §10.4.
     */
    private void clearDormancyOnActivity(RealtyGroup group) {
        if (group.getWalletDormancyPhase() != null && group.getWalletDormancyPhase() != 99) {
            log.info("clearing group dormancy on activity: groupId={}, priorPhase={}",
                group.getId(), group.getWalletDormancyPhase());
            group.setWalletDormancyPhase(null);
            group.setWalletDormancyStartedAt(null);
        }
    }
}
