package com.slparcelauctions.backend.realty.wallet;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.escrow.command.TerminalCommand;
import com.slparcelauctions.backend.escrow.command.TerminalCommandAction;
import com.slparcelauctions.backend.escrow.command.TerminalCommandPurpose;
import com.slparcelauctions.backend.escrow.command.TerminalCommandRepository;
import com.slparcelauctions.backend.escrow.command.TerminalCommandStatus;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.moderation.RealtyGroupGuard;
import com.slparcelauctions.backend.realty.wallet.broadcast.GroupWalletBroadcastPublisher;
import com.slparcelauctions.backend.user.User;
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
    private final UserRepository userRepository;
    private final TerminalCommandRepository terminalCommandRepository;
    private final NotificationPublisher notificationPublisher;
    private final GroupWalletBroadcastPublisher broadcastPublisher;
    private final RealtyGroupGuard realtyGroupGuard;
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
    /* CASE-3 LISTING PAYOUT (called from AgentCommissionDistributor) */
    /* ============================================================ */

    /**
     * Credit the group wallet with its case-3 share of earnings (after platform
     * commission and the listing agent's commission slice). Called from
     * {@code AgentCommissionDistributor} inside the escrow-payout-success
     * transaction for case-3 (SL-group-owned) auctions. Spec §9.6.
     *
     * <p>Idempotency key {@code LP-{auctionId}} prevents a duplicate credit if
     * the payout callback ever fires twice for the same auction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void creditPayout(Long realtyGroupId, Long auctionId, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        String idempotencyKey = "LP-" + auctionId;
        Optional<RealtyGroupLedgerEntry> existing = ledgerRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.debug("group listing payout replay for auctionId={} (existing entry id={})",
                auctionId, existing.get().getId());
            return;
        }
        RealtyGroup group = groupRepository.findByIdForUpdate(realtyGroupId).orElseThrow();
        long newBalance = group.getBalanceLindens() + amount;
        group.setBalanceLindens(newBalance);
        clearDormancyOnActivity(group);
        groupRepository.save(group);

        RealtyGroupLedgerEntry entry = ledgerRepository.save(RealtyGroupLedgerEntry.builder()
            .groupId(realtyGroupId)
            .entryType(RealtyGroupLedgerEntryType.LISTING_PAYOUT)
            .amount(amount)
            .balanceAfter(newBalance)
            .reservedAfter(group.getReservedLindens())
            .refType("AUCTION")
            .refId(auctionId)
            .idempotencyKey(idempotencyKey)
            .build());

        broadcastPublisher.publish(group.getPublicId(),
            newBalance, group.getReservedLindens(), group.availableLindens(),
            RealtyGroupLedgerEntryType.LISTING_PAYOUT.name(), entry.getPublicId());

        log.info("group listing payout credit: groupId={}, auctionId={}, amount={}, balanceAfter={}",
            realtyGroupId, auctionId, amount, newBalance);
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
    /* WITHDRAW                                                      */
    /* ============================================================ */

    /**
     * Result returned by {@link #withdraw}. The caller (controller) uses
     * {@code queueId} as the handle for progress polling and
     * {@code estimatedFulfillmentSeconds} for UX copy.
     */
    public record WithdrawResult(Long queueId, int estimatedFulfillmentSeconds) {}

    /**
     * Initiate a group-wallet withdrawal. Caller is whoever holds
     * WITHDRAW_FROM_GROUP_WALLET (or the leader). Recipient is always
     * the group leader's verified SL avatar. Spec §5.3.
     *
     * <p>Idempotent: a duplicate {@code idempotencyKey} returns the original
     * result without re-processing.
     */
    @Transactional
    public WithdrawResult withdraw(Long groupId, long amount, UUID idempotencyKey, Long callerUserId) {
        realtyGroupGuard.requireGroupCanOperate(groupId);
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        String idemStr = idempotencyKey.toString();
        Optional<RealtyGroupLedgerEntry> replay = ledgerRepository.findByIdempotencyKey(idemStr);
        if (replay.isPresent()) {
            // Idempotency hit — find the matching TerminalCommand and return the original queueId.
            TerminalCommand prior = terminalCommandRepository
                .findByIdempotencyKey("GWAL-" + replay.get().getId())
                .orElseThrow(() -> new IllegalStateException(
                    "ledger row exists but terminal command missing for GWAL-" + replay.get().getId()));
            return new WithdrawResult(prior.getId(), 60);
        }

        RealtyGroup group = groupRepository.findByIdForUpdate(groupId).orElseThrow();
        User leader = userRepository.findById(group.getLeaderId())
            .orElseThrow(() -> new IllegalStateException(
                "group " + group.getPublicId() + " leader id " + group.getLeaderId() + " missing"));

        if (leader.getWalletTermsAcceptedAt() == null) {
            throw new com.slparcelauctions.backend.realty.wallet.exception
                .LeaderTermsNotAcceptedException(leader.getPublicId());
        }
        if ((leader.getWalletFrozenAt() != null)
                || (leader.getBannedFromListing() != null && leader.getBannedFromListing())) {
            throw new com.slparcelauctions.backend.realty.wallet.exception.LeaderFrozenException();
        }
        long available = group.availableLindens();
        if (available < amount) {
            throw new com.slparcelauctions.backend.realty.wallet.exception
                .InsufficientGroupBalanceException(available, amount);
        }

        long newBalance = group.getBalanceLindens() - amount;
        group.setBalanceLindens(newBalance);
        clearDormancyOnActivity(group);
        groupRepository.save(group);

        RealtyGroupLedgerEntry queuedEntry = ledgerRepository.save(RealtyGroupLedgerEntry.builder()
            .groupId(groupId)
            .entryType(RealtyGroupLedgerEntryType.WITHDRAW_QUEUED)
            .amount(amount)
            .balanceAfter(newBalance)
            .reservedAfter(group.getReservedLindens())
            .actorUserId(callerUserId)
            .idempotencyKey(idemStr)
            .refType("TERMINAL_COMMAND")
            .build());

        TerminalCommand cmd = terminalCommandRepository.save(TerminalCommand.builder()
            .action(TerminalCommandAction.WITHDRAW)
            .purpose(TerminalCommandPurpose.GROUP_WALLET_WITHDRAWAL)
            .recipientUuid(leader.getSlAvatarUuid().toString())
            .amount(amount)
            .status(TerminalCommandStatus.QUEUED)
            .idempotencyKey("GWAL-" + queuedEntry.getId())
            .realtyGroupId(groupId)
            .nextAttemptAt(OffsetDateTime.now(clock))
            .attemptCount(0)
            .requiresManualReview(false)
            .build());

        broadcastPublisher.publish(group.getPublicId(),
            newBalance, group.getReservedLindens(), group.availableLindens(),
            RealtyGroupLedgerEntryType.WITHDRAW_QUEUED.name(), queuedEntry.getPublicId());

        log.info("group withdraw queued: groupId={}, amount={}, leader={}, callerUserId={}, queueId={}",
            groupId, amount, leader.getPublicId(), callerUserId, cmd.getId());
        return new WithdrawResult(cmd.getId(), 60);
    }

    /**
     * Called by {@link com.slparcelauctions.backend.realty.wallet.GroupWalletWithdrawalCallbackHandler}
     * when the terminal confirms the L$ transfer. No balance change — balance was
     * decremented at queue time. Spec §5.3.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordWithdrawalSuccess(Long queuedLedgerId, String slTransactionKey) {
        RealtyGroupLedgerEntry queued = ledgerRepository.findById(queuedLedgerId).orElseThrow();
        ledgerRepository.save(RealtyGroupLedgerEntry.builder()
            .groupId(queued.getGroupId())
            .entryType(RealtyGroupLedgerEntryType.WITHDRAW_COMPLETED)
            .amount(queued.getAmount())
            .balanceAfter(queued.getBalanceAfter())
            .reservedAfter(queued.getReservedAfter())
            .slTransactionId(slTransactionKey)
            .refType("REALTY_GROUP_LEDGER_ENTRY")
            .refId(queuedLedgerId)
            .build());
        log.info("group withdraw completed: ledgerId={}, slTxn={}", queuedLedgerId, slTransactionKey);
    }

    /**
     * Called by {@link com.slparcelauctions.backend.realty.wallet.GroupWalletWithdrawalCallbackHandler}
     * when the terminal fails after retry exhaustion. Credits the L$ back to the
     * group balance and appends a WITHDRAW_REVERSED row. Spec §5.3.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordWithdrawalReversal(Long queuedLedgerId, String reason) {
        RealtyGroupLedgerEntry queued = ledgerRepository.findById(queuedLedgerId).orElseThrow();
        RealtyGroup group = groupRepository.findByIdForUpdate(queued.getGroupId()).orElseThrow();
        long newBalance = group.getBalanceLindens() + queued.getAmount();
        group.setBalanceLindens(newBalance);
        groupRepository.save(group);

        RealtyGroupLedgerEntry reversed = ledgerRepository.save(RealtyGroupLedgerEntry.builder()
            .groupId(queued.getGroupId())
            .entryType(RealtyGroupLedgerEntryType.WITHDRAW_REVERSED)
            .amount(queued.getAmount())
            .balanceAfter(newBalance)
            .reservedAfter(group.getReservedLindens())
            .description(reason == null ? "transport failure" : reason)
            .refType("REALTY_GROUP_LEDGER_ENTRY")
            .refId(queuedLedgerId)
            .build());

        broadcastPublisher.publish(group.getPublicId(),
            newBalance, group.getReservedLindens(), group.availableLindens(),
            RealtyGroupLedgerEntryType.WITHDRAW_REVERSED.name(), reversed.getPublicId());
        log.warn("group withdraw reversed: ledgerId={}, reason={}, balanceAfter={}",
            queuedLedgerId, reason, newBalance);
    }

    /**
     * Returns the {@code groupId} of a realty_group_ledger row, or {@code null}
     * if the row does not exist. Used by callback handlers to route notifications.
     */
    public Long findGroupIdForLedgerEntry(Long ledgerEntryId) {
        return ledgerRepository.findById(ledgerEntryId)
            .map(RealtyGroupLedgerEntry::getGroupId)
            .orElse(null);
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
