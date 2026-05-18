package com.slparcelauctions.backend.admin.escrowreview;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.admin.escrowreview.exception.EscrowReviewAlreadyResolvedException;
import com.slparcelauctions.backend.admin.escrowreview.exception.EscrowReviewNotFoundException;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowService;
import com.slparcelauctions.backend.escrow.review.EscrowManualReview;
import com.slparcelauctions.backend.escrow.review.EscrowManualReviewRepository;
import com.slparcelauctions.backend.escrow.review.ManualReviewResolution;
import com.slparcelauctions.backend.escrow.review.ManualReviewStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Admin orchestrator for the escrow manual-review queue (spec
 * 2026-05-17-escrow-transfer-split-verification §7).
 *
 * <p>Mirrors {@code AdminDisputeService}: list/detail are read-only
 * projections; {@code resolve} runs in a single transaction so the
 * {@link EscrowService} mutators — which are
 * {@code @Transactional(propagation = MANDATORY)} — participate, the review
 * row is stamped, and an {@code AdminActionService} audit row is written
 * atomically. A failed precondition (review missing / not OPEN) leaves the
 * database untouched.
 *
 * <p>Resolution actions:
 * <ul>
 *   <li>{@code FORCE_CONFIRM_SELL_TO} → {@link EscrowService#confirmSellTo}
 *       (same path as bot {@code SELL_TO_OK}).</li>
 *   <li>{@code FORCE_COMPLETE_TRANSFER} → {@link EscrowService#confirmTransfer}
 *       → payout (admin verified the in-world purchase).</li>
 *   <li>{@code REFUND_WINNER} → {@link EscrowService#expireTransfer} → escrow
 *       {@code EXPIRED}, winner refunded if funded.</li>
 *   <li>{@code DISMISS} → close the review, no escrow change.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminEscrowReviewService {

    private final EscrowManualReviewRepository reviewRepo;
    private final EscrowRepository escrowRepo;
    private final EscrowService escrowService;
    private final AdminActionService adminActionService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Page<AdminEscrowReviewRow> list(ManualReviewStatus status, int page, int size) {
        return reviewRepo.findFiltered(status, PageRequest.of(page, size))
                .map(this::toRow);
    }

    @Transactional(readOnly = true)
    public AdminEscrowReviewDetail detail(UUID reviewPublicId) {
        EscrowManualReview review = reviewRepo.findByPublicId(reviewPublicId)
                .orElseThrow(() -> new EscrowReviewNotFoundException(reviewPublicId));
        return toDetail(review);
    }

    @Transactional
    public AdminEscrowReviewResolveResponse resolve(
            UUID reviewPublicId,
            AdminEscrowReviewResolveRequest req,
            Long adminUserId) {

        EscrowManualReview review = reviewRepo.findByPublicId(reviewPublicId)
                .orElseThrow(() -> new EscrowReviewNotFoundException(reviewPublicId));

        if (review.getStatus() != ManualReviewStatus.OPEN) {
            throw new EscrowReviewAlreadyResolvedException(reviewPublicId, review.getStatus());
        }

        ManualReviewResolution action = req.action();
        OffsetDateTime now = OffsetDateTime.now(clock);

        // Pessimistically lock the escrow before any state mutation so a
        // concurrent bot/timeout sweep can't race the admin override.
        Long escrowId = review.getEscrow().getId();
        Escrow escrow = escrowRepo.findByIdForUpdate(escrowId)
                .orElseThrow(() -> new EscrowReviewNotFoundException(reviewPublicId));

        switch (action) {
            case FORCE_CONFIRM_SELL_TO -> escrowService.confirmSellTo(escrow, now);
            case FORCE_COMPLETE_TRANSFER -> escrowService.confirmTransfer(escrow, now);
            case REFUND_WINNER -> escrowService.expireTransfer(escrow, now);
            case DISMISS -> { /* no escrow change */ }
        }

        ManualReviewStatus newStatus = action == ManualReviewResolution.DISMISS
                ? ManualReviewStatus.DISMISSED
                : ManualReviewStatus.RESOLVED;

        review.setStatus(newStatus);
        review.setResolution(action);
        review.setResolvedByAdminId(adminUserId);
        review.setResolvedAt(now);
        review.setAdminNotes(req.adminNote());
        reviewRepo.save(review);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reviewPublicId", reviewPublicId.toString());
        details.put("escrowId", escrowId);
        details.put("action", action.name());
        details.put("newStatus", newStatus.name());
        details.put("adminNote", req.adminNote());
        adminActionService.record(
                adminUserId,
                AdminActionType.ESCROW_REVIEW_RESOLVED,
                AdminActionTargetType.ESCROW_REVIEW,
                escrowId,
                req.adminNote(),
                details);

        log.info("Escrow review {} resolved: action={}, newStatus={}, escrowId={}, adminUserId={}",
                reviewPublicId, action, newStatus, escrowId, adminUserId);

        return new AdminEscrowReviewResolveResponse(reviewPublicId, newStatus, action, now);
    }

    private AdminEscrowReviewRow toRow(EscrowManualReview r) {
        Escrow e = r.getEscrow();
        Auction a = e.getAuction();
        long ageMinutes = r.getCreatedAt() == null ? 0
                : ChronoUnit.MINUTES.between(r.getCreatedAt(), OffsetDateTime.now(clock));
        return new AdminEscrowReviewRow(
                r.getPublicId(),
                e.getPublicId(),
                a.getPublicId(),
                parcelName(a),
                r.getStep(),
                r.getReason(),
                r.getStatus(),
                r.getRequestedRole(),
                r.getCreatedAt(),
                ageMinutes);
    }

    private AdminEscrowReviewDetail toDetail(EscrowManualReview r) {
        Escrow e = r.getEscrow();
        Auction a = e.getAuction();
        AuctionParcelSnapshot snap = a.getParcelSnapshot();
        return new AdminEscrowReviewDetail(
                r.getPublicId(),
                e.getPublicId(),
                a.getPublicId(),
                a.getTitle(),
                parcelName(a),
                snap != null ? snap.getSlurl() : null,
                r.getStep(),
                r.getReason(),
                r.getStatus(),
                r.getRequestedRole(),
                r.getResolution(),
                r.getAdminNotes(),
                r.getCreatedAt(),
                r.getResolvedAt(),
                e.getState(),
                e.getFinalBidAmount() == null ? 0L : e.getFinalBidAmount(),
                e.getFundedAt(),
                e.getSellToConfirmedAt(),
                e.getTransferConfirmedAt(),
                e.getTransferDeadline(),
                e.getSellToLastResult(),
                e.getSellToLastCheckedAt(),
                nz(e.getSellToVerifyAttempts()),
                nz(e.getBuyVerifySellerAttempts()),
                nz(e.getBuyVerifyBuyerAttempts()),
                nz(e.getConsecutiveSellToBotFailures()),
                nz(e.getConsecutiveWorldApiFailures()));
    }

    private static String parcelName(Auction a) {
        AuctionParcelSnapshot snap = a.getParcelSnapshot();
        return snap != null ? snap.getParcelName() : a.getTitle();
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
