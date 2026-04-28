package com.slparcelauctions.backend.admin.disputes;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.admin.disputes.exception.AlsoCancelInvalidForActionException;
import com.slparcelauctions.backend.admin.disputes.exception.DisputeActionInvalidForStateException;
import com.slparcelauctions.backend.admin.disputes.exception.DisputeNotFoundException;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.CancellationService;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.escrow.command.TerminalCommandService;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Central orchestrator for admin dispute resolution (Epic 10 sub-spec 3 §Task 9).
 *
 * <p>All state transitions, refund queuing, listing cancellation, notifications,
 * and audit rows are committed atomically in a single transaction. The method
 * validates state preconditions before mutating any row so any validation
 * failure leaves the database untouched.
 *
 * <p>State transitions supported:
 * <ul>
 *   <li>DISPUTED + RECOGNIZE_PAYMENT → TRANSFER_PENDING</li>
 *   <li>DISPUTED + RESET_TO_FUNDED, no checkbox → FUNDED</li>
 *   <li>DISPUTED + RESET_TO_FUNDED + alsoCancelListing → EXPIRED (refund queued + listing cancelled)</li>
 *   <li>FROZEN + RESUME_TRANSFER → TRANSFER_PENDING</li>
 *   <li>FROZEN + MARK_EXPIRED → EXPIRED (refund queued)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminDisputeService {

    private final EscrowRepository escrowRepo;
    private final TerminalCommandService terminalCommandService;
    private final CancellationService cancellationService;
    private final NotificationPublisher notificationPublisher;
    private final AdminActionService adminActionService;
    private final Clock clock;

    @Transactional
    public AdminDisputeResolveResponse resolve(
            Long escrowId,
            AdminDisputeResolveRequest req,
            Long adminUserId) {

        Escrow escrow = escrowRepo.findById(escrowId)
                .orElseThrow(() -> new DisputeNotFoundException(escrowId));

        AdminDisputeAction action = req.action();
        boolean alsoCancel = Boolean.TRUE.equals(req.alsoCancelListing());

        // alsoCancelListing is only meaningful with RESET_TO_FUNDED.
        if (alsoCancel && action != AdminDisputeAction.RESET_TO_FUNDED) {
            throw new AlsoCancelInvalidForActionException(action);
        }

        // Validate action against current state.
        EscrowState currentState = escrow.getState();
        boolean disputedAction = action == AdminDisputeAction.RECOGNIZE_PAYMENT
                || action == AdminDisputeAction.RESET_TO_FUNDED;
        boolean frozenAction = action == AdminDisputeAction.RESUME_TRANSFER
                || action == AdminDisputeAction.MARK_EXPIRED;

        if (disputedAction && currentState != EscrowState.DISPUTED) {
            throw new DisputeActionInvalidForStateException(escrowId, action, currentState.name());
        }
        if (frozenAction && currentState != EscrowState.FROZEN) {
            throw new DisputeActionInvalidForStateException(escrowId, action, currentState.name());
        }

        Auction auction = escrow.getAuction();
        long winnerUserId = auction.getWinnerUserId();
        long sellerUserId = auction.getSeller().getId();

        // Compute target state.
        EscrowState newState = switch (action) {
            case RECOGNIZE_PAYMENT -> EscrowState.TRANSFER_PENDING;
            case RESET_TO_FUNDED -> alsoCancel ? EscrowState.EXPIRED : EscrowState.FUNDED;
            case RESUME_TRANSFER -> EscrowState.TRANSFER_PENDING;
            case MARK_EXPIRED -> EscrowState.EXPIRED;
        };
        boolean reachedExpired = newState == EscrowState.EXPIRED;

        escrow.setState(newState);
        escrowRepo.save(escrow);

        // Queue refund when escrow reaches EXPIRED, but only if funded.
        // An unfunded escrow has no L$ to refund — queueing a REFUND command
        // for a never-funded escrow would send L$ the winner never paid.
        boolean refundQueued = false;
        if (reachedExpired && escrow.getFundedAt() != null) {
            terminalCommandService.queueRefund(escrow);
            refundQueued = true;
        }

        // Cancel listing when requested (RESET_TO_FUNDED + checkbox path).
        // cancelByDisputeResolution fires LISTING_REMOVED_BY_ADMIN to the seller
        // so we skip the seller-side DISPUTE_RESOLVED notification below.
        boolean listingCancelled = false;
        if (alsoCancel) {
            cancellationService.cancelByDisputeResolution(
                    auction.getId(), adminUserId,
                    "Dispute resolution: " + req.adminNote());
            listingCancelled = true;
        }

        // Fan out DISPUTE_RESOLVED notifications.
        // Winner always receives it.
        notificationPublisher.disputeResolved(
                winnerUserId, "winner",
                auction.getId(), escrow.getId(),
                auction.getTitle(), escrow.getFinalBidAmount(),
                action, alsoCancel);
        // Seller receives it UNLESS alsoCancelListing fired (that path already
        // notified the seller via LISTING_REMOVED_BY_ADMIN).
        if (!alsoCancel) {
            notificationPublisher.disputeResolved(
                    sellerUserId, "seller",
                    auction.getId(), escrow.getId(),
                    auction.getTitle(), escrow.getFinalBidAmount(),
                    action, false);
        }

        // Audit row 1: always written.
        Map<String, Object> resolveDetails = new LinkedHashMap<>();
        resolveDetails.put("disputeEscrowId", escrow.getId());
        resolveDetails.put("action", action.name());
        resolveDetails.put("alsoCancelListing", alsoCancel);
        resolveDetails.put("refundQueued", refundQueued);
        resolveDetails.put("adminNote", req.adminNote());
        adminActionService.record(
                adminUserId,
                AdminActionType.DISPUTE_RESOLVED,
                AdminActionTargetType.DISPUTE,
                escrow.getId(),
                req.adminNote(),
                resolveDetails);

        // Audit row 2: written only when alsoCancelListing fires.
        if (alsoCancel) {
            Map<String, Object> cancelDetails = new LinkedHashMap<>();
            cancelDetails.put("auctionId", auction.getId());
            cancelDetails.put("originatingDisputeEscrowId", escrow.getId());
            cancelDetails.put("refundQueued", refundQueued);
            adminActionService.record(
                    adminUserId,
                    AdminActionType.LISTING_CANCELLED_VIA_DISPUTE,
                    AdminActionTargetType.AUCTION,
                    auction.getId(),
                    req.adminNote(),
                    cancelDetails);
        }

        log.info("Dispute resolved: escrowId={}, action={}, alsoCancel={}, " +
                "newState={}, refundQueued={}, adminUserId={}",
                escrow.getId(), action, alsoCancel, newState, refundQueued, adminUserId);

        return new AdminDisputeResolveResponse(
                escrow.getId(), newState, refundQueued, listingCancelled,
                OffsetDateTime.now(clock));
    }
}
