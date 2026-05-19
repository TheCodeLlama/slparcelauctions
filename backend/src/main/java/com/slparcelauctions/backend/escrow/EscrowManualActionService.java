package com.slparcelauctions.backend.escrow;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.bot.BotTask;
import com.slparcelauctions.backend.bot.BotTaskRepository;
import com.slparcelauctions.backend.bot.BotTaskStatus;
import com.slparcelauctions.backend.bot.BotTaskType;
import com.slparcelauctions.backend.escrow.dto.EscrowStatusResponse;
import com.slparcelauctions.backend.escrow.exception.EscrowAccessDeniedException;
import com.slparcelauctions.backend.escrow.exception.EscrowManualAttemptsExhaustedException;
import com.slparcelauctions.backend.escrow.exception.EscrowNotFoundException;
import com.slparcelauctions.backend.escrow.exception.EscrowStepNotReadyException;
import com.slparcelauctions.backend.escrow.review.EscrowManualReview;
import com.slparcelauctions.backend.escrow.review.EscrowManualReviewRepository;
import com.slparcelauctions.backend.escrow.review.ManualReviewReason;
import com.slparcelauctions.backend.escrow.review.ManualReviewRole;
import com.slparcelauctions.backend.escrow.review.ManualReviewStatus;
import com.slparcelauctions.backend.escrow.review.ManualReviewStep;
import com.slparcelauctions.backend.escrow.terminal.EscrowConfigProperties;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroup;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroupRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Manual escrow-step actions invoked from the escrow page (spec §5.4, §6.1,
 * §7), kept out of {@link EscrowService} so the orchestrator stays focused
 * on the system-driven lifecycle. Every method loads the escrow row under a
 * {@code PESSIMISTIC_WRITE} lock ({@link EscrowRepository#findByIdForUpdate})
 * inside its own transaction so it serialises against the bot result
 * callback, the ownership monitor, the timeout job and the payment callback
 * that take the same lock. The World-API verify path reuses the exact
 * classification matrix from
 * {@link com.slparcelauctions.backend.escrow.scheduler.EscrowOwnershipCheckTask}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EscrowManualActionService {

    /** Payload key on the {@link BotTask#getResultData()} JSON map carrying
     *  the requesting role ("SELLER" / "BUYER") so the callback consumes the
     *  correct manual-attempt counter for verify-purchase. */
    public static final String REQUESTING_ROLE_KEY = "requestingRole";
    public static final String REQUESTING_ROLE_SELLER = "SELLER";
    public static final String REQUESTING_ROLE_BUYER = "BUYER";

    /** Payload key carrying the expected pre-transfer owner type
     *  ("agent" for case-1 / "group" for case-3) so the bot can match both
     *  UUID and ownerType when classifying live ownership. The pre-transfer
     *  UUID itself is stamped onto {@link BotTask#getExpectedSellerUuid()}. */
    public static final String EXPECTED_OWNER_TYPE_KEY = "expectedOwnerType";
    public static final String EXPECTED_OWNER_TYPE_AGENT = "agent";
    public static final String EXPECTED_OWNER_TYPE_GROUP = "group";

    private final EscrowRepository escrowRepo;
    private final EscrowService escrowService;
    private final BotTaskRepository botTaskRepo;
    private final EscrowManualReviewRepository manualReviewRepo;
    private final UserRepository userRepo;
    private final RealtyGroupSlGroupRepository slGroupRepo;
    private final EscrowConfigProperties props;
    private final Clock clock;

    /**
     * Seller-only manual "Verify Sell To" expedite (spec §5.4). Bumps the
     * open {@code VERIFY_SELL_TO} bot task to run immediately and arms
     * {@code manualVerifyPending} so the next definitive-negative bot result
     * consumes one of the seller's attempts. No attempt is consumed here —
     * the result arrives asynchronously via the bot callback.
     */
    @Transactional
    public EscrowStatusResponse verifySellTo(Long auctionId, Long callerUserId) {
        Escrow escrow = lockEscrowForAuction(auctionId);
        Auction auction = escrow.getAuction();
        Long sellerId = auction.getSeller() == null ? null : auction.getSeller().getId();
        if (sellerId == null || !sellerId.equals(callerUserId)) {
            // Only the seller sets Sell To, so only the seller can ask us to
            // re-check it. Buyer/stranger gets the generic forbidden.
            throw new EscrowAccessDeniedException();
        }
        assertSetSellToSubPhase(escrow);

        int cap = props.manualVerifyAttempts();
        int consumed = nz(escrow.getSellToVerifyAttempts());
        if (consumed >= cap) {
            throw new EscrowManualAttemptsExhaustedException("Set-Sell-To");
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        // Best-effort expedite: Phase 3 creates the recurring task at
        // funding. Absent (pre-Phase-3 / already-completed) → no-op; the
        // pending flag still arms attempt-consumption for whichever check
        // lands next.
        List<BotTask> open = botTaskRepo.findOpenByEscrowAndType(
                escrow.getId(), BotTaskType.VERIFY_SELL_TO);
        if (!open.isEmpty()) {
            BotTask task = open.get(0);
            task.setNextRunAt(now);
            botTaskRepo.save(task);
        }
        escrow.setManualVerifyPending(true);
        escrowRepo.save(escrow);

        log.info("Manual verify-sell-to requested for escrow {} (auction {}) by seller {}",
                escrow.getId(), auctionId, callerUserId);
        return escrowService.getStatus(auctionId, callerUserId);
    }

    /**
     * Seller-or-buyer manual "Verify Purchase" (spec §6.1, bot-dispatch refactor
     * 2026-05-18). Expedites an open {@code VERIFY_BUY_OWNER} bot task (or
     * creates one if none exists) and arms {@code manualVerifyPending} so the
     * UI greys both verify buttons until the bot result lands. Definitive-
     * negative outcomes (owner still seller/group) consume the caller-role
     * attempt counter back in {@link com.slparcelauctions.backend.bot.BotTaskResultService};
     * no attempt is consumed here.
     *
     * <p>The 30-min automated background ownership polling stays on the
     * cheaper World-API path — this bot-dispatched flow is scoped to the
     * user-initiated manual verify only.
     */
    @Transactional
    public EscrowStatusResponse verifyTransfer(Long auctionId, Long callerUserId) {
        Escrow escrow = lockEscrowForAuction(auctionId);
        Auction auction = escrow.getAuction();
        boolean isSeller = auction.getSeller() != null
                && callerUserId.equals(auction.getSeller().getId());
        boolean isWinner = auction.getWinnerUserId() != null
                && callerUserId.equals(auction.getWinnerUserId());
        if (!isSeller && !isWinner) {
            throw new EscrowAccessDeniedException();
        }
        if (escrow.getSellToConfirmedAt() == null) {
            throw new EscrowStepNotReadyException(
                    "Set Sell To must be confirmed before verifying the purchase");
        }
        if (escrow.getState() != EscrowState.TRANSFER_PENDING
                || escrow.getTransferConfirmedAt() != null) {
            throw new EscrowStepNotReadyException(
                    "Escrow is not in the Buy-Parcel sub-phase");
        }

        int cap = props.manualVerifyAttempts();
        int consumed = isSeller
                ? nz(escrow.getBuyVerifySellerAttempts())
                : nz(escrow.getBuyVerifyBuyerAttempts());
        if (consumed >= cap) {
            throw new EscrowManualAttemptsExhaustedException("Buy-Parcel");
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        String requestingRole = isSeller ? REQUESTING_ROLE_SELLER : REQUESTING_ROLE_BUYER;

        // Resolve the expected pre-transfer owner. Case-3 (group listing):
        // the parcel is held by the realty group's registered SL group until
        // the buy completes — expected owner is the SL group UUID with type
        // "group". Case-1 (individual): expected owner is the seller's
        // avatar UUID with type "agent".
        java.util.UUID expectedPreTransferUuid;
        String expectedOwnerType;
        if (auction.getRealtyGroupSlGroupId() != null) {
            RealtyGroupSlGroup slGroup = slGroupRepo
                    .findById(auction.getRealtyGroupSlGroupId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Auction " + auction.getId() + " references missing "
                                    + "RealtyGroupSlGroup " + auction.getRealtyGroupSlGroupId()));
            expectedPreTransferUuid = slGroup.getSlGroupUuid();
            expectedOwnerType = EXPECTED_OWNER_TYPE_GROUP;
        } else {
            expectedPreTransferUuid = auction.getSeller() == null
                    ? null : auction.getSeller().getSlAvatarUuid();
            expectedOwnerType = EXPECTED_OWNER_TYPE_AGENT;
        }

        // Expedite any open VERIFY_BUY_OWNER task for this escrow; otherwise
        // create one. The "one open task per escrow per type" invariant
        // matches the VERIFY_SELL_TO path (BotTaskRepository.findOpenByEscrowAndType
        // returns at most one row). The requesting role + expected
        // pre-transfer owner type are stamped onto the task's resultData
        // payload so the bot callback consumes the correct role counter on a
        // definitive negative and the bot can distinguish PRE_TRANSFER from
        // STRANGER outcomes. The pre-transfer UUID itself rides on the
        // dedicated expectedSellerUuid column.
        List<BotTask> open = botTaskRepo.findOpenByEscrowAndType(
                escrow.getId(), BotTaskType.VERIFY_BUY_OWNER);
        BotTask task;
        if (!open.isEmpty()) {
            task = open.get(0);
            task.setNextRunAt(now);
            task.setExpectedSellerUuid(expectedPreTransferUuid);
            Map<String, Object> payload = task.getResultData() == null
                    ? new HashMap<>() : new HashMap<>(task.getResultData());
            payload.put(REQUESTING_ROLE_KEY, requestingRole);
            payload.put(EXPECTED_OWNER_TYPE_KEY, expectedOwnerType);
            task.setResultData(payload);
        } else {
            AuctionParcelSnapshot snap = auction.getParcelSnapshot();
            User winner = userRepo.findById(auction.getWinnerUserId()).orElseThrow();
            Map<String, Object> payload = new HashMap<>();
            payload.put(REQUESTING_ROLE_KEY, requestingRole);
            payload.put(EXPECTED_OWNER_TYPE_KEY, expectedOwnerType);
            task = BotTask.builder()
                    .taskType(BotTaskType.VERIFY_BUY_OWNER)
                    .status(BotTaskStatus.PENDING)
                    .auction(auction)
                    .escrow(escrow)
                    .parcelUuid(snap != null ? snap.getSlParcelUuid()
                            : auction.getSlParcelUuid())
                    .regionName(snap != null ? snap.getRegionName() : null)
                    .positionX(snap != null ? snap.getPositionX() : null)
                    .positionY(snap != null ? snap.getPositionY() : null)
                    .positionZ(snap != null ? snap.getPositionZ() : null)
                    .expectedWinnerUuid(winner.getSlAvatarUuid())
                    .expectedSellerUuid(expectedPreTransferUuid)
                    .nextRunAt(now)
                    .sentinelPrice(0L)
                    .resultData(payload)
                    .build();
        }
        botTaskRepo.save(task);
        escrow.setManualVerifyPending(true);
        escrowRepo.save(escrow);

        log.info("Manual verify-transfer requested for escrow {} (auction {}) by {} ({})",
                escrow.getId(), auctionId, callerUserId, requestingRole);
        return escrowService.getStatus(auctionId, callerUserId);
    }

    /**
     * User-initiated escalation (spec §7). Idempotent: an existing OPEN
     * review is returned without creating a new row. Does not freeze the
     * escrow or alter the deadline — the bot/poll keep running so it can
     * still self-resolve.
     */
    @Transactional
    public EscrowStatusResponse requestManualReview(
            Long auctionId, Long callerUserId, String note) {
        Escrow escrow = lockEscrowForAuction(auctionId);
        Auction auction = escrow.getAuction();
        boolean isSeller = auction.getSeller() != null
                && callerUserId.equals(auction.getSeller().getId());
        boolean isWinner = auction.getWinnerUserId() != null
                && callerUserId.equals(auction.getWinnerUserId());
        if (!isSeller && !isWinner) {
            throw new EscrowAccessDeniedException();
        }

        EscrowManualReview existing = manualReviewRepo
                .findByEscrowIdAndStatus(escrow.getId(), ManualReviewStatus.OPEN)
                .orElse(null);
        if (existing == null) {
            ManualReviewStep step = escrow.getSellToConfirmedAt() == null
                    ? ManualReviewStep.SET_SELL_TO : ManualReviewStep.BUY_PARCEL;
            EscrowManualReview review = EscrowManualReview.builder()
                    .escrow(escrow)
                    .requestedByUserId(callerUserId)
                    .requestedRole(isSeller ? ManualReviewRole.SELLER : ManualReviewRole.BUYER)
                    .step(step)
                    .reason(ManualReviewReason.USER_REQUESTED)
                    .status(ManualReviewStatus.OPEN)
                    .adminNotes(note == null || note.isBlank() ? null : note)
                    .build();
            manualReviewRepo.save(review);
            log.info("Manual review opened for escrow {} (auction {}) by user {} at step {}",
                    escrow.getId(), auctionId, callerUserId, step);
        } else {
            log.debug("Manual review already open for escrow {} — idempotent no-op",
                    escrow.getId());
        }
        return escrowService.getStatus(auctionId, callerUserId);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Escrow lockEscrowForAuction(Long auctionId) {
        Escrow escrow = escrowRepo.findByAuctionId(auctionId)
                .orElseThrow(() -> new EscrowNotFoundException(auctionId));
        return escrowRepo.findByIdForUpdate(escrow.getId()).orElseThrow();
    }

    private void assertSetSellToSubPhase(Escrow escrow) {
        if (escrow.getState() != EscrowState.TRANSFER_PENDING
                || escrow.getSellToConfirmedAt() != null) {
            throw new EscrowStepNotReadyException(
                    "Escrow is not in the Set-Sell-To sub-phase");
        }
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
