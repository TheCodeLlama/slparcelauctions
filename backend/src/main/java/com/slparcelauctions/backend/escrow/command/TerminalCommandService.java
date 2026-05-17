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
    private final AuctionStatusFlipper statusFlipper;

    /**
     * Queues an escrow payout to the seller's SL terminal. Sub-project G §8.1
     * short-circuit: when {@code escrow.getPayoutAmt() == 0L} (case-3
     * SL-group-owned auctions; the agent commission and group slice both flow
     * through {@link com.slparcelauctions.backend.auction.agentfee.AgentCommissionDistributor}
     * inside the success path), the terminal round-trip is skipped and the
     * post-payout work runs inline via {@link #runZeroPayoutSuccessInline}.
     * Returns {@link Optional#empty()} in that case so the caller knows no
     * {@link TerminalCommand} was enqueued.
     *
     * @return the enqueued command, or {@link Optional#empty()} when the
     *         payout amount is zero and the success path ran inline.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<TerminalCommand> queuePayout(Escrow escrow) {
        if (escrow.getPayoutAmt() != null && escrow.getPayoutAmt() == 0L) {
            if (escrow.getState() == EscrowState.COMPLETED) {
                // Idempotent replay: a previous call already ran the inline
                // success path. No-op so we don't double-write the ledger row,
                // double-notify the seller, or double-credit commissions.
                log.info("queuePayout: escrow {} already COMPLETED, no-op", escrow.getId());
                return Optional.empty();
            }
            log.info("queuePayout: escrow {} payoutAmt=0 (case-3), running success path inline",
                    escrow.getId());
            runZeroPayoutSuccessInline(escrow, OffsetDateTime.now(clock));
            return Optional.empty();
        }
        String recipientUuid = escrow.getAuction().getSeller().getSlAvatarUuid().toString();
        TerminalCommand cmd = queue(escrow.getId(), null,
                TerminalCommandAction.PAYOUT, TerminalCommandPurpose.AUCTION_ESCROW,
                recipientUuid, escrow.getPayoutAmt(),
                idempotencyKey("ESC", escrow.getId(), TerminalCommandAction.PAYOUT, 1));
        return Optional.of(cmd);
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
                    && cmd.getPurpose() != TerminalCommandPurpose.GROUP_WALLET_WITHDRAWAL) {
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
        } else {
            throw new IllegalStateException(
                    "Unhandled terminal command callback: purpose=" + cmd.getPurpose()
                            + ", action=" + cmd.getAction() + ", commandId=" + cmd.getId());
        }
    }

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

        // Notify seller that payout was received (ESCROW_PAYOUT → seller only).
        notificationPublisher.escrowPayout(
                finalEscrow.getAuction().getSeller().getId(),
                finalEscrow.getAuction().getId(),
                finalEscrow.getId(),
                finalEscrow.getAuction().getTitle(),
                cmd.getAmount());

        // Realty-group payout splitting.
        //
        //   case 3 (E -- SL-group-owned): realty_group_sl_group_id IS NOT NULL.
        //       The escrow's payoutAmt is 0 (set by EscrowService.createForEndedAuction);
        //       no L$ leaves SLPA via the terminal. AgentCommissionDistributor credits
        //       the full earnings (finalBid - commission) to the listing agent's wallet
        //       (agent_slice) and the group wallet (group_slice) using
        //       agent_commission_rate. Spec §8.5, §9.6.
        //
        //   individual: realty_group_sl_group_id IS NULL -- nothing to split.
        //
        //   (The pre-G case-1 path -- realty_group_id set but realty_group_sl_group_id
        //   null -- was removed when sub-project G deleted the case-1 distributor.)
        if (finalEscrow.getAuction().getRealtyGroupSlGroupId() != null) {
            agentCommissionDistributor.distribute(
                finalEscrow.getAuction(),
                finalEscrow.getFinalBidAmount(),
                finalEscrow.getCommissionAmt());
        }
    }

    /**
     * Sub-project G §8.1 -- post-payout success path for the case-3 zero-payout
     * branch. Mirrors {@link #handleEscrowPayoutSuccess}'s body but is driven
     * directly from {@link #queuePayout} (no terminal callback because no
     * terminal round-trip happened). Writes the {@code AUCTION_ESCROW_PAYOUT}
     * ledger row with amount=0, transitions the escrow to COMPLETED, bumps the
     * seller's completedSales counter, broadcasts the
     * {@link EscrowCompletedEnvelope} after commit, notifies the seller, and
     * invokes
     * {@link com.slparcelauctions.backend.auction.agentfee.AgentCommissionDistributor#distribute}
     * so the agent slice and group slice land in their respective wallets.
     *
     * <p>Idempotency is enforced by the caller -- {@link #queuePayout} short-
     * circuits when the escrow is already COMPLETED.
     */
    private void runZeroPayoutSuccessInline(Escrow escrow, OffsetDateTime now) {
        EscrowService.enforceTransitionAllowed(
                escrow.getId(), escrow.getState(), EscrowState.COMPLETED);
        escrow.setState(EscrowState.COMPLETED);
        escrow.setCompletedAt(now);
        escrow = escrowRepo.save(escrow);
        // Lockstep auction-status flip: case-3 zero-payout still transitions
        // the escrow to COMPLETED inline (no terminal round-trip), so the
        // auction lands at COMPLETED here too.
        statusFlipper.flip(escrow, AuctionStatus.COMPLETED);

        User seller = escrow.getAuction().getSeller();
        int prior = seller.getCompletedSales() == null ? 0 : seller.getCompletedSales();
        seller.setCompletedSales(prior + 1);
        userRepo.save(seller);

        // Same shape as the terminal-callback path; amount = 0, no slTxn, no
        // terminalId. Reconciliation by type (AUCTION_ESCROW_PAYOUT) still works.
        ledgerRepo.save(EscrowTransaction.builder()
                .escrow(escrow)
                .auction(escrow.getAuction())
                .type(EscrowTransactionType.AUCTION_ESCROW_PAYOUT)
                .status(EscrowTransactionStatus.COMPLETED)
                .amount(0L)
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

        // Seller payout notification; Task 24 tweaks the body copy so case-3
        // doesn't say "L$0 payout received". Amount is still 0 here -- the
        // builder decides the body string based on realtyGroupId.
        notificationPublisher.escrowPayout(
                finalEscrow.getAuction().getSeller().getId(),
                finalEscrow.getAuction().getId(),
                finalEscrow.getId(),
                finalEscrow.getAuction().getTitle(),
                0L);

        // Case-3 distributor: credits agent_slice to the listing agent's wallet,
        // group_slice to the group wallet. Both flow through here because
        // payoutAmt = 0 meant no L$ left SLPA via the terminal. By construction
        // (Sub-project E spec §9.6 post-G), case-3 is the only branch that
        // reaches this method.
        if (finalEscrow.getAuction().getRealtyGroupSlGroupId() != null) {
            agentCommissionDistributor.distribute(
                finalEscrow.getAuction(),
                finalEscrow.getFinalBidAmount(),
                finalEscrow.getCommissionAmt());
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
