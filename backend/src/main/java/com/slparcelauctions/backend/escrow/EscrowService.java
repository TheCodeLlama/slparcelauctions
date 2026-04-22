package com.slparcelauctions.backend.escrow;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.fraud.FraudFlag;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.escrow.broadcast.EscrowBroadcastPublisher;
import com.slparcelauctions.backend.escrow.broadcast.EscrowCreatedEnvelope;
import com.slparcelauctions.backend.escrow.broadcast.EscrowDisputedEnvelope;
import com.slparcelauctions.backend.escrow.broadcast.EscrowFundedEnvelope;
import com.slparcelauctions.backend.escrow.dto.EscrowDisputeRequest;
import com.slparcelauctions.backend.escrow.dto.EscrowStatusResponse;
import com.slparcelauctions.backend.escrow.dto.EscrowTimelineEntry;
import com.slparcelauctions.backend.escrow.exception.EscrowAccessDeniedException;
import com.slparcelauctions.backend.escrow.exception.EscrowNotFoundException;
import com.slparcelauctions.backend.escrow.exception.IllegalEscrowTransitionException;
import com.slparcelauctions.backend.escrow.payment.EscrowCallbackResponseReason;
import com.slparcelauctions.backend.escrow.payment.dto.EscrowPaymentRequest;
import com.slparcelauctions.backend.escrow.payment.dto.SlCallbackResponse;
import com.slparcelauctions.backend.escrow.terminal.TerminalRepository;
import com.slparcelauctions.backend.escrow.terminal.TerminalService;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Central orchestrator for escrow lifecycle transitions (spec §4.2). Tasks
 * 2-9 progressively add methods — this task adds
 * {@link #createForEndedAuction} which stamps the ESCROW_PENDING row in the
 * same transaction that closes the auction and schedules the
 * {@code ESCROW_CREATED} broadcast on afterCommit.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EscrowService {

    static final Map<EscrowState, Set<EscrowState>> ALLOWED_TRANSITIONS = Map.of(
            EscrowState.ESCROW_PENDING,
                    Set.of(EscrowState.FUNDED, EscrowState.EXPIRED, EscrowState.DISPUTED),
            EscrowState.FUNDED,
                    Set.of(EscrowState.TRANSFER_PENDING, EscrowState.DISPUTED),
            EscrowState.TRANSFER_PENDING,
                    Set.of(EscrowState.COMPLETED, EscrowState.EXPIRED,
                           EscrowState.FROZEN, EscrowState.DISPUTED),
            EscrowState.COMPLETED, Set.of(),
            EscrowState.EXPIRED, Set.of(),
            EscrowState.DISPUTED, Set.of(),
            EscrowState.FROZEN, Set.of()
    );

    private static final long PAYMENT_DEADLINE_HOURS = 48;
    private static final long TRANSFER_DEADLINE_HOURS = 72;

    private final EscrowRepository escrowRepo;
    private final EscrowTransactionRepository ledgerRepo;
    private final EscrowCommissionCalculator commission;
    private final Clock clock;
    private final EscrowBroadcastPublisher broadcastPublisher;
    private final UserRepository userRepo;
    private final FraudFlagRepository fraudFlagRepo;
    private final TerminalService terminalService;
    private final TerminalRepository terminalRepo;

    public static boolean isAllowed(EscrowState from, EscrowState to) {
        return ALLOWED_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static void enforceTransitionAllowed(Long escrowId, EscrowState from, EscrowState to) {
        if (!isAllowed(from, to)) {
            throw new IllegalEscrowTransitionException(escrowId, from, to);
        }
    }

    /**
     * Creates the Escrow row for an auction that just closed with
     * {@code endOutcome ∈ {SOLD, BOUGHT_NOW}}. Called from
     * {@code AuctionEndTask.closeOne} and from the inline buy-it-now close
     * path in {@code BidService} — both run inside the caller's transaction,
     * hence the {@link Propagation#MANDATORY} contract: a stray call outside
     * a transaction is a bug we want to fail fast rather than silently
     * persist with no close-transaction to roll back with.
     *
     * <p>The {@code ESCROW_CREATED} envelope is captured inside the
     * transaction (while {@code saved} is managed) and registered on
     * {@code afterCommit} so subscribers never observe a row that gets
     * rolled back with the close. On rollback the synchronization's
     * {@code afterCommit} callback is never invoked — no phantom envelope
     * for a reverted creation.
     *
     * <p>Caller passes {@code endedAt} explicitly rather than reading the
     * clock here so the {@code paymentDeadline} is anchored to the exact
     * instant the auction row records as {@code endedAt}. Reading
     * {@code OffsetDateTime.now(clock)} here could drift microseconds from
     * the caller's {@code now}, breaking cross-channel event ordering and
     * subtly shifting the 48h deadline. Spec §4.3.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Escrow createForEndedAuction(Auction auction, OffsetDateTime endedAt) {
        long finalBid = auction.getFinalBidAmount();
        Escrow escrow = Escrow.builder()
                .auction(auction)
                .state(EscrowState.ESCROW_PENDING)
                .finalBidAmount(finalBid)
                .commissionAmt(commission.commission(finalBid))
                .payoutAmt(commission.payout(finalBid))
                .paymentDeadline(endedAt.plusHours(PAYMENT_DEADLINE_HOURS))
                .consecutiveWorldApiFailures(0)
                .build();
        Escrow saved = escrowRepo.save(escrow);

        final EscrowCreatedEnvelope envelope = EscrowCreatedEnvelope.of(saved, endedAt);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        broadcastPublisher.publishCreated(envelope);
                    }
                });

        log.info("Escrow {} created for auction {} (final L${}, commission L${}, payout L${})",
                saved.getId(), auction.getId(), finalBid, saved.getCommissionAmt(), saved.getPayoutAmt());
        return saved;
    }

    /**
     * Read the escrow row for the given auction, visible only to seller or
     * winner. The response includes a timeline built from state-column
     * timestamps plus any {@link EscrowTransaction} ledger rows. Spec §4 / §8.
     */
    @Transactional(readOnly = true)
    public EscrowStatusResponse getStatus(Long auctionId, Long currentUserId) {
        Escrow escrow = escrowRepo.findByAuctionId(auctionId)
                .orElseThrow(() -> new EscrowNotFoundException(auctionId));
        assertSellerOrWinner(escrow, currentUserId);
        return toStatusResponse(escrow);
    }

    /**
     * Transitions the escrow to {@link EscrowState#DISPUTED}, stamps
     * {@code disputedAt} / {@code disputeReasonCategory} / {@code disputeDescription},
     * and broadcasts an {@link EscrowDisputedEnvelope} after commit. Only
     * the seller or the winner may file a dispute. Spec §4.4 / §8.
     *
     * <p>The initial {@code findByAuctionId} is used for the access check;
     * the mutation path re-fetches the row under a pessimistic write lock
     * via {@link EscrowRepository#findByIdForUpdate} so the transition
     * serialises against the ownership-monitor, timeout-job, and payment
     * callback paths that also take that lock.
     */
    @Transactional
    public EscrowStatusResponse fileDispute(
            Long auctionId, EscrowDisputeRequest req, Long currentUserId) {
        Escrow escrow = escrowRepo.findByAuctionId(auctionId)
                .orElseThrow(() -> new EscrowNotFoundException(auctionId));
        assertSellerOrWinner(escrow, currentUserId);
        // Re-lock inside the transaction for the mutation path.
        escrow = escrowRepo.findByIdForUpdate(escrow.getId()).orElseThrow();
        enforceTransitionAllowed(escrow.getId(), escrow.getState(), EscrowState.DISPUTED);

        OffsetDateTime now = OffsetDateTime.now(clock);
        escrow.setState(EscrowState.DISPUTED);
        escrow.setDisputedAt(now);
        escrow.setDisputeReasonCategory(req.reasonCategory().name());
        escrow.setDisputeDescription(req.description());
        escrow = escrowRepo.save(escrow);

        queueRefundIfFunded(escrow);

        final EscrowDisputedEnvelope envelope = EscrowDisputedEnvelope.of(escrow, now);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        broadcastPublisher.publishDisputed(envelope);
                    }
                });
        log.info("Escrow {} DISPUTED by user {}: category={}, description_len={}",
                escrow.getId(), currentUserId, req.reasonCategory(), req.description().length());

        return toStatusResponse(escrow);
    }

    /**
     * Enforces that {@code currentUserId} is either the auction's seller or
     * its recorded winner (via {@code auction.winnerUserId}). Package-private
     * for reuse by future service methods that also need this gate.
     */
    void assertSellerOrWinner(Escrow escrow, Long currentUserId) {
        Long sellerId = escrow.getAuction().getSeller().getId();
        Long winnerUserId = escrow.getAuction().getWinnerUserId();
        boolean isSeller = sellerId != null && sellerId.equals(currentUserId);
        boolean isWinner = winnerUserId != null && winnerUserId.equals(currentUserId);
        if (!isSeller && !isWinner) {
            throw new EscrowAccessDeniedException();
        }
    }

    /**
     * Placeholder for Task 7 integration. When an escrow is {@code DISPUTED},
     * {@code FROZEN}, or {@code EXPIRED} from {@code TRANSFER_PENDING}, a refund
     * L$ flow must be queued on the terminal command pipeline. Task 7 introduces
     * {@code TerminalCommandService} and replaces this body with
     * {@code if (escrow.getFundedAt() != null) terminalCommandService.queueRefund(escrow);}.
     * In Task 3, the stub is a no-op so the dispute flow can ship without the
     * command-queue scaffolding.
     */
    void queueRefundIfFunded(Escrow escrow) {
        // Task 7 integration — see javadoc.
    }

    private EscrowStatusResponse toStatusResponse(Escrow escrow) {
        List<EscrowTimelineEntry> timeline = buildTimeline(escrow);
        return new EscrowStatusResponse(
                escrow.getId(), escrow.getAuction().getId(), escrow.getState(),
                escrow.getFinalBidAmount(), escrow.getCommissionAmt(), escrow.getPayoutAmt(),
                escrow.getPaymentDeadline(), escrow.getTransferDeadline(),
                escrow.getFundedAt(), escrow.getTransferConfirmedAt(), escrow.getCompletedAt(),
                escrow.getDisputedAt(), escrow.getFrozenAt(), escrow.getExpiredAt(),
                escrow.getDisputeReasonCategory(), escrow.getDisputeDescription(),
                escrow.getFreezeReason(), timeline);
    }

    private List<EscrowTimelineEntry> buildTimeline(Escrow e) {
        List<EscrowTimelineEntry> out = new ArrayList<>();
        addIfNotNull(out, "STATE_TRANSITION", "Escrow created",
                e.getCreatedAt(), null, "state=ESCROW_PENDING");
        addIfNotNull(out, "STATE_TRANSITION", "Payment received",
                e.getFundedAt(), e.getFinalBidAmount(), "state=TRANSFER_PENDING");
        addIfNotNull(out, "STATE_TRANSITION", "Transfer confirmed",
                e.getTransferConfirmedAt(), null, null);
        addIfNotNull(out, "STATE_TRANSITION", "Payout complete",
                e.getCompletedAt(), e.getPayoutAmt(), "state=COMPLETED");
        addIfNotNull(out, "STATE_TRANSITION", "Dispute filed",
                e.getDisputedAt(), null, e.getDisputeReasonCategory());
        addIfNotNull(out, "STATE_TRANSITION", "Escrow frozen",
                e.getFrozenAt(), null, e.getFreezeReason());
        addIfNotNull(out, "STATE_TRANSITION", "Escrow expired",
                e.getExpiredAt(), null, null);

        ledgerRepo.findByEscrowIdOrderByCreatedAtAsc(e.getId()).forEach(tx -> {
            String kind = "LEDGER_" + tx.getType().name();
            String label = tx.getType().name().replace('_', ' ');
            String details = tx.getStatus().name()
                    + (tx.getSlTransactionId() != null ? " / " + tx.getSlTransactionId() : "");
            out.add(new EscrowTimelineEntry(
                    kind, label, tx.getCreatedAt(), tx.getAmount(), details));
        });
        out.sort(Comparator.comparing(EscrowTimelineEntry::at));
        return out;
    }

    private void addIfNotNull(
            List<EscrowTimelineEntry> out,
            String kind, String label, OffsetDateTime at, Long amount, String details) {
        if (at != null) {
            out.add(new EscrowTimelineEntry(kind, label, at, amount, details));
        }
    }

    /**
     * Processes a terminal-posted payment callback. The flow runs the full
     * validation pipeline — shared secret (defense in depth with the
     * controller layer), idempotency on {@code slTransactionKey}, terminal
     * registration, escrow existence, state gate, deadline check, payer
     * match, amount match — before transitioning atomically through
     * {@code ESCROW_PENDING → FUNDED → TRANSFER_PENDING} inside one
     * transaction. The transient {@code FUNDED} is never externally
     * observable by design (spec §4); the envelope broadcast afterCommit
     * carries the post-atomic {@code TRANSFER_PENDING} state.
     *
     * <p>On any validation failure we write a {@code FAILED} ledger row so a
     * replay of the same {@code slTransactionKey} can reconstruct the prior
     * REFUND response without reprocessing and so the dispute timeline shows
     * the rejected attempt. Wrong-payer also creates a {@link FraudFlag}
     * with {@link FraudFlagReason#ESCROW_WRONG_PAYER} for the admin review
     * queue.
     *
     * <p>Returns an {@link SlCallbackResponse} in LSL-friendly shape; the
     * HTTP status is always 200 (the domain decision is in the body). Only
     * header and secret checks — performed by the controller + service
     * entry points — can produce non-2xx.
     */
    @Transactional
    public SlCallbackResponse acceptPayment(EscrowPaymentRequest req) {
        // 1. Shared secret — defense in depth; the controller has also
        // enforced this by the time we're here.
        terminalService.assertSharedSecret(req.sharedSecret());

        // 2. Idempotency check on slTransactionKey. A COMPLETED row means the
        // previous attempt succeeded; a FAILED row means the previous attempt
        // was rejected. Replays of either short-circuit with the same answer.
        Optional<EscrowTransaction> existing = ledgerRepo
                .findFirstBySlTransactionIdAndType(
                        req.slTransactionKey(), EscrowTransactionType.AUCTION_ESCROW_PAYMENT);
        if (existing.isPresent()) {
            EscrowTransaction tx = existing.get();
            if (tx.getStatus() == EscrowTransactionStatus.COMPLETED) {
                return SlCallbackResponse.ok();
            }
            return parseRefundFromLedger(tx);
        }

        // 3. Terminal must be registered (known, not necessarily live). Keeps
        // arbitrary attackers with a leaked secret from minting REFUND rows.
        if (!terminalRepo.existsById(req.terminalId())) {
            return SlCallbackResponse.error(
                    EscrowCallbackResponseReason.UNKNOWN_TERMINAL,
                    "Terminal not registered: " + req.terminalId());
        }

        // 4. Load escrow by auctionId. Unknown auction → ERROR (terminal does
        // NOT refund — there's no matching record for this payment).
        Escrow escrow = escrowRepo.findByAuctionId(req.auctionId()).orElse(null);
        if (escrow == null) {
            return SlCallbackResponse.error(
                    EscrowCallbackResponseReason.UNKNOWN_AUCTION,
                    "No escrow for auction " + req.auctionId());
        }

        // 5. Pessimistic lock so the payment path serialises against the
        // dispute / timeout / monitor paths that take the same lock.
        escrow = escrowRepo.findByIdForUpdate(escrow.getId()).orElseThrow();

        // 6. State check. FUNDED / TRANSFER_PENDING / COMPLETED → ALREADY_FUNDED
        // REFUND. EXPIRED / DISPUTED / FROZEN → ESCROW_EXPIRED REFUND.
        EscrowState state = escrow.getState();
        if (state == EscrowState.TRANSFER_PENDING
                || state == EscrowState.COMPLETED
                || state == EscrowState.FUNDED) {
            writeFailedLedger(escrow, req, EscrowCallbackResponseReason.ALREADY_FUNDED);
            return SlCallbackResponse.refund(
                    EscrowCallbackResponseReason.ALREADY_FUNDED,
                    "Escrow already funded for this auction");
        }
        if (state == EscrowState.EXPIRED
                || state == EscrowState.DISPUTED
                || state == EscrowState.FROZEN) {
            writeFailedLedger(escrow, req, EscrowCallbackResponseReason.ESCROW_EXPIRED);
            return SlCallbackResponse.refund(
                    EscrowCallbackResponseReason.ESCROW_EXPIRED,
                    "Escrow is no longer accepting payment");
        }
        // else: ESCROW_PENDING — fall through to the remaining gates.

        // 7. Deadline check.
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (now.isAfter(escrow.getPaymentDeadline())) {
            writeFailedLedger(escrow, req, EscrowCallbackResponseReason.ESCROW_EXPIRED);
            return SlCallbackResponse.refund(
                    EscrowCallbackResponseReason.ESCROW_EXPIRED,
                    "Payment deadline exceeded");
        }

        // 8. Payer match. Wrong payer → fraud flag + REFUND. We compare the
        // winner's sl_avatar_uuid case-insensitively to accommodate LSL's
        // habit of upper-casing key dumps.
        User winner = userRepo.findById(escrow.getAuction().getWinnerUserId()).orElseThrow();
        if (winner.getSlAvatarUuid() == null
                || !winner.getSlAvatarUuid().toString().equalsIgnoreCase(req.payerUuid())) {
            String expected = winner.getSlAvatarUuid() == null
                    ? "<null>" : winner.getSlAvatarUuid().toString();
            FraudFlag flag = FraudFlag.builder()
                    .auction(escrow.getAuction())
                    .parcel(escrow.getAuction().getParcel())
                    .reason(FraudFlagReason.ESCROW_WRONG_PAYER)
                    .detectedAt(now)
                    .evidenceJson(Map.of(
                            "expectedPayerUuid", expected,
                            "actualPayerUuid", req.payerUuid(),
                            "auctionId", req.auctionId(),
                            "amount", req.amount(),
                            "slTransactionKey", req.slTransactionKey()))
                    .resolved(false)
                    .build();
            fraudFlagRepo.save(flag);
            writeFailedLedger(escrow, req, EscrowCallbackResponseReason.WRONG_PAYER);
            log.warn("Escrow wrong-payer fraud flag on auction {}: expected={}, actual={}",
                    req.auctionId(), expected, req.payerUuid());
            return SlCallbackResponse.refund(
                    EscrowCallbackResponseReason.WRONG_PAYER,
                    "Payer does not match auction winner");
        }

        // 9. Amount match.
        if (!req.amount().equals(escrow.getFinalBidAmount())) {
            writeFailedLedger(escrow, req, EscrowCallbackResponseReason.WRONG_AMOUNT);
            return SlCallbackResponse.refund(
                    EscrowCallbackResponseReason.WRONG_AMOUNT,
                    "Expected L$" + escrow.getFinalBidAmount() + ", got L$" + req.amount());
        }

        // 10. Atomic ESCROW_PENDING → FUNDED → TRANSFER_PENDING within this
        // transaction. We validate both transitions against the state
        // machine so a future edit to ALLOWED_TRANSITIONS can't silently
        // break the invariant. External observers only ever see the
        // TRANSFER_PENDING landing state.
        enforceTransitionAllowed(escrow.getId(), escrow.getState(), EscrowState.FUNDED);
        escrow.setState(EscrowState.FUNDED);
        escrow.setFundedAt(now);
        enforceTransitionAllowed(escrow.getId(), escrow.getState(), EscrowState.TRANSFER_PENDING);
        escrow.setState(EscrowState.TRANSFER_PENDING);
        escrow.setTransferDeadline(now.plusHours(TRANSFER_DEADLINE_HOURS));
        escrow = escrowRepo.save(escrow);

        // 11. Write the COMPLETED ledger row with the L$ amount and payer.
        ledgerRepo.save(EscrowTransaction.builder()
                .escrow(escrow)
                .auction(escrow.getAuction())
                .type(EscrowTransactionType.AUCTION_ESCROW_PAYMENT)
                .status(EscrowTransactionStatus.COMPLETED)
                .amount(req.amount())
                .payer(winner)
                .slTransactionId(req.slTransactionKey())
                .terminalId(req.terminalId())
                .completedAt(now)
                .build());

        // 12. Broadcast afterCommit so subscribers never observe a row that
        // gets rolled back on a late DB failure.
        final Escrow finalEscrow = escrow;
        final EscrowFundedEnvelope envelope = EscrowFundedEnvelope.of(finalEscrow, now);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        broadcastPublisher.publishFunded(envelope);
                    }
                });

        log.info("Escrow {} FUNDED (auction {}, amount L${}, txn {})",
                escrow.getId(), req.auctionId(), req.amount(), req.slTransactionKey());

        return SlCallbackResponse.ok();
    }

    /**
     * Writes a FAILED {@link EscrowTransaction} ledger row for a rejected
     * payment. The reason's enum name is stored in {@code errorMessage} so
     * {@link #parseRefundFromLedger(EscrowTransaction)} can rebuild the
     * original REFUND response on a replay of the same
     * {@code slTransactionKey} without re-running domain checks.
     */
    private void writeFailedLedger(
            Escrow escrow, EscrowPaymentRequest req, EscrowCallbackResponseReason reason) {
        ledgerRepo.save(EscrowTransaction.builder()
                .escrow(escrow)
                .auction(escrow.getAuction())
                .type(EscrowTransactionType.AUCTION_ESCROW_PAYMENT)
                .status(EscrowTransactionStatus.FAILED)
                .amount(req.amount())
                .slTransactionId(req.slTransactionKey())
                .terminalId(req.terminalId())
                .errorMessage(reason.name())
                .build());
    }

    /**
     * Reconstructs the REFUND response from a persisted FAILED ledger row so a
     * replay of the same {@code slTransactionKey} returns the same answer
     * without re-running the decision logic. Unknown / missing error codes
     * fall back to {@link EscrowCallbackResponseReason#ESCROW_EXPIRED} — a
     * safe conservative default that tells the terminal to refund.
     */
    private SlCallbackResponse parseRefundFromLedger(EscrowTransaction tx) {
        String reason = tx.getErrorMessage();
        if (reason == null) {
            return SlCallbackResponse.refund(
                    EscrowCallbackResponseReason.ESCROW_EXPIRED,
                    "Replay of previously-failed payment");
        }
        try {
            EscrowCallbackResponseReason r = EscrowCallbackResponseReason.valueOf(reason);
            return SlCallbackResponse.refund(r,
                    "Replay of previous " + reason + " response");
        } catch (IllegalArgumentException e) {
            return SlCallbackResponse.refund(
                    EscrowCallbackResponseReason.ESCROW_EXPIRED,
                    "Replay of previously-failed payment");
        }
    }
}
