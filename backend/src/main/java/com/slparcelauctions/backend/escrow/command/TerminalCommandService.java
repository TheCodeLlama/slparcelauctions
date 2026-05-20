package com.slparcelauctions.backend.escrow.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.slparcelauctions.backend.admin.infrastructure.terminals.TerminalSecretService;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.AuctionStatusFlipper;
import com.slparcelauctions.backend.auction.ListingFeeRefund;
import com.slparcelauctions.backend.auction.ListingFeeRefundRepository;
import com.slparcelauctions.backend.auction.RefundStatus;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowService;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.escrow.EscrowTransaction;
import com.slparcelauctions.backend.escrow.EscrowTransactionRepository;
import com.slparcelauctions.backend.escrow.EscrowTransactionStatus;
import com.slparcelauctions.backend.escrow.EscrowTransactionType;
import com.slparcelauctions.backend.escrow.broadcast.EscrowBroadcastPublisher;
import com.slparcelauctions.backend.escrow.broadcast.EscrowCompletedEnvelope;
import com.slparcelauctions.backend.escrow.broadcast.EscrowPayoutStalledEnvelope;
import com.slparcelauctions.backend.escrow.broadcast.EscrowRefundCompletedEnvelope;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.escrow.command.dto.PayoutResultRequest;
import com.slparcelauctions.backend.escrow.command.exception.UnknownTerminalCommandException;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Core service for the outbound terminal command pipeline (spec §7.2, §7.4).
 * Exposes three {@code queue*} methods that persist a fresh {@code QUEUED}
 * {@link TerminalCommand} row for the dispatcher to pick up, and one
 * {@link #applyCallback(PayoutResultRequest)} entry point the
 * {@link PayoutResultController} calls when a terminal posts back a result.
 *
 * <p>The queue methods run under
 * {@link Propagation#MANDATORY} so a stray call outside a transaction fails
 * fast — the caller is always {@link EscrowService} in the middle of a
 * state-transition transaction, and the queued row must be rolled back with
 * the parent if the transition fails.
 *
 * <p>The callback method runs in its own transaction with a pessimistic lock
 * on the command row so it serialises against the dispatcher's
 * {@code markStaleAndRequeue} and {@code dispatchOne} paths. On success it
 * transitions the escrow state (PAYOUT only — REFUND and LISTING_FEE_REFUND
 * are ledger-only), writes the COMPLETED ledger row(s), and registers the
 * success envelope for afterCommit publication. On failure below the retry
 * cap it schedules backoff; at the cap it stalls and registers the
 * {@code ESCROW_PAYOUT_STALLED} envelope.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TerminalCommandService {

    private final TerminalCommandRepository cmdRepo;
    private final EscrowRepository escrowRepo;
    private final EscrowTransactionRepository ledgerRepo;
    private final ListingFeeRefundRepository listingFeeRefundRepo;
    private final UserRepository userRepo;
    private final EscrowBroadcastPublisher broadcastPublisher;
    private final NotificationPublisher notificationPublisher;
    private final Clock clock;
    private final TerminalSecretService terminalSecretService;
    private final com.slparcelauctions.backend.admin.infrastructure.withdrawals.WithdrawalCallbackHandler withdrawalCallbackHandler;
    private final com.slparcelauctions.backend.wallet.WalletWithdrawalCallbackHandler walletWithdrawalCallbackHandler;
    private final com.slparcelauctions.backend.auction.agentfee.AgentCommissionDistributor agentCommissionDistributor;
    private final com.slparcelauctions.backend.realty.wallet.GroupWalletWithdrawalCallbackHandler groupWalletWithdrawalCallbackHandler;
    private final com.slparcelauctions.backend.wallet.WalletService walletService;
    private final RealtyGroupRepository realtyGroupRepository;
    private final AuctionStatusFlipper statusFlipper;

    /**
     * Runs the escrow-payout success path inline. No terminal round-trip
     * happens for either sale shape any more:
     *
     * <ul>
     *   <li>Individual sale ({@code payoutAmt > 0}, no
     *       {@code realtyGroupSlGroupId}): credits the seller's SLParcels
     *       wallet via {@link com.slparcelauctions.backend.wallet.WalletService#creditAuctionPayout}
     *       and then runs the shared bookkeeping. Replaces the prior
     *       {@code TerminalCommand{action=PAYOUT}} dispatch to the seller's
     *       avatar -- per the wallet-first policy, L$ stays inside SLParcels
     *       at sale conclusion and the seller withdraws separately if they
     *       choose.</li>
     *   <li>Group sale ({@code payoutAmt == 0},
     *       {@code realtyGroupSlGroupId != null}): no wallet credit (the
     *       distributor splits the earnings into agent + group slices);
     *       runs the same shared bookkeeping; then invokes
     *       {@link com.slparcelauctions.backend.auction.agentfee.AgentCommissionDistributor#distribute}.</li>
     * </ul>
     *
     * <p>Always returns {@link Optional#empty()}: no {@link TerminalCommand}
     * is enqueued from this path post wallet-first cutover. The method name
     * is kept for the EscrowService call-site shape; rename is out of scope.
     *
     * <p>Historical in-flight {@link TerminalCommandAction#PAYOUT} rows queued
     * before this cutover continue to flow through
     * {@link #handleEscrowPayoutSuccess} via the callback path.
     *
     * @return always {@link Optional#empty()}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<TerminalCommand> queuePayout(Escrow escrow) {
        if (escrow.getState() == EscrowState.COMPLETED) {
            // Idempotent replay: a previous call already ran the inline
            // success path. No-op so we don't double-write the ledger row,
            // double-credit the wallet, double-notify the seller, or
            // double-credit commissions.
            log.info("queuePayout: escrow {} already COMPLETED, no-op", escrow.getId());
            return Optional.empty();
        }
        long payoutAmt = escrow.getPayoutAmt() == null ? 0L : escrow.getPayoutAmt();
        if (payoutAmt > 0L) {
            log.info("queuePayout: escrow {} payoutAmt={} (individual sale), crediting seller wallet inline",
                    escrow.getId(), payoutAmt);
            User seller = escrow.getAuction().getSeller();
            walletService.creditAuctionPayout(
                    seller.getId(),
                    escrow.getAuction().getId(),
                    escrow.getId(),
                    payoutAmt);
        } else {
            log.info("queuePayout: escrow {} payoutAmt=0 (group sale), running success path inline",
                    escrow.getId());
        }
        runPayoutSuccessInline(escrow, OffsetDateTime.now(clock), payoutAmt);
        return Optional.empty();
    }

    /**
     * Historical note: {@code queueRefund} and {@code queueListingFeeRefund}
     * lived here until the wallet-model-always-on migration. Both refund
     * surfaces (escrow refund, listing-fee refund) now credit the
     * recipient's SLParcels wallet via {@link
     * com.slparcelauctions.backend.wallet.WalletService#creditEscrowRefund}
     * / {@link
     * com.slparcelauctions.backend.wallet.WalletService#creditListingFeeRefund}
     * / {@link
     * com.slparcelauctions.backend.realty.wallet.RealtyGroupWalletService#creditListingFeeRefund}.
     * The {@link TerminalCommandAction#REFUND} enum value is retained
     * because historical rows + in-flight REFUND commands at deploy time
     * still need their callback handled (see {@link #applyCallback}).
     */
    /**
     * Queues an admin WITHDRAW command. Called from
     * {@code AdminWithdrawalService.requestWithdrawal} which already holds
     * a transaction, so {@link Propagation#MANDATORY} enforces that the
     * command row is atomically committed with the parent Withdrawal row.
     * Uses primitive parameters to avoid a cross-package circular
     * dependency with the admin withdrawals package.
     *
     * @param withdrawalId the synthetic PK of the parent Withdrawal row
     * @param recipientUuid the SL avatar UUID of the withdrawal recipient
     * @param amount the L$ amount to withdraw
     * @return the saved {@link TerminalCommand} row
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TerminalCommand queueWithdraw(Long withdrawalId, String recipientUuid, long amount) {
        return queue(null, null,
                TerminalCommandAction.WITHDRAW, TerminalCommandPurpose.ADMIN_WITHDRAWAL,
                recipientUuid, amount,
                "withdraw:" + withdrawalId);
    }

    private String idempotencyKey(String prefix, Long id,
            TerminalCommandAction action, int seq) {
        return prefix + "-" + id + "-" + action.name() + "-" + seq;
    }

    private TerminalCommand queue(Long escrowId, Long refundId,
            TerminalCommandAction action, TerminalCommandPurpose purpose,
            String recipientUuid, long amount, String idempotencyKey) {
        TerminalCommand cmd = TerminalCommand.builder()
                .escrowId(escrowId)
                .listingFeeRefundId(refundId)
                .action(action)
                .purpose(purpose)
                .recipientUuid(recipientUuid)
                .amount(amount)
                .status(TerminalCommandStatus.QUEUED)
                .idempotencyKey(idempotencyKey)
                .nextAttemptAt(OffsetDateTime.now(clock))
                .attemptCount(0)
                .requiresManualReview(false)
                .build();
        Integer currentVersion = terminalSecretService.current()
                .map(s -> s.getSecretVersion()).orElse(null);
        if (currentVersion != null) {
            cmd.setSharedSecretVersion(String.valueOf(currentVersion));
        }
        TerminalCommand saved = cmdRepo.save(cmd);
        log.info("Queued terminal command {}: action={}, purpose={}, escrowId={}, refundId={}, idempotencyKey={}",
                saved.getId(), action, purpose, escrowId, refundId, idempotencyKey);
        return saved;
    }

    /**
     * Terminal-posted callback handler. Runs under its own transaction so it
     * serialises against the dispatcher paths via the pessimistic lock on
     * the command row. Idempotent: an already-{@code COMPLETED} command is
     * a no-op so retries of the same callback don't duplicate ledger rows
     * or state transitions.
     */
    @Transactional
    public void applyCallback(PayoutResultRequest req) {
        TerminalCommand initial = cmdRepo.findByIdempotencyKey(req.idempotencyKey())
                .orElseThrow(() -> new UnknownTerminalCommandException(req.idempotencyKey()));
        TerminalCommand cmd = cmdRepo.findByIdForUpdate(initial.getId()).orElseThrow();

        if (cmd.getStatus() == TerminalCommandStatus.COMPLETED) {
            // Idempotent replay of a previously-successful callback: return
            // OK without re-processing. Ledger + state are already stamped.
            return;
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        if (req.success()) {
            cmd.setStatus(TerminalCommandStatus.COMPLETED);
            cmd.setCompletedAt(now);
            cmd.setLastError(null);
            cmd = cmdRepo.save(cmd);
            applySuccessfulCallback(cmd, req.slTransactionKey(), now);
            log.info("Terminal command {} COMPLETED via callback (purpose={}, action={}, slTxn={})",
                    cmd.getId(), cmd.getPurpose(), cmd.getAction(), req.slTransactionKey());
        } else {
            // Write a FAILED escrow_transactions row so the dispute timeline
            // + forensic replay capture the failed attempt even if the next
            // retry eventually succeeds. The row is keyed to the originating
            // escrow / auction so the UI timeline surfaces it. Skip for the
            // non-escrow command shapes — ADMIN_WITHDRAWAL is tracked in
            // the Withdrawal entity, WALLET_WITHDRAWAL in user_ledger.
            Escrow escrow = cmd.getEscrowId() == null
                    ? null
                    : escrowRepo.findById(cmd.getEscrowId()).orElse(null);
            if (cmd.getPurpose() != TerminalCommandPurpose.ADMIN_WITHDRAWAL
                    && cmd.getPurpose() != TerminalCommandPurpose.WALLET_WITHDRAWAL
                    && cmd.getPurpose() != TerminalCommandPurpose.GROUP_WALLET_WITHDRAWAL
                    && cmd.getPurpose() != TerminalCommandPurpose.USER_WALLET_DORMANCY_AUTO_RETURN) {
                ledgerRepo.save(buildFailedLedgerRow(
                        cmd, escrow, req.errorMessage(), req.slTransactionKey()));
            }

            cmd.setLastError(req.errorMessage());
            if (cmd.getAttemptCount() < EscrowRetryPolicy.MAX_ATTEMPTS) {
                cmd.setStatus(TerminalCommandStatus.FAILED);
                cmd.setNextAttemptAt(now.plus(
                        EscrowRetryPolicy.backoffFor(cmd.getAttemptCount())));
                cmd = cmdRepo.save(cmd);
                log.warn("Terminal command {} callback reported failure: attempt {}/{}, nextAttemptAt={}, err={}",
                        cmd.getId(), cmd.getAttemptCount(), EscrowRetryPolicy.MAX_ATTEMPTS,
                        cmd.getNextAttemptAt(), req.errorMessage());
            } else {
                cmd.setStatus(TerminalCommandStatus.FAILED);
                cmd.setRequiresManualReview(true);
                cmd = cmdRepo.save(cmd);
                if (escrow != null) {
                    publishStallAfterCommit(cmd, escrow, now);
                }
                if (cmd.getPurpose() == TerminalCommandPurpose.ADMIN_WITHDRAWAL) {
                    withdrawalCallbackHandler.onFailure(cmd.getId(), req.errorMessage());
                } else if (cmd.getPurpose() == TerminalCommandPurpose.WALLET_WITHDRAWAL) {
                    walletWithdrawalCallbackHandler.onStall(cmd, req.errorMessage());
                } else if (cmd.getPurpose() == TerminalCommandPurpose.GROUP_WALLET_WITHDRAWAL) {
                    groupWalletWithdrawalCallbackHandler.onStall(cmd, req.errorMessage());
                }
                log.error("Terminal command {} STALLED after {} attempts: err={}",
                        cmd.getId(), cmd.getAttemptCount(), req.errorMessage());
            }
        }
    }

    private void applySuccessfulCallback(TerminalCommand cmd, String slTxn, OffsetDateTime now) {
        if (cmd.getPurpose() == TerminalCommandPurpose.AUCTION_ESCROW
                && cmd.getAction() == TerminalCommandAction.PAYOUT) {
            handleEscrowPayoutSuccess(cmd, slTxn, now);
        } else if (cmd.getPurpose() == TerminalCommandPurpose.AUCTION_ESCROW
                && cmd.getAction() == TerminalCommandAction.REFUND) {
            handleEscrowRefundSuccess(cmd, slTxn, now);
        } else if (cmd.getPurpose() == TerminalCommandPurpose.LISTING_FEE_REFUND) {
            handleListingFeeRefundSuccess(cmd, slTxn, now);
        } else if (cmd.getPurpose() == TerminalCommandPurpose.ADMIN_WITHDRAWAL) {
            withdrawalCallbackHandler.onSuccess(cmd.getId());
        } else if (cmd.getPurpose() == TerminalCommandPurpose.WALLET_WITHDRAWAL) {
            walletWithdrawalCallbackHandler.onSuccess(cmd, slTxn);
        } else if (cmd.getPurpose() == TerminalCommandPurpose.GROUP_WALLET_WITHDRAWAL) {
            groupWalletWithdrawalCallbackHandler.onSuccess(cmd, slTxn);
        } else if (cmd.getPurpose() == TerminalCommandPurpose.USER_WALLET_DORMANCY_AUTO_RETURN) {
            // The dormancy auto-return is fire-and-forget at the success-path
            // level. The ledger row + balance debit were appended inline by
            // UserWalletDormancyTask.autoReturn before the command was queued;
            // a successful bot delivery only marks the command COMPLETED.
            // Failures past the retry budget land in requiresManualReview=true
            // and an admin reviews the orphaned balance manually (mirrors
            // the group dormancy path which has no callback handler either).
            log.info("user-wallet dormancy auto-return delivered: cmdId={}, slTxn={}",
                cmd.getId(), slTxn);
        } else {
            throw new IllegalStateException(
                    "Unhandled terminal command callback: purpose=" + cmd.getPurpose()
                            + ", action=" + cmd.getAction() + ", commandId=" + cmd.getId());
        }
    }

    /**
     * Historical callback handler for in-flight {@link TerminalCommandAction#PAYOUT}
     * commands. New payouts (both individual and group sales) no longer queue
     * a terminal command -- {@link #queuePayout} runs the success path inline
     * via {@link #runPayoutSuccessInline}. This handler is preserved so that
     * PAYOUT rows in {@code QUEUED} / {@code IN_FLIGHT} state at the wallet-
     * first cutover can still complete via their callback when the terminal
     * eventually responds.
     */
    private void handleEscrowPayoutSuccess(TerminalCommand cmd, String slTxn, OffsetDateTime now) {
        Escrow escrow = escrowRepo.findByIdForUpdate(cmd.getEscrowId()).orElseThrow();
        EscrowService.enforceTransitionAllowed(
                escrow.getId(), escrow.getState(), EscrowState.COMPLETED);
        escrow.setState(EscrowState.COMPLETED);
        escrow.setCompletedAt(now);
        escrow = escrowRepo.save(escrow);
        // Lockstep auction-status flip: this is the real escrow → COMPLETED
        // transition site, so the auction also lands at COMPLETED here.
        statusFlipper.flip(escrow, AuctionStatus.COMPLETED);

        // Epic 08 sub-spec 1 §3.4 / §6.1: track completed sales for the
        // seller. The counter has been declared on User since Epic 02 but
        // was never written; sub-spec 1 starts writing it so the
        // completion-rate mapper + reputation aggregates have a real number
        // to work with. Incremented inside the same transaction that flipped
        // the escrow to COMPLETED so the counter cannot drift on a crash
        // between steps.
        User seller = escrow.getAuction().getSeller();
        int prior = seller.getCompletedSales() == null ? 0 : seller.getCompletedSales();
        seller.setCompletedSales(prior + 1);
        userRepo.save(seller);

        // PAYOUT and COMMISSION land as separate ledger rows so audit and
        // accounting can bucket them independently. Spec §7.2.
        ledgerRepo.save(EscrowTransaction.builder()
                .escrow(escrow)
                .auction(escrow.getAuction())
                .type(EscrowTransactionType.AUCTION_ESCROW_PAYOUT)
                .status(EscrowTransactionStatus.COMPLETED)
                .amount(cmd.getAmount())
                .payee(escrow.getAuction().getSeller())
                .slTransactionId(slTxn)
                .terminalId(cmd.getTerminalId())
                .completedAt(now)
                .build());
        // COMMISSION rows intentionally omit a payee — the SLParcels platform itself is the
        // recipient and we have no User entity for the internal platform account.
        // Reconciliation by type (AUCTION_ESCROW_COMMISSION) is the canonical path.
        ledgerRepo.save(EscrowTransaction.builder()
                .escrow(escrow)
                .auction(escrow.getAuction())
                .type(EscrowTransactionType.AUCTION_ESCROW_COMMISSION)
                .status(EscrowTransactionStatus.COMPLETED)
                .amount(escrow.getCommissionAmt())
                .slTransactionId(slTxn)
                .terminalId(cmd.getTerminalId())
                .completedAt(now)
                .build());

        final Escrow finalEscrow = escrow;
        final EscrowCompletedEnvelope env = EscrowCompletedEnvelope.of(finalEscrow, now);
        registerAfterCommit(() -> broadcastPublisher.publishCompleted(env));

        // Realty-group payout splitting.
        //
        //   group sale (SL-group-owned): realty_group_sl_group_id IS NOT NULL.
        //       The escrow's payoutAmt is 0 (set by EscrowService.createForEndedAuction);
        //       no L$ leaves SLPA via the terminal. AgentCommissionDistributor credits
        //       the full earnings (finalBid - commission) to the listing agent's wallet
        //       (agent_slice) and the group wallet (group_slice) using
        //       agent_commission_rate. Spec §8.5, §9.6.
        //
        //   individual sale: realty_group_sl_group_id IS NULL -- nothing to split.
        //
        //   (The pre-G "agent listing own land under a group" path -- realty_group_id
        //   set but realty_group_sl_group_id null -- was removed when sub-project G
        //   deleted the legacy distributor.)
        //
        // The distribute call has to precede notificationPublisher.escrowPayout
        // so the seller-facing body can surface the agent-slice + group-slice
        // breakdown (spec §8.3) for group sales; without those values the 5-arg
        // overload would route into the individual-sale wallet-credit branch
        // and silently misread the group sale as a L$0 payout.
        if (finalEscrow.getAuction().getRealtyGroupSlGroupId() != null) {
            com.slparcelauctions.backend.auction.agentfee.AgentCommissionDistributor.SplitResult split =
                agentCommissionDistributor.distribute(
                    finalEscrow.getAuction(),
                    finalEscrow.getFinalBidAmount(),
                    finalEscrow.getCommissionAmt());

            String groupName = realtyGroupRepository
                .findById(finalEscrow.getAuction().getRealtyGroupId())
                .map(RealtyGroup::getName)
                .orElse(null);

            notificationPublisher.escrowPayout(
                    finalEscrow.getAuction().getSeller().getId(),
                    finalEscrow.getAuction().getId(),
                    finalEscrow.getId(),
                    finalEscrow.getAuction().getTitle(),
                    /* payoutL */ 0L,
                    groupName,
                    /* commissionAmt */ split.agentSlice(),
                    /* groupSliceAmt */ split.groupSlice());
        } else {
            // Individual sale -- 5-arg overload routes into the "credited to
            // your SLParcels wallet" branch.
            notificationPublisher.escrowPayout(
                    finalEscrow.getAuction().getSeller().getId(),
                    finalEscrow.getAuction().getId(),
                    finalEscrow.getId(),
                    finalEscrow.getAuction().getTitle(),
                    cmd.getAmount());
        }
    }

    /**
     * Shared post-payout success bookkeeping for both sale shapes. Mirrors
     * the body of {@link #handleEscrowPayoutSuccess} (which still runs for
     * in-flight historical {@link TerminalCommandAction#PAYOUT} callbacks)
     * but is driven directly from {@link #queuePayout}, no terminal
     * round-trip.
     *
     * <p>Writes the {@code AUCTION_ESCROW_PAYOUT} + {@code AUCTION_ESCROW_COMMISSION}
     * escrow_transactions rows, flips escrow -> COMPLETED, lockstep flips
     * the auction to COMPLETED, bumps the seller's completed-sales counter,
     * broadcasts the {@link EscrowCompletedEnvelope} after commit, and
     * notifies the seller. For group sales (auctions with
     * {@code realtyGroupSlGroupId}) the caller has already passed
     * {@code sellerCreditAmt == 0} (no wallet credit happened in the parent);
     * this method then invokes
     * {@link com.slparcelauctions.backend.auction.agentfee.AgentCommissionDistributor#distribute}
     * to split earnings into agent + group slices. Individual sales pass
     * {@code sellerCreditAmt > 0}; the caller has already credited the
     * seller's wallet via
     * {@link com.slparcelauctions.backend.wallet.WalletService#creditAuctionPayout}
     * and no distributor call is made here.
     *
     * <p>Idempotency is enforced by the caller -- {@link #queuePayout} short-
     * circuits when the escrow is already COMPLETED.
     *
     * @param escrow            the escrow being completed (state must be a
     *                          legal predecessor of {@code COMPLETED}).
     * @param now               the completion timestamp.
     * @param sellerCreditAmt   L$ paid to the seller this transition.
     *                          {@code > 0} for individual sales (wallet
     *                          credit already issued), {@code 0} for group
     *                          sales (split flows through the distributor).
     *                          Surfaces as the {@code AUCTION_ESCROW_PAYOUT}
     *                          ledger-row {@code amount} so reconciliation by
     *                          type continues to balance.
     */
    private void runPayoutSuccessInline(Escrow escrow, OffsetDateTime now, long sellerCreditAmt) {
        EscrowService.enforceTransitionAllowed(
                escrow.getId(), escrow.getState(), EscrowState.COMPLETED);
        escrow.setState(EscrowState.COMPLETED);
        escrow.setCompletedAt(now);
        escrow = escrowRepo.save(escrow);
        // Lockstep auction-status flip: the inline path still transitions
        // the escrow to COMPLETED, so the auction lands at COMPLETED here too.
        statusFlipper.flip(escrow, AuctionStatus.COMPLETED);

        User seller = escrow.getAuction().getSeller();
        int prior = seller.getCompletedSales() == null ? 0 : seller.getCompletedSales();
        seller.setCompletedSales(prior + 1);
        userRepo.save(seller);

        // Same row shape as the historical terminal-callback path; no slTxn /
        // terminalId because no terminal round-trip happened. Reconciliation
        // by type (AUCTION_ESCROW_PAYOUT) still works.
        ledgerRepo.save(EscrowTransaction.builder()
                .escrow(escrow)
                .auction(escrow.getAuction())
                .type(EscrowTransactionType.AUCTION_ESCROW_PAYOUT)
                .status(EscrowTransactionStatus.COMPLETED)
                .amount(sellerCreditAmt)
                .payee(escrow.getAuction().getSeller())
                .completedAt(now)
                .build());
        ledgerRepo.save(EscrowTransaction.builder()
                .escrow(escrow)
                .auction(escrow.getAuction())
                .type(EscrowTransactionType.AUCTION_ESCROW_COMMISSION)
                .status(EscrowTransactionStatus.COMPLETED)
                .amount(escrow.getCommissionAmt())
                .completedAt(now)
                .build());

        final Escrow finalEscrow = escrow;
        final EscrowCompletedEnvelope env = EscrowCompletedEnvelope.of(finalEscrow, now);
        registerAfterCommit(() -> broadcastPublisher.publishCompleted(env));

        // Group-sale distributor: credits agent_slice to the listing agent's
        // wallet, group_slice to the group wallet. Only group sales reach this
        // branch (realtyGroupSlGroupId != null); individual sales were already
        // credited to the seller's wallet by the queuePayout call site.
        //
        // The distribute call has to precede notificationPublisher.escrowPayout
        // so the seller-facing body can surface the agent-slice + group-slice
        // breakdown (spec §8.3); without those values the 5-arg overload routes
        // into the individual-sale "credited to your SLParcels wallet" branch
        // and silently misreads the group sale as a L$0 payout.
        if (finalEscrow.getAuction().getRealtyGroupSlGroupId() != null) {
            com.slparcelauctions.backend.auction.agentfee.AgentCommissionDistributor.SplitResult split =
                agentCommissionDistributor.distribute(
                    finalEscrow.getAuction(),
                    finalEscrow.getFinalBidAmount(),
                    finalEscrow.getCommissionAmt());

            String groupName = realtyGroupRepository
                .findById(finalEscrow.getAuction().getRealtyGroupId())
                .map(RealtyGroup::getName)
                .orElse(null);

            notificationPublisher.escrowPayout(
                    finalEscrow.getAuction().getSeller().getId(),
                    finalEscrow.getAuction().getId(),
                    finalEscrow.getId(),
                    finalEscrow.getAuction().getTitle(),
                    /* payoutL */ 0L,
                    groupName,
                    /* commissionAmt */ split.agentSlice(),
                    /* groupSliceAmt */ split.groupSlice());
        } else {
            // Individual sale -- the 5-arg overload routes into the
            // "L$X credited to your SLParcels wallet" branch.
            notificationPublisher.escrowPayout(
                    finalEscrow.getAuction().getSeller().getId(),
                    finalEscrow.getAuction().getId(),
                    finalEscrow.getId(),
                    finalEscrow.getAuction().getTitle(),
                    sellerCreditAmt);
        }
    }

    private void handleEscrowRefundSuccess(TerminalCommand cmd, String slTxn, OffsetDateTime now) {
        Escrow escrow = escrowRepo.findById(cmd.getEscrowId()).orElseThrow();
        // Refund commands are queued from DISPUTED / FROZEN / EXPIRED —
        // the escrow is already in its terminal state, so the callback is
        // ledger-only.
        ledgerRepo.save(EscrowTransaction.builder()
                .escrow(escrow)
                .auction(escrow.getAuction())
                .type(EscrowTransactionType.AUCTION_ESCROW_REFUND)
                .status(EscrowTransactionStatus.COMPLETED)
                .amount(cmd.getAmount())
                .slTransactionId(slTxn)
                .terminalId(cmd.getTerminalId())
                .completedAt(now)
                .build());
        final Escrow finalEscrow = escrow;
        final long amount = cmd.getAmount();
        final EscrowRefundCompletedEnvelope env = EscrowRefundCompletedEnvelope.of(
                finalEscrow, amount, now);
        registerAfterCommit(() -> broadcastPublisher.publishRefundCompleted(env));
    }

    private void handleListingFeeRefundSuccess(TerminalCommand cmd, String slTxn,
            OffsetDateTime now) {
        ListingFeeRefund refund = listingFeeRefundRepo.findById(cmd.getListingFeeRefundId())
                .orElseThrow();
        refund.setStatus(RefundStatus.PROCESSED);
        refund.setProcessedAt(now);
        refund.setTxnRef(slTxn);
        listingFeeRefundRepo.save(refund);

        ledgerRepo.save(EscrowTransaction.builder()
                .auction(refund.getAuction())
                .type(EscrowTransactionType.LISTING_FEE_REFUND)
                .status(EscrowTransactionStatus.COMPLETED)
                .amount(cmd.getAmount())
                .slTransactionId(slTxn)
                .terminalId(cmd.getTerminalId())
                .completedAt(now)
                .build());
        // No envelope for listing-fee refunds — they are a seller-side
        // admin flow; the auction room has no reason to observe them.
    }

    /**
     * Builds a FAILED {@link EscrowTransaction} ledger row for the given
     * command + (optional) escrow + error context. Public + static so the
     * dispatcher's transport-failure stall path can reuse the same row shape
     * without duplicating the {@code .builder()} chain. Callers persist the
     * returned entity via their own {@code EscrowTransactionRepository}.
     *
     * <p>Both branches that emit a FAILED row — terminal-reported failures
     * (in this service) and transport-failure stalls (in the dispatcher) —
     * route through this helper so the dispute timeline surfaces them
     * uniformly.
     */
    public static EscrowTransaction buildFailedLedgerRow(
            TerminalCommand cmd, Escrow escrow, String errorMessage,
            String slTransactionId) {
        return EscrowTransaction.builder()
                .escrow(escrow)
                .auction(escrow == null ? null : escrow.getAuction())
                .type(ledgerTypeFor(cmd))
                .status(EscrowTransactionStatus.FAILED)
                .amount(cmd.getAmount())
                .terminalId(cmd.getTerminalId())
                .slTransactionId(slTransactionId)
                .errorMessage(errorMessage)
                .build();
    }

    private static EscrowTransactionType ledgerTypeFor(TerminalCommand cmd) {
        if (cmd.getPurpose() == TerminalCommandPurpose.LISTING_FEE_REFUND) {
            return EscrowTransactionType.LISTING_FEE_REFUND;
        }
        return cmd.getAction() == TerminalCommandAction.PAYOUT
                ? EscrowTransactionType.AUCTION_ESCROW_PAYOUT
                : EscrowTransactionType.AUCTION_ESCROW_REFUND;
    }

    private void publishStallAfterCommit(TerminalCommand cmd, Escrow escrow,
            OffsetDateTime now) {
        final EscrowPayoutStalledEnvelope env =
                EscrowPayoutStalledEnvelope.of(cmd, escrow, now);
        registerAfterCommit(() -> broadcastPublisher.publishPayoutStalled(env));

        // Notify seller of the stalled payout — only for PAYOUT actions.
        // REFUND stalls go to admin review; no seller-facing notification.
        if (cmd.getAction() == TerminalCommandAction.PAYOUT) {
            notificationPublisher.escrowPayoutStalled(
                    escrow.getAuction().getSeller().getId(),
                    escrow.getAuction().getId(),
                    escrow.getId(),
                    escrow.getAuction().getTitle());
        }
    }

    private void registerAfterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            r.run();
                        }
                    });
        } else {
            // Defensive path for unit tests / callers that forgot to wrap
            // in a transaction — production always runs inside
            // @Transactional, so the afterCommit branch is the hot path.
            r.run();
        }
    }
}
