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
import org.springframework.web.multipart.MultipartFile;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.auction.AuctionStatusFlipper;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.fraud.FraudFlag;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.escrow.broadcast.EscrowBroadcastPublisher;
import com.slparcelauctions.backend.escrow.dispute.DisputeEvidenceUploadService;
import com.slparcelauctions.backend.escrow.dispute.EvidenceImage;
import com.slparcelauctions.backend.escrow.broadcast.EscrowCreatedEnvelope;
import com.slparcelauctions.backend.escrow.broadcast.EscrowDisputedEnvelope;
import com.slparcelauctions.backend.escrow.broadcast.EscrowExpiredEnvelope;
import com.slparcelauctions.backend.escrow.broadcast.EscrowFrozenEnvelope;
import com.slparcelauctions.backend.escrow.broadcast.EscrowFundedEnvelope;
import com.slparcelauctions.backend.escrow.broadcast.EscrowTransferConfirmedEnvelope;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.escrow.command.TerminalCommandService;
import com.slparcelauctions.backend.escrow.dispute.exception.EscrowNotDisputedException;
import com.slparcelauctions.backend.escrow.dispute.exception.EvidenceAlreadySubmittedException;
import com.slparcelauctions.backend.escrow.dispute.exception.NotSellerOfEscrowException;
import com.slparcelauctions.backend.escrow.dto.EscrowDisputeRequest;
import com.slparcelauctions.backend.escrow.dto.EscrowStatusResponse;
import com.slparcelauctions.backend.escrow.dto.EscrowTimelineEntry;
import com.slparcelauctions.backend.escrow.dto.SellerEvidenceRequest;
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
import com.slparcelauctions.backend.wallet.WalletService;
import com.slparcelauctions.backend.wallet.exception.BidReservationAmountMismatchException;


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

    private static final long TRANSFER_DEADLINE_HOURS = 72;

    private final EscrowRepository escrowRepo;
    private final AuctionStatusFlipper statusFlipper;
    private final EscrowTransactionRepository ledgerRepo;
    private final EscrowCommissionCalculator commission;
    private final Clock clock;
    private final EscrowBroadcastPublisher broadcastPublisher;
    private final UserRepository userRepo;
    private final FraudFlagRepository fraudFlagRepo;
    private final TerminalService terminalService;
    private final TerminalRepository terminalRepo;
    private final TerminalCommandService terminalCommandService;
    private final NotificationPublisher notificationPublisher;
    private final DisputeEvidenceUploadService evidenceUploadService;
    private final WalletService walletService;

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
        long payoutAmt = computePayoutAmt(auction, finalBid);
        Escrow escrow = Escrow.builder()
                .auction(auction)
                .state(EscrowState.ESCROW_PENDING)
                .finalBidAmount(finalBid)
                .commissionAmt(commission.commission(finalBid))
                .payoutAmt(payoutAmt)
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

        // Wallet-only escrow funding (spec 2026-05-16): auto-fund the
        // escrow immediately by consuming the winner's bid reservation and
        // debiting their wallet balance. Transitions
        // ESCROW_PENDING → FUNDED → TRANSFER_PENDING in this same
        // transaction so external observers only see TRANSFER_PENDING.
        // ESCROW_PENDING is the transactional intermediate.
        if (auction.getWinnerUserId() == null) {
            return saved;
        }
        User winner = userRepo.findByIdForUpdate(auction.getWinnerUserId()).orElseThrow();
        try {
            walletService.autoFundEscrow(auction.getId(), winner, finalBid, saved.getId());
        } catch (BidReservationAmountMismatchException e) {
            log.error("BID-RESERVATION-AMOUNT-MISMATCH for auction {}: reservation=L${} != finalBid=L${}; freezing escrow",
                    auction.getId(), e.getReservationAmount(), e.getFinalBidAmount());
            saved.setState(EscrowState.FROZEN);
            saved.setFrozenAt(endedAt);
            return escrowRepo.save(saved);
        } catch (IllegalStateException e) {
            log.error("Auto-fund failed for auction {}: {}", auction.getId(), e.getMessage());
            saved.setState(EscrowState.FROZEN);
            saved.setFrozenAt(endedAt);
            return escrowRepo.save(saved);
        }
        // Transition ESCROW_PENDING → FUNDED → TRANSFER_PENDING
        saved.setState(EscrowState.FUNDED);
        saved.setFundedAt(endedAt);
        saved.setState(EscrowState.TRANSFER_PENDING);
        saved.setTransferDeadline(endedAt.plusHours(TRANSFER_DEADLINE_HOURS));
        saved = escrowRepo.save(saved);
        statusFlipper.flip(saved, AuctionStatus.TRANSFER_PENDING);

        // Append escrow_transactions ledger row
        ledgerRepo.save(EscrowTransaction.builder()
                .escrow(saved)
                .auction(auction)
                .type(EscrowTransactionType.AUCTION_ESCROW_PAYMENT)
                .status(EscrowTransactionStatus.COMPLETED)
                .amount(finalBid)
                .payer(winner)
                .completedAt(endedAt)
                .build());

        // Notify seller of funded state — except on the buy-now close path,
        // where BidService.acceptBid has already published a dedicated
        // AUCTION_ENDED_BOUGHT_NOW notification with the same L$ amount.
        // Both reaching the seller as separate rows is duplicate noise (one
        // event, one notification). Natural auction-end paths still fire
        // escrowFunded since they don't have a BOUGHT_NOW counterpart.
        if (auction.getEndOutcome() != AuctionEndOutcome.BOUGHT_NOW) {
            notificationPublisher.escrowFunded(
                    auction.getSeller().getId(),
                    auction.getId(),
                    saved.getId(),
                    auction.getTitle(),
                    saved.getTransferDeadline());
        }

        final Escrow finalEscrow = saved;
        final EscrowFundedEnvelope fundedEnvelope = EscrowFundedEnvelope.of(finalEscrow, endedAt);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        broadcastPublisher.publishFunded(fundedEnvelope);
                    }
                });

        log.info("Escrow {} auto-funded from wallet (auction {}, amount L${})",
                saved.getId(), auction.getId(), finalBid);
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
            Long auctionId, EscrowDisputeRequest req, Long currentUserId,
            List<MultipartFile> evidenceFiles) {
        Escrow escrow = escrowRepo.findByAuctionId(auctionId)
                .orElseThrow(() -> new EscrowNotFoundException(auctionId));
        assertSellerOrWinner(escrow, currentUserId);
        // Re-lock inside the transaction for the mutation path.
        escrow = escrowRepo.findByIdForUpdate(escrow.getId()).orElseThrow();
        enforceTransitionAllowed(escrow.getId(), escrow.getState(), EscrowState.DISPUTED);

        // PAYMENT_NOT_CREDITED requires slTransactionKey.
        if (req.reasonCategory() == EscrowDisputeReasonCategory.PAYMENT_NOT_CREDITED
                && (req.slTransactionKey() == null || req.slTransactionKey().isBlank())) {
            throw new IllegalArgumentException(
                    "slTransactionKey is required for PAYMENT_NOT_CREDITED disputes");
        }

        // Defensive guard — evidence must not have been written already.
        if (!escrow.getWinnerEvidenceImages().isEmpty() || escrow.getSlTransactionKey() != null) {
            throw new IllegalStateException(
                    "Winner evidence already written for escrow " + escrow.getId());
        }

        // Upload evidence before any state mutation (storage failures abort cleanly).
        List<EvidenceImage> uploaded = evidenceUploadService.uploadAll(
                escrow.getId(), "winner", evidenceFiles);

        OffsetDateTime now = OffsetDateTime.now(clock);
        escrow.setState(EscrowState.DISPUTED);
        escrow.setDisputedAt(now);
        escrow.setDisputeReasonCategory(req.reasonCategory().name());
        escrow.setDisputeDescription(req.description());
        escrow.setWinnerEvidenceImages(uploaded);
        escrow.setSlTransactionKey(req.slTransactionKey());
        escrow = escrowRepo.save(escrow);
        statusFlipper.flip(escrow, AuctionStatus.DISPUTED);


        queueRefundIfFunded(escrow);

        final EscrowDisputedEnvelope envelope = EscrowDisputedEnvelope.of(escrow, now);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        broadcastPublisher.publishDisputed(envelope);
                    }
                });

        // Notify both parties of the dispute (winner side preserves existing record).
        String disputedParcelName = escrow.getAuction().getTitle();
        long disputedAuctionId = escrow.getAuction().getId();
        long disputedEscrowId = escrow.getId();
        String disputeReasonCategory = req.reasonCategory().name();
        notificationPublisher.escrowDisputed(
                escrow.getAuction().getWinnerUserId(),
                disputedAuctionId, disputedEscrowId, disputedParcelName, disputeReasonCategory);
        notificationPublisher.escrowDisputed(
                escrow.getAuction().getSeller().getId(),
                disputedAuctionId, disputedEscrowId, disputedParcelName, disputeReasonCategory);

        // Seller-specific dispute notification (DISPUTE_FILED_AGAINST_SELLER).
        Auction disputedAuction = escrow.getAuction();
        notificationPublisher.disputeFiledAgainstSeller(
                disputedAuction.getSeller().getId(),
                disputedAuctionId,
                disputedEscrowId,
                disputedParcelName,
                escrow.getFinalBidAmount(),
                disputeReasonCategory);

        log.info("Escrow {} DISPUTED by user {}: category={}, description_len={}, evidence_count={}",
                escrow.getId(), currentUserId, req.reasonCategory(), req.description().length(),
                uploaded.size());

        return toStatusResponse(escrow);
    }

    /**
     * Submits seller-side evidence for a disputed escrow. Submit-once
     * invariant: a second call throws {@link EvidenceAlreadySubmittedException}.
     * Only the auction's seller may call this; any other caller gets
     * {@link NotSellerOfEscrowException}. The escrow must be in
     * {@link EscrowState#DISPUTED}; otherwise {@link EscrowNotDisputedException}
     * is thrown. Spec §4.4.
     */
    @Transactional
    public EscrowStatusResponse submitSellerEvidence(
            Long escrowId,
            Long sellerUserId,
            SellerEvidenceRequest body,
            List<MultipartFile> evidenceFiles) {
        Escrow escrow = escrowRepo.findById(escrowId)
                .orElseThrow(() -> new EscrowNotFoundException(escrowId));

        Long actualSellerId = escrow.getAuction().getSeller().getId();
        if (!actualSellerId.equals(sellerUserId)) {
            throw new NotSellerOfEscrowException(escrowId, sellerUserId);
        }
        if (escrow.getState() != EscrowState.DISPUTED) {
            throw new EscrowNotDisputedException(escrowId, escrow.getState().name());
        }
        if (escrow.getSellerEvidenceSubmittedAt() != null) {
            throw new EvidenceAlreadySubmittedException(escrowId);
        }

        List<EvidenceImage> uploaded = evidenceUploadService.uploadAll(
                escrowId, "seller", evidenceFiles);
        escrow.setSellerEvidenceImages(uploaded);
        escrow.setSellerEvidenceText(body.text());
        escrow.setSellerEvidenceSubmittedAt(OffsetDateTime.now(clock));
        escrowRepo.save(escrow);

        log.info("Seller evidence submitted for escrow {} by user {}: {} image(s)",
                escrowId, sellerUserId, uploaded.size());

        return toStatusResponse(escrow);
    }

    /**
     * Looks up the escrow id for a given auction id. Used by
     * {@link EscrowController} to bridge the auction-id path variable to
     * escrow-id before delegating to {@link #submitSellerEvidence}.
     */
    @Transactional(readOnly = true)
    public Long findEscrowIdByAuctionId(Long auctionId) {
        return escrowRepo.findByAuctionId(auctionId)
                .map(Escrow::getId)
                .orElseThrow(() -> new EscrowNotFoundException(auctionId));
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
     * Refund the winner's L$ when a funded escrow goes DISPUTED / FROZEN /
     * EXPIRED. The {@code fundedAt} guard matters because an unfunded
     * escrow has no money held -- crediting it would invent L$ the winner
     * never paid.
     *
     * <p>Per platform policy, refunds always credit the winner's
     * SLParcels wallet. The winner can withdraw to their SL avatar at any
     * time via the regular Withdraw flow if they want the L$ out of the
     * system. Only called from transactional methods that already hold
     * a pessimistic lock on the escrow row.
     */
    public void queueRefundIfFunded(Escrow escrow) {
        if (escrow.getFundedAt() == null) return;
        User winner = userRepo.findByIdForUpdate(escrow.getAuction().getWinnerUserId()).orElseThrow();
        walletService.creditEscrowRefund(winner, escrow.getFinalBidAmount(), escrow.getId());
        ledgerRepo.save(EscrowTransaction.builder()
                .escrow(escrow)
                .auction(escrow.getAuction())
                .type(EscrowTransactionType.AUCTION_ESCROW_REFUND)
                .status(EscrowTransactionStatus.COMPLETED)
                .amount(escrow.getFinalBidAmount())
                .completedAt(OffsetDateTime.now(clock))
                .build());
        log.info("Escrow {} refund credited to winner wallet (winnerId={}, amount=L${})",
                escrow.getId(), winner.getId(), escrow.getFinalBidAmount());
    }

    /**
     * Delegates to {@link TerminalCommandService#queuePayout} once the
     * ownership monitor confirms the seller has transferred the parcel to
     * the winner. For non-zero payouts (individual / non-group), the state
     * flip from {@code TRANSFER_PENDING} to {@code COMPLETED} is owned by the
     * callback path in {@code TerminalCommandService.applyCallback}, not this
     * hook — queuing the command merely schedules the terminal POST.
     *
     * <p>Sub-project G §8.1: for case-3 (SL-group-owned, payoutAmt = 0),
     * {@code queuePayout} short-circuits and runs the success path inline,
     * returning {@link java.util.Optional#empty()}. The state flip to
     * {@code COMPLETED} has already happened by the time this method returns.
     * We discard the return value either way — the contract is
     * fire-and-forget; the caller doesn't care which branch ran.
     */
    void queuePayoutOnConfirm(Escrow escrow) {
        terminalCommandService.queuePayout(escrow);
    }

    private EscrowStatusResponse toStatusResponse(Escrow escrow) {
        List<EscrowTimelineEntry> timeline = buildTimeline(escrow);
        return new EscrowStatusResponse(
                escrow.getPublicId(), escrow.getAuction().getPublicId(), escrow.getState(),
                escrow.getFinalBidAmount(), escrow.getCommissionAmt(), escrow.getPayoutAmt(),
                escrow.getTransferDeadline(),
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
     * Processes a terminal-posted payment callback. Spec §5.2, §5.5.
     *
     * <p>This method is the single authoritative enforcement point for the
     * entire trust pipeline: shared-secret match (constant-time) →
     * idempotency on {@code slTransactionKey} → terminal registered →
     * escrow exists → state gate → payment deadline → payer UUID match
     * → amount match. Both the SL-header-gated
     * {@link com.slparcelauctions.backend.escrow.payment.EscrowPaymentController}
     * and the dev-profile {@code DevEscrowController} route through this
     * method, so every invocation runs every body-level check regardless of
     * entry point — the controllers do NOT pre-validate the shared secret
     * or any other body field.
     *
     * <p>After validation, the escrow transitions atomically through
     * {@code ESCROW_PENDING → FUNDED → TRANSFER_PENDING} inside a single
     * transaction under {@code PESSIMISTIC_WRITE}, stamps {@code fundedAt}
     * and {@code transferDeadline = now + 72h}, writes a COMPLETED
     * {@code AUCTION_ESCROW_PAYMENT} ledger row, and registers an
     * {@code afterCommit} publication of the {@code ESCROW_FUNDED}
     * envelope. The transient {@code FUNDED} state is never externally
     * observable by design (spec §4); subscribers only ever see the
     * {@code TRANSFER_PENDING} landing state.
     *
     * <p>On any validation failure we write a {@code FAILED} ledger row so a
     * replay of the same {@code slTransactionKey} can reconstruct the prior
     * REFUND response without re-running domain checks and so the dispute
     * timeline shows the rejected attempt. Wrong-payer additionally creates
     * a {@link FraudFlag} with {@link FraudFlagReason#ESCROW_WRONG_PAYER}
     * for the admin review queue.
     *
     * <p>Returns an {@link SlCallbackResponse} in LSL-friendly shape; the
     * HTTP status is always 200 (the domain decision is in the body). Only
     * the SL-header check at the controller layer and the shared-secret
     * check here can produce non-2xx.
     */
    @Transactional
    public SlCallbackResponse acceptPayment(EscrowPaymentRequest req) {
        // Wallet-only escrow funding (spec 2026-05-16): the terminal is no
        // longer a valid funding channel for escrow. Escrows auto-fund
        // from the winner's bid reservation inside createForEndedAuction.
        // This endpoint is preserved only as a defensive refund path so a
        // mis-rezzed legacy terminal can't get L$ stuck — every call
        // returns an ESCROW_EXPIRED REFUND after writing a FAILED ledger
        // row (for idempotent replays + audit trail).
        terminalService.assertSharedSecret(req.sharedSecret());

        // Idempotency: replay of the same slTransactionKey reuses the
        // previous decision (will always be a FAILED refund row post-spec).
        Optional<EscrowTransaction> existing = ledgerRepo
                .findFirstBySlTransactionIdAndType(
                        req.slTransactionKey(), EscrowTransactionType.AUCTION_ESCROW_PAYMENT);
        if (existing.isPresent()) {
            EscrowTransaction tx = existing.get();
            if (tx.getStatus() == EscrowTransactionStatus.COMPLETED) {
                // Pre-spec rows may exist for historical funded escrows.
                // Treat as success replay.
                return SlCallbackResponse.ok();
            }
            return parseRefundFromLedger(tx);
        }

        if (!terminalRepo.existsById(req.terminalId())) {
            return SlCallbackResponse.error(
                    EscrowCallbackResponseReason.UNKNOWN_TERMINAL,
                    "Terminal not registered: " + req.terminalId());
        }

        Escrow escrow = escrowRepo.findByAuctionId(req.auctionId()).orElse(null);
        if (escrow == null) {
            return SlCallbackResponse.error(
                    EscrowCallbackResponseReason.UNKNOWN_AUCTION,
                    "No escrow for auction " + req.auctionId());
        }
        escrow = escrowRepo.findByIdForUpdate(escrow.getId()).orElseThrow();

        writeFailedLedger(escrow, req, EscrowCallbackResponseReason.ESCROW_EXPIRED);
        log.warn("Terminal escrow payment received post-wallet-only migration: auctionId={}, amount=L${}, terminal={} - refunding",
                req.auctionId(), req.amount(), req.terminalId());
        return SlCallbackResponse.refund(
                EscrowCallbackResponseReason.ESCROW_EXPIRED,
                "Escrow payments now happen automatically from your SLParcels wallet. L$ refunded.");
    }

    /**
     * Called by {@link com.slparcelauctions.backend.escrow.scheduler.EscrowOwnershipCheckTask}
     * when the World API reports the parcel's owner has transitioned to the
     * auction winner (spec §4.5). Stamps {@code transferConfirmedAt} and
     * {@code lastCheckedAt}, and resets the consecutive World API failure
     * counter so a run of previously-transient failures does not later tip
     * the escrow into a false WORLD_API_PERSISTENT_FAILURE freeze.
     *
     * <p>State stays {@link EscrowState#TRANSFER_PENDING} — only the payout
     * callback (Task 7) flips the row to {@link EscrowState#COMPLETED}.
     * {@link Propagation#MANDATORY} so a stray invocation outside the
     * ownership-check transaction fails fast: there must be a locked escrow
     * row in scope for the afterCommit envelope to be sound.
     *
     * <p>{@link #queuePayoutOnConfirm(Escrow)} delegates to
     * {@code terminalCommandService.queuePayout(escrow)} so the dispatcher
     * picks up the payout on the next 30s tick.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void confirmTransfer(Escrow escrow, OffsetDateTime now) {
        escrow.setTransferConfirmedAt(now);
        escrow.setLastCheckedAt(now);
        escrow.setConsecutiveWorldApiFailures(0);
        escrow = escrowRepo.save(escrow);
        // No auction-status flip here. confirmTransfer only stamps
        // transferConfirmedAt — the escrow stays in TRANSFER_PENDING, and so
        // does the auction. The COMPLETED flip happens later in
        // TerminalCommandService.handleEscrowPayoutSuccess /
        // runZeroPayoutSuccessInline, where the escrow itself actually
        // transitions to COMPLETED.

        queuePayoutOnConfirm(escrow);

        final EscrowTransferConfirmedEnvelope envelope =
                EscrowTransferConfirmedEnvelope.of(escrow, now);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        broadcastPublisher.publishTransferConfirmed(envelope);
                    }
                });

        // Notify both parties that transfer was confirmed.
        String transferConfirmedParcel = escrow.getAuction().getTitle();
        long transferConfirmedAuctionId = escrow.getAuction().getId();
        long transferConfirmedEscrowId = escrow.getId();
        notificationPublisher.escrowTransferConfirmed(
                escrow.getAuction().getWinnerUserId(),
                transferConfirmedAuctionId, transferConfirmedEscrowId, transferConfirmedParcel);
        notificationPublisher.escrowTransferConfirmed(
                escrow.getAuction().getSeller().getId(),
                transferConfirmedAuctionId, transferConfirmedEscrowId, transferConfirmedParcel);

        log.info("Escrow {} transfer confirmed for auction {}",
                escrow.getId(), escrow.getAuction().getId());
    }

    /**
     * System-triggered freeze on ownership-change to an unknown third party,
     * parcel deletion, or persistent World API failure (spec §4.5). The
     * existing state-machine guard is enforced, so a FROZEN / COMPLETED /
     * EXPIRED escrow cannot be re-frozen by a late monitor sweep.
     *
     * <p>A {@link FraudFlag} row is created for the admin review queue with
     * the supplied evidence payload, and the refund queue is kicked via the
     * same {@link #queueRefundIfFunded} stub that {@code fileDispute} uses —
     * Task 7 wires the real refund dispatcher. The {@code ESCROW_FROZEN}
     * envelope is registered afterCommit so subscribers never observe a
     * rolled-back freeze.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void freezeForFraud(Escrow escrow, FreezeReason reason,
                               Map<String, Object> evidence,
                               OffsetDateTime now) {
        enforceTransitionAllowed(escrow.getId(), escrow.getState(), EscrowState.FROZEN);
        escrow.setState(EscrowState.FROZEN);
        escrow.setFrozenAt(now);
        escrow.setFreezeReason(reason.name());
        escrow.setLastCheckedAt(now);
        escrow = escrowRepo.save(escrow);
        statusFlipper.flip(escrow, AuctionStatus.FROZEN);

        FraudFlagReason flagReason = switch (reason) {
            case UNKNOWN_OWNER -> FraudFlagReason.ESCROW_UNKNOWN_OWNER;
            case PARCEL_DELETED -> FraudFlagReason.ESCROW_PARCEL_DELETED;
            case WORLD_API_PERSISTENT_FAILURE -> FraudFlagReason.ESCROW_WORLD_API_FAILURE;
            case BOT_OWNERSHIP_CHANGED -> FraudFlagReason.BOT_OWNERSHIP_CHANGED;
        };
        fraudFlagRepo.save(FraudFlag.builder()
                .auction(escrow.getAuction())
                .slParcelUuid(escrow.getAuction().getSlParcelUuid())
                .reason(flagReason)
                .detectedAt(now)
                .evidenceJson(evidence)
                .resolved(false)
                .build());


        queueRefundIfFunded(escrow);

        final EscrowFrozenEnvelope envelope = EscrowFrozenEnvelope.of(escrow, now);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        broadcastPublisher.publishFrozen(envelope);
                    }
                });

        // Notify both parties that the escrow was frozen.
        String frozenParcelName = escrow.getAuction().getTitle();
        long frozenAuctionId = escrow.getAuction().getId();
        long frozenEscrowId = escrow.getId();
        String frozenReasonStr = reason.name();
        notificationPublisher.escrowFrozen(
                escrow.getAuction().getWinnerUserId(),
                frozenAuctionId, frozenEscrowId, frozenParcelName, frozenReasonStr);
        notificationPublisher.escrowFrozen(
                escrow.getAuction().getSeller().getId(),
                frozenAuctionId, frozenEscrowId, frozenParcelName, frozenReasonStr);

        log.warn("Escrow {} FROZEN for auction {}: reason={}, evidence={}",
                escrow.getId(), escrow.getAuction().getId(), reason, evidence);
    }

    /**
     * Bumps the consecutive World API failure counter on a transient
     * upstream failure and stamps {@code lastCheckedAt}. Does NOT transition
     * state — the ownership-check task decides whether the next step is
     * another retry or a threshold-triggered freeze.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void incrementWorldApiFailure(Escrow escrow, OffsetDateTime now) {
        int prior = escrow.getConsecutiveWorldApiFailures() == null
                ? 0 : escrow.getConsecutiveWorldApiFailures();
        escrow.setConsecutiveWorldApiFailures(prior + 1);
        escrow.setLastCheckedAt(now);
        escrowRepo.save(escrow);
        log.debug("Escrow {} World API failure count now {}",
                escrow.getId(), prior + 1);
    }

    /**
     * Stamps {@code lastCheckedAt} and resets the World API failure counter
     * without changing state. Used by the ownership monitor for the
     * seller-still-owns-parcel branch — the seller has not yet transferred,
     * but the check itself succeeded, so previous transient failures are
     * no longer relevant.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void stampChecked(Escrow escrow, OffsetDateTime now) {
        escrow.setLastCheckedAt(now);
        escrow.setConsecutiveWorldApiFailures(0);
        escrowRepo.save(escrow);
    }

    /**
     * Expires a {@link EscrowState#TRANSFER_PENDING} escrow whose 72h
     * {@code transferDeadline} has passed AND no PAYOUT command is in flight —
     * the seller never transferred the parcel. Queues a REFUND
     * {@link com.slparcelauctions.backend.escrow.command.TerminalCommand} so
     * the winner's L$ is returned, stamps {@code expiredAt}, and registers
     * an {@link EscrowExpiredEnvelope} ({@code reason=TRANSFER_TIMEOUT}) on
     * {@code afterCommit}. Spec §4.6.
     *
     * <p>Caller must pre-filter by the payout-in-flight guard via
     * {@link EscrowRepository#findExpiredTransferPendingIds}; this method
     * does NOT re-validate the guard — the repo query is the enforcement
     * point. The rare race where a PAYOUT command is created between the
     * repo query and the per-escrow lock is benign: the refund we'd queue
     * is a duplicate, and both commands would be resolved by the admin
     * dispute path rather than silently double-spending, because the
     * escrow row state would first need to have been flipped by another
     * caller.
     *
     * <p>{@link Propagation#MANDATORY} so a stray call outside the
     * timeout-task transaction fails fast.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void expireTransfer(Escrow escrow, OffsetDateTime now) {
        enforceTransitionAllowed(escrow.getId(), escrow.getState(), EscrowState.EXPIRED);
        escrow.setState(EscrowState.EXPIRED);
        escrow.setExpiredAt(now);
        escrow = escrowRepo.save(escrow);
        statusFlipper.flip(escrow, AuctionStatus.EXPIRED);


        queueRefundIfFunded(escrow);

        // Epic 08 sub-spec 1 §3.4 / §6.1: a transfer-timeout is seller-fault
        // (the seller never handed the parcel over inside the 72h window).
        // Increment the seller's escrowExpiredUnfulfilled counter inside the
        // same transaction that flips the escrow to EXPIRED so the counter
        // can't drift on crash-between-steps. The buyer-fault counterpart
        // (expirePayment) intentionally does NOT touch this counter.
        User seller = escrow.getAuction().getSeller();
        int prior = seller.getEscrowExpiredUnfulfilled() == null
                ? 0 : seller.getEscrowExpiredUnfulfilled();
        seller.setEscrowExpiredUnfulfilled(prior + 1);
        userRepo.save(seller);

        final EscrowExpiredEnvelope envelope =
                EscrowExpiredEnvelope.of(escrow, "TRANSFER_TIMEOUT", now);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        broadcastPublisher.publishExpired(envelope);
                    }
                });

        // Notify both parties of transfer-timeout expiry.
        String transferExpiredParcel = escrow.getAuction().getTitle();
        long transferExpiredAuctionId = escrow.getAuction().getId();
        long transferExpiredEscrowId = escrow.getId();
        notificationPublisher.escrowExpired(
                escrow.getAuction().getWinnerUserId(),
                transferExpiredAuctionId, transferExpiredEscrowId, transferExpiredParcel);
        notificationPublisher.escrowExpired(
                escrow.getAuction().getSeller().getId(),
                transferExpiredAuctionId, transferExpiredEscrowId, transferExpiredParcel);

        log.info("Escrow {} EXPIRED (transfer timeout, refund queued): auction {}",
                escrow.getId(), escrow.getAuction().getId());
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

    /**
     * Computes the {@code escrow.payout_amt} for an ended auction, branching
     * on the auction's realty-group shape. The two cases are mutually
     * exclusive at the column level:
     *
     * <ul>
     *   <li><b>Case 3 (E -- SL-group-owned)</b>: {@code realty_group_sl_group_id IS NOT NULL}.
     *       {@code payoutAmt = 0}. The earnings (finalBid - commission) are credited
     *       entirely via internal wallet routing at payout-success:
     *       {@code AgentCommissionDistributor} credits the listing agent's wallet with
     *       {@code agent_slice} and the realty group's wallet with {@code group_slice}.
     *       No L$ leaves SLPA to an SL avatar from the escrow row, so the terminal
     *       PAYOUT command carries amount=0 and is a SL-side no-op. Spec §8.5, §9.6.</li>
     *   <li><b>Individual</b>: both group columns null.
     *       {@code payoutAmt = commission.payout(finalBid)}.</li>
     * </ul>
     *
     * <p>The escrow's {@code payoutTargetUuid} is still the seller's SL avatar for
     * case-3 — the column is set elsewhere from {@code auction.seller.slAvatarUuid}
     * and remains correct. For case-3 it simply receives 0 L$ from the terminal,
     * because all routing is internal.
     */
    private long computePayoutAmt(Auction auction, long finalBid) {
        if (auction.getRealtyGroupSlGroupId() != null) {
            // Case 3: earnings stay in SLPA; AgentCommissionDistributor credits agent
            // and group wallets internally at payout-success.
            return 0L;
        }
        return commission.payout(finalBid);
    }

    /**
     * Flags an escrow for admin review without changing lifecycle state.
     * Idempotent -- already-flagged escrows are a no-op. Does not publish
     * a broadcast envelope (admin-only signal). Retained as a primitive
     * for future review paths; the bot-monitor caller that used to invoke
     * it was retired with the ownership-only verification refactor.
     */
    @Transactional
    public void markReviewRequired(Escrow escrow) {
        if (Boolean.TRUE.equals(escrow.getReviewRequired())) {
            log.debug("Escrow {} already flagged for review", escrow.getId());
            return;
        }
        escrow.setReviewRequired(true);
        escrowRepo.save(escrow);
        log.warn("Escrow {} flagged for admin review", escrow.getId());
    }
}
