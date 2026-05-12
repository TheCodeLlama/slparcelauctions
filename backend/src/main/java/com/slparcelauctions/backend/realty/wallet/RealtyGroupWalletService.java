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
    /* LISTING FEE DEBIT                                             */
    /* ============================================================ */

    /**
     * Debit a group-listed auction's listing fee from the group wallet.
     * Called from MeWalletController.payListingFee branching on
     * auction.realty_group_id != null. Spec §5.4.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void debitListingFee(Long groupId, Long auctionId, long amount, Long actorUserId) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        RealtyGroup group = groupRepository.findByIdForUpdate(groupId).orElseThrow();
        long available = group.availableLindens();
        if (available < amount) {
            throw new com.slparcelauctions.backend.realty.wallet.exception
                .InsufficientGroupBalanceException(available, amount);
        }
        long newBalance = group.getBalanceLindens() - amount;
        group.setBalanceLindens(newBalance);
        clearDormancyOnActivity(group);
        groupRepository.save(group);

        RealtyGroupLedgerEntry entry = ledgerRepository.save(RealtyGroupLedgerEntry.builder()
            .groupId(groupId)
            .entryType(RealtyGroupLedgerEntryType.LISTING_FEE_DEBIT)
            .amount(amount)
            .balanceAfter(newBalance)
            .reservedAfter(group.getReservedLindens())
            .refType("AUCTION")
            .refId(auctionId)
            .actorUserId(actorUserId)
            .build());

        broadcastPublisher.publish(group.getPublicId(),
            newBalance, group.getReservedLindens(), group.availableLindens(),
            RealtyGroupLedgerEntryType.LISTING_FEE_DEBIT.name(), entry.getPublicId());
        log.info("group listing fee debit: groupId={}, auctionId={}, amount={}, balanceAfter={}, actor={}",
            groupId, auctionId, amount, newBalance, actorUserId);
    }

    /* ============================================================ */
    /* LISTING FEE REFUND CREDIT                                     */
    /* ============================================================ */

    /**
     * Credit the group wallet with a listing-fee refund.
     * Called from {@code ListingFeeRefundProcessorTask} when a
     * {@code realty_group_ledger.LISTING_FEE_DEBIT} row exists for the auction
     * (i.e. the original fee came from the group wallet). Spec §8.3.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void creditListingFeeRefund(Long groupId, Long auctionId, long amount, Long refundRowId) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        RealtyGroup group = groupRepository.findByIdForUpdate(groupId).orElseThrow();
        if (group.getDissolvedAt() != null) {
            throw new IllegalStateException(
                "cannot credit dissolved group " + group.getPublicId()
                + " for listing-fee refund row " + refundRowId);
        }
        long newBalance = group.getBalanceLindens() + amount;
        group.setBalanceLindens(newBalance);
        clearDormancyOnActivity(group);
        groupRepository.save(group);

        RealtyGroupLedgerEntry entry = ledgerRepository.save(RealtyGroupLedgerEntry.builder()
            .groupId(groupId)
            .entryType(RealtyGroupLedgerEntryType.LISTING_FEE_REFUND)
            .amount(amount)
            .balanceAfter(newBalance)
            .reservedAfter(group.getReservedLindens())
            .refType("LISTING_FEE_REFUND")
            .refId(refundRowId)
            .build());

        broadcastPublisher.publish(group.getPublicId(),
            newBalance, group.getReservedLindens(), group.availableLindens(),
            RealtyGroupLedgerEntryType.LISTING_FEE_REFUND.name(), entry.getPublicId());
        log.info("group listing fee refund credit: groupId={}, refundId={}, amount={}, balanceAfter={}",
            groupId, refundRowId, amount, newBalance);
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
