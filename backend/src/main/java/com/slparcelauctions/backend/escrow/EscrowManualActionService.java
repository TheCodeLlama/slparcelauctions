package com.slparcelauctions.backend.escrow;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.bot.BotTask;
import com.slparcelauctions.backend.bot.BotTaskRepository;
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
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.dto.ParcelPageData;
import com.slparcelauctions.backend.sl.exception.ParcelNotFoundInSlException;
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

    private final EscrowRepository escrowRepo;
    private final EscrowService escrowService;
    private final BotTaskRepository botTaskRepo;
    private final EscrowManualReviewRepository manualReviewRepo;
    private final SlWorldApiClient worldApi;
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
     * Seller-or-buyer manual "Verify Purchase" (spec §6.1). Runs the
     * World-API owner check inline (sub-second) and applies the same
     * outcome matrix as the scheduled monitor. Definitive-negative
     * (owner still seller/group) consumes the caller-role attempt counter.
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
        UUID parcelUuid = auction.getSlParcelUuid();
        try {
            ParcelMetadata result = worldApi.fetchParcelPage(parcelUuid)
                    .map(ParcelPageData::parcel)
                    .block();
            if (result == null) {
                // Degenerate empty response — treat as a retryable infra
                // failure, do not consume an attempt.
                throw new IllegalStateException("Empty World API response for parcel " + parcelUuid);
            }

            UUID ownerUuid = result.ownerUuid();
            User winner = userRepo.findById(auction.getWinnerUserId()).orElseThrow();
            UUID winnerUuid = winner.getSlAvatarUuid();

            UUID expectedPreTransfer;
            String expectedPreTransferLabel;
            if (auction.getRealtyGroupSlGroupId() != null) {
                RealtyGroupSlGroup reg = slGroupRepo
                        .findById(auction.getRealtyGroupSlGroupId())
                        .orElse(null);
                expectedPreTransfer = (reg == null) ? null : reg.getSlGroupUuid();
                expectedPreTransferLabel = "expectedGroupUuid";
            } else {
                expectedPreTransfer = auction.getSeller().getSlAvatarUuid();
                expectedPreTransferLabel = "expectedSellerUuid";
            }

            if (winnerUuid != null && winnerUuid.equals(ownerUuid)) {
                escrowService.confirmTransfer(escrow, now);
                return escrowService.getStatus(auctionId, callerUserId);
            }
            if (expectedPreTransfer != null && expectedPreTransfer.equals(ownerUuid)) {
                // Definitive negative: parcel still pre-transfer. Consume the
                // caller's role counter, then stamp-checked (success of the
                // check itself resets the World-API failure streak).
                if (isSeller) {
                    escrow.setBuyVerifySellerAttempts(consumed + 1);
                } else {
                    escrow.setBuyVerifyBuyerAttempts(consumed + 1);
                }
                escrowRepo.save(escrow);
                escrowService.stampChecked(escrow, now);
                return escrowService.getStatus(auctionId, callerUserId);
            }

            Map<String, Object> evidence = new HashMap<>();
            evidence.put("observedOwnerUuid", ownerUuid == null ? "<null>" : ownerUuid.toString());
            evidence.put("expectedWinnerUuid", winnerUuid == null ? "<null>" : winnerUuid.toString());
            evidence.put(expectedPreTransferLabel,
                    expectedPreTransfer == null ? "<null>" : expectedPreTransfer.toString());
            evidence.put("observedOwnerType", result.ownerType() == null ? "<null>" : result.ownerType());
            evidence.put("source", "manual-verify-transfer");
            escrowService.freezeForFraud(escrow, FreezeReason.UNKNOWN_OWNER, evidence, now);
            return escrowService.getStatus(auctionId, callerUserId);

        } catch (ParcelNotFoundInSlException e) {
            Map<String, Object> evidence = new HashMap<>();
            evidence.put("parcelUuid", parcelUuid.toString());
            evidence.put("worldApiMessage", e.getMessage() == null ? "" : e.getMessage());
            evidence.put("source", "manual-verify-transfer");
            escrowService.freezeForFraud(escrow, FreezeReason.PARCEL_DELETED, evidence, now);
            return escrowService.getStatus(auctionId, callerUserId);
        }
        // ExternalApiTimeoutException (and the IllegalStateException above)
        // propagate unconsumed — a transient infra failure must not burn a
        // manual attempt. The escrow @RestControllerAdvice / GlobalExceptionHandler
        // surface it as a retryable error.
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
