package com.slparcelauctions.backend.escrow.command;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public TerminalCommand queuePayout(Escrow escrow) {
        String recipientUuid = escrow.getAuction().getSeller().getSlAvatarUuid().toString();
        return queue(escrow.getId(), null,
                TerminalCommandAction.PAYOUT, TerminalCommandPurpose.AUCTION_ESCROW,
                recipientUuid, escrow.getPayoutAmt(),
                idempotencyKey("ESC", escrow.getId(), TerminalCommandAction.PAYOUT, 1));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public TerminalCommand queueRefund(Escrow escrow) {
        User winner = userRepo.findById(escrow.getAuction().getWinnerUserId()).orElseThrow();
        String recipientUuid = winner.getSlAvatarUuid().toString();
        return queue(escrow.getId(), null,
                TerminalCommandAction.REFUND, TerminalCommandPurpose.AUCTION_ESCROW,
                recipientUuid, escrow.getFinalBidAmount(),
                idempotencyKey("ESC", escrow.getId(), TerminalCommandAction.REFUND, 1));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public TerminalCommand queueListingFeeRefund(ListingFeeRefund refund) {
        String recipientUuid = refund.getAuction().getSeller().getSlAvatarUuid().toString();
        return queue(null, refund.getId(),
                TerminalCommandAction.REFUND, TerminalCommandPurpose.LISTING_FEE_REFUND,
                recipientUuid, refund.getAmount(),
                idempotencyKey("LFR", refund.getId(), TerminalCommandAction.REFUND, 1));
    }

    private String idempotencyKey(String prefix, Long id,
            TerminalCommandAction action, int seq) {
        return prefix + "-" + id + "-" + action.name() + "-" + seq;
    }

    private TerminalCommand queue(Long escrowId, Long refundId,
            TerminalCommandAction action, TerminalCommandPurpose purpose,
            String recipientUuid, long amount, String idempotencyKey) {
        TerminalCommand saved = cmdRepo.save(TerminalCommand.builder()
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
                .build());
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
            // Write a FAILED ledger row so the dispute timeline + forensic
            // replay capture the failed attempt even if the next retry
            // eventually succeeds. The row is keyed to the originating
            // escrow / auction so the UI timeline surfaces it.
            Escrow escrow = cmd.getEscrowId() == null
                    ? null
                    : escrowRepo.findById(cmd.getEscrowId()).orElse(null);
            ledgerRepo.save(EscrowTransaction.builder()
                    .escrow(escrow)
                    .auction(escrow == null ? null : escrow.getAuction())
                    .type(ledgerTypeFor(cmd))
                    .status(EscrowTransactionStatus.FAILED)
                    .amount(cmd.getAmount())
                    .terminalId(cmd.getTerminalId())
                    .slTransactionId(req.slTransactionKey())
                    .errorMessage(req.errorMessage())
                    .build());

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

    private EscrowTransactionType ledgerTypeFor(TerminalCommand cmd) {
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
