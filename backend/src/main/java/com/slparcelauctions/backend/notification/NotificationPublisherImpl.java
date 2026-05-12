package com.slparcelauctions.backend.notification;

import com.slparcelauctions.backend.notification.NotificationDao.UpsertResult;
import com.slparcelauctions.backend.notification.dto.NotificationDto;
import com.slparcelauctions.backend.notification.slim.SlImChannelDispatcher;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupInvitation;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Default implementation of {@link NotificationPublisher}.
 *
 * <p>Each single-recipient method builds the per-category data blob via
 * {@link NotificationDataBuilder}, formats title/body templates, computes
 * the coalesce key, and delegates to {@link NotificationService#publish}.
 *
 * <p>The fan-out method ({@link #listingCancelledBySellerFanout}) defers
 * all DB writes to {@code afterCommit} to ensure they only fire when the
 * parent transaction committed. Each recipient runs in its own
 * {@code REQUIRES_NEW} transaction so a FK violation for one stale bidder
 * ID does not abort delivery to the remaining recipients.
 *
 * <p>Uses an explicit constructor rather than {@code @RequiredArgsConstructor}
 * because Lombok does not propagate field-level {@code @Qualifier} annotations
 * to generated constructor parameters — same pattern as {@code FeaturedCache}.
 */
@Component
@Slf4j
public class NotificationPublisherImpl implements NotificationPublisher {

    private final NotificationService notificationService;
    private final NotificationDao notificationDao;
    private final NotificationWsBroadcasterPort wsBroadcaster;
    private final TransactionTemplate requiresNewTxTemplate;
    private final SlImChannelDispatcher slImChannelDispatcher;
    private final RealtyGroupRepository realtyGroupRepository;
    private final RealtyGroupMemberRepository realtyGroupMemberRepository;
    private final UserRepository userRepository;

    public NotificationPublisherImpl(
            NotificationService notificationService,
            NotificationDao notificationDao,
            NotificationWsBroadcasterPort wsBroadcaster,
            @Qualifier("requiresNewTxTemplate") TransactionTemplate requiresNewTxTemplate,
            SlImChannelDispatcher slImChannelDispatcher,
            RealtyGroupRepository realtyGroupRepository,
            RealtyGroupMemberRepository realtyGroupMemberRepository,
            UserRepository userRepository) {
        this.notificationService = notificationService;
        this.notificationDao = notificationDao;
        this.wsBroadcaster = wsBroadcaster;
        this.requiresNewTxTemplate = requiresNewTxTemplate;
        this.slImChannelDispatcher = slImChannelDispatcher;
        this.realtyGroupRepository = realtyGroupRepository;
        this.realtyGroupMemberRepository = realtyGroupMemberRepository;
        this.userRepository = userRepository;
    }

    @Override
    public void outbid(long bidderUserId, long auctionId, String parcelName,
                       long currentBidL, boolean isProxyOutbid, OffsetDateTime endsAt) {
        String title = "You've been outbid on " + parcelName;
        String body = isProxyOutbid
            ? String.format("Your proxy max was reached. Current bid is L$%,d.", currentBidL)
            : String.format("Current bid is L$%,d.", currentBidL);
        notificationService.publish(new NotificationEvent(
            bidderUserId, NotificationCategory.OUTBID, title, body,
            NotificationDataBuilder.outbid(auctionId, parcelName, currentBidL, isProxyOutbid, endsAt),
            "outbid:" + bidderUserId + ":" + auctionId
        ));
    }

    @Override
    public void proxyExhausted(long bidderUserId, long auctionId, String parcelName,
                                long proxyMaxL, OffsetDateTime endsAt) {
        String title = "Your proxy on " + parcelName + " was exhausted";
        String body = String.format("Your max bid (L$%,d) was reached. Place a new proxy to keep bidding.", proxyMaxL);
        notificationService.publish(new NotificationEvent(
            bidderUserId, NotificationCategory.PROXY_EXHAUSTED, title, body,
            NotificationDataBuilder.proxyExhausted(auctionId, parcelName, proxyMaxL, endsAt),
            "proxy_exhausted:" + bidderUserId + ":" + auctionId
        ));
    }

    @Override
    public void auctionWon(long winnerUserId, long auctionId, String parcelName, long winningBidL) {
        String title = "You won " + parcelName + "!";
        String body = String.format("Pay L$%,d into escrow within 24 hours to claim the parcel.", winningBidL);
        notificationService.publish(new NotificationEvent(
            winnerUserId, NotificationCategory.AUCTION_WON, title, body,
            NotificationDataBuilder.auctionWon(auctionId, parcelName, winningBidL),
            null
        ));
    }

    @Override
    public void auctionLost(long bidderUserId, long auctionId, String parcelName, long winningBidL) {
        String title = "Auction ended: " + parcelName;
        String body = String.format("Winning bid was L$%,d. Better luck next time.", winningBidL);
        notificationService.publish(new NotificationEvent(
            bidderUserId, NotificationCategory.AUCTION_LOST, title, body,
            NotificationDataBuilder.auctionLost(auctionId, parcelName, winningBidL),
            null
        ));
    }

    @Override
    public void auctionEndedSold(long sellerUserId, long auctionId, String parcelName, long winningBidL) {
        String title = "Your auction sold: " + parcelName;
        String body = String.format("Winning bid: L$%,d. Awaiting buyer's escrow payment.", winningBidL);
        notificationService.publish(new NotificationEvent(
            sellerUserId, NotificationCategory.AUCTION_ENDED_SOLD, title, body,
            NotificationDataBuilder.auctionEndedSold(auctionId, parcelName, winningBidL),
            null
        ));
    }

    @Override
    public void auctionEndedReserveNotMet(long sellerUserId, long auctionId, String parcelName, long highestBidL) {
        String title = "Reserve not met: " + parcelName;
        String body = String.format("Highest bid was L$%,d, below your reserve. The auction has ended.", highestBidL);
        notificationService.publish(new NotificationEvent(
            sellerUserId, NotificationCategory.AUCTION_ENDED_RESERVE_NOT_MET, title, body,
            NotificationDataBuilder.auctionEndedReserveNotMet(auctionId, parcelName, highestBidL),
            null
        ));
    }

    @Override
    public void auctionEndedNoBids(long sellerUserId, long auctionId, String parcelName) {
        String title = "No bids received: " + parcelName;
        String body = "Your auction ended without any bids. You can re-list at any time.";
        notificationService.publish(new NotificationEvent(
            sellerUserId, NotificationCategory.AUCTION_ENDED_NO_BIDS, title, body,
            NotificationDataBuilder.auctionEndedNoBids(auctionId, parcelName),
            null
        ));
    }

    @Override
    public void auctionEndedBoughtNow(long sellerUserId, long auctionId, String parcelName, long buyNowL) {
        String title = "Buy-now exercised: " + parcelName;
        String body = String.format("Sold at L$%,d via Buy Now.", buyNowL);
        notificationService.publish(new NotificationEvent(
            sellerUserId, NotificationCategory.AUCTION_ENDED_BOUGHT_NOW, title, body,
            NotificationDataBuilder.auctionEndedBoughtNow(auctionId, parcelName, buyNowL),
            null
        ));
    }

    @Override
    public void escrowFunded(long sellerUserId, long auctionId, long escrowId,
                              String parcelName, OffsetDateTime transferDeadline) {
        String title = "Buyer funded escrow on " + parcelName;
        String body = "Transfer the parcel to escrow within 72 hours to release payout.";
        notificationService.publish(new NotificationEvent(
            sellerUserId, NotificationCategory.ESCROW_FUNDED, title, body,
            NotificationDataBuilder.escrowFunded(auctionId, escrowId, parcelName, transferDeadline),
            null
        ));
    }

    @Override
    public void escrowTransferConfirmed(long userId, long auctionId, long escrowId, String parcelName) {
        String title = "Land transfer confirmed: " + parcelName;
        String body = "Payout is processing.";
        notificationService.publish(new NotificationEvent(
            userId, NotificationCategory.ESCROW_TRANSFER_CONFIRMED, title, body,
            NotificationDataBuilder.escrowTransferConfirmed(auctionId, escrowId, parcelName),
            null
        ));
    }

    @Override
    public void escrowPayout(long sellerUserId, long auctionId, long escrowId,
                              String parcelName, long payoutL) {
        String title = String.format("Payout received: L$%,d", payoutL);
        String body = parcelName + " escrow completed successfully.";
        notificationService.publish(new NotificationEvent(
            sellerUserId, NotificationCategory.ESCROW_PAYOUT, title, body,
            NotificationDataBuilder.escrowPayout(auctionId, escrowId, parcelName, payoutL),
            null
        ));
    }

    @Override
    public void escrowExpired(long userId, long auctionId, long escrowId, String parcelName) {
        String title = "Escrow expired: " + parcelName;
        String body = "The escrow window passed without completion.";
        notificationService.publish(new NotificationEvent(
            userId, NotificationCategory.ESCROW_EXPIRED, title, body,
            NotificationDataBuilder.escrowExpired(auctionId, escrowId, parcelName),
            null
        ));
    }

    @Override
    public void escrowDisputed(long userId, long auctionId, long escrowId,
                                String parcelName, String reasonCategory) {
        String title = "Escrow disputed: " + parcelName;
        String body = "A dispute was opened. Awaiting admin review.";
        notificationService.publish(new NotificationEvent(
            userId, NotificationCategory.ESCROW_DISPUTED, title, body,
            NotificationDataBuilder.escrowDisputed(auctionId, escrowId, parcelName, reasonCategory),
            null
        ));
    }

    @Override
    public void escrowFrozen(long userId, long auctionId, long escrowId,
                              String parcelName, String reason) {
        String title = "Escrow frozen: " + parcelName;
        String body = "Held pending review. Contact support if you need information.";
        notificationService.publish(new NotificationEvent(
            userId, NotificationCategory.ESCROW_FROZEN, title, body,
            NotificationDataBuilder.escrowFrozen(auctionId, escrowId, parcelName, reason),
            null
        ));
    }

    @Override
    public void escrowPayoutStalled(long sellerUserId, long auctionId, long escrowId, String parcelName) {
        String title = "Payout delayed: " + parcelName;
        String body = "Your payout is delayed. We're investigating; no action needed from you.";
        notificationService.publish(new NotificationEvent(
            sellerUserId, NotificationCategory.ESCROW_PAYOUT_STALLED, title, body,
            NotificationDataBuilder.escrowPayoutStalled(auctionId, escrowId, parcelName),
            null
        ));
    }

    @Override
    public void escrowTransferReminder(long sellerUserId, long auctionId, long escrowId,
                                        String parcelName, OffsetDateTime transferDeadline) {
        String title = "Reminder: transfer " + parcelName;
        String body = "Your escrow window expires soon. Transfer the parcel to release payout.";
        notificationService.publish(new NotificationEvent(
            sellerUserId, NotificationCategory.ESCROW_TRANSFER_REMINDER, title, body,
            NotificationDataBuilder.escrowTransferReminder(auctionId, escrowId, parcelName, transferDeadline),
            "transfer_reminder:" + sellerUserId + ":" + escrowId
        ));
    }

    @Override
    public void listingVerified(long sellerUserId, long auctionId, String parcelName) {
        String title = "Listing verified: " + parcelName;
        String body = "Your parcel listing is now live.";
        notificationService.publish(new NotificationEvent(
            sellerUserId, NotificationCategory.LISTING_VERIFIED, title, body,
            NotificationDataBuilder.listingVerified(auctionId, parcelName),
            null
        ));
    }

    @Override
    public void listingSuspended(long sellerUserId, long auctionId, String parcelName, String reason) {
        String title = "Listing suspended: " + parcelName;
        String body = "Reason: " + reason + ". Contact support for details.";
        notificationService.publish(new NotificationEvent(
            sellerUserId, NotificationCategory.LISTING_SUSPENDED, title, body,
            NotificationDataBuilder.listingSuspended(auctionId, parcelName, reason),
            null
        ));
    }

    @Override
    public void listingRemovedByAdmin(long sellerUserId, long auctionId, String parcelName, String reason) {
        String title = "Listing removed: " + parcelName;
        String body = "Your listing has been removed by SLParcels staff. Reason: " + reason + ".";
        notificationService.publish(new NotificationEvent(
            sellerUserId, NotificationCategory.LISTING_REMOVED_BY_ADMIN, title, body,
            NotificationDataBuilder.listingRemovedByAdmin(auctionId, parcelName, reason),
            null
        ));
    }

    @Override
    public void brokerCancelled(Long listingAgentUserId, Long auctionId, String auctionTitle,
                                Long brokerUserId, String reason) {
        // Phase 4 stub — log-only, matching the pattern used for group wallet
        // notifications (Epic 09 will wire the dispatcher fan-out + body copy).
        log.info("[NOTIF] brokerCancelled to user={} auction={} title='{}' broker={} reason='{}'",
                listingAgentUserId, auctionId, auctionTitle, brokerUserId, reason);
    }

    @Override
    public void listingWarned(long sellerUserId, long auctionId, String parcelName, String notes) {
        String title = "Warning on your listing: " + parcelName;
        String body = "An admin has reviewed reports on this listing and issued a warning. Notes: " + notes;
        notificationService.publish(new NotificationEvent(
            sellerUserId, NotificationCategory.LISTING_WARNED, title, body,
            NotificationDataBuilder.listingWarned(auctionId, parcelName, notes),
            null
        ));
    }

    @Override
    public void listingReinstated(long sellerUserId, long auctionId, String parcelName, OffsetDateTime newEndsAt) {
        String title = "Listing reinstated: " + parcelName;
        String body = "Your auction has been reinstated. All existing bids and proxy maxes are preserved. "
            + "Ends " + newEndsAt + ".";
        notificationService.publish(new NotificationEvent(
            sellerUserId, NotificationCategory.LISTING_REINSTATED, title, body,
            NotificationDataBuilder.listingReinstated(auctionId, parcelName, newEndsAt),
            null
        ));
    }

    @Override
    public void listingReviewRequired(long sellerUserId, long auctionId, String parcelName, String reason) {
        String title = "Review required: " + parcelName;
        String body = "Reason: " + reason + ".";
        notificationService.publish(new NotificationEvent(
            sellerUserId, NotificationCategory.LISTING_REVIEW_REQUIRED, title, body,
            NotificationDataBuilder.listingReviewRequired(auctionId, parcelName, reason),
            null
        ));
    }

    @Override
    public void reviewReceived(long revieweeUserId, long reviewId, long auctionId,
                                String parcelName, int rating) {
        String title = "New review (" + rating + "★): " + parcelName;
        String body = "A new review has been posted on your transaction.";
        notificationService.publish(new NotificationEvent(
            revieweeUserId, NotificationCategory.REVIEW_RECEIVED, title, body,
            NotificationDataBuilder.reviewReceived(reviewId, auctionId, parcelName, rating),
            null
        ));
    }

    @Override
    public void reviewResponseWindowClosing(long revieweeUserId, long reviewId, long auctionId,
                                             String parcelName, OffsetDateTime responseDeadline) {
        String title = "Response window closing: " + parcelName;
        String body = "The window to respond to a review for " + parcelName
                + " closes soon. Respond before " + responseDeadline + ".";
        notificationService.publish(new NotificationEvent(
            revieweeUserId, NotificationCategory.REVIEW_RESPONSE_WINDOW_CLOSING,
            title, body,
            NotificationDataBuilder.reviewResponseWindowClosing(
                    reviewId, auctionId, parcelName, responseDeadline),
            null));
    }

    @Override
    public void disputeFiledAgainstSeller(long sellerUserId, long auctionId, long escrowId,
                                           String parcelName, long amountL,
                                           String reasonCategory) {
        String title = "A winner disputed your sale: " + parcelName;
        String body = "A winner disputed your sale of " + parcelName
                + " (L$ " + amountL + "). Submit your evidence to help admins resolve.";
        notificationService.publish(new NotificationEvent(
            sellerUserId,
            NotificationCategory.DISPUTE_FILED_AGAINST_SELLER,
            title, body,
            NotificationDataBuilder.disputeFiledAgainstSeller(
                    auctionId, escrowId, parcelName, amountL, reasonCategory),
            null));
    }

    @Override
    public void disputeResolved(long recipientUserId, String role,
                                 long auctionId, long escrowId,
                                 String parcelName, long amountL,
                                 com.slparcelauctions.backend.admin.disputes.AdminDisputeAction action,
                                 boolean alsoCancelListing) {
        String title = "Dispute resolved: " + parcelName;
        String body = bodyFor(role, action, alsoCancelListing, parcelName, amountL);
        notificationService.publish(new NotificationEvent(
            recipientUserId,
            NotificationCategory.DISPUTE_RESOLVED,
            title, body,
            NotificationDataBuilder.disputeResolved(
                    auctionId, escrowId, parcelName, amountL,
                    action.name(), alsoCancelListing, role),
            null));
    }

    private static String bodyFor(String role,
                                   com.slparcelauctions.backend.admin.disputes.AdminDisputeAction action,
                                   boolean alsoCancelListing,
                                   String parcelName, long amountL) {
        return switch (action) {
            case RECOGNIZE_PAYMENT -> "winner".equals(role)
                    ? "Payment recognized for " + parcelName + ". Land transfer monitoring resumed."
                    : "Dispute resolved for " + parcelName + ". Please transfer the parcel to the winner.";
            case RESET_TO_FUNDED -> {
                if (alsoCancelListing) {
                    yield "winner".equals(role)
                            ? "Your dispute for " + parcelName
                                + " was upheld. The listing has been cancelled and your L$ "
                                + amountL + " refund is being processed."
                            : "Listing cancelled by admin via dispute resolution: " + parcelName;
                }
                yield "winner".equals(role)
                        ? "Dispute dismissed for " + parcelName
                            + ". Escrow remains funded; please complete payment at the terminal."
                        : "Dispute resolved for " + parcelName + ". Escrow remains funded.";
            }
            case RESUME_TRANSFER -> "Escrow unfrozen for " + parcelName
                    + ". Land transfer monitoring resumed.";
            case MARK_EXPIRED -> "winner".equals(role)
                    ? "Escrow expired for " + parcelName
                        + ". Your L$ " + amountL + " refund is being processed."
                    : "Escrow expired for " + parcelName + ".";
        };
    }

    @Override
    public void reconciliationMismatch(List<Long> adminUserIds, long drift, String date) {
        String title = "Daily reconciliation mismatch";
        String body = "Daily reconciliation detected L$ " + drift + " drift on " + date + ". Open dashboard.";
        for (Long adminId : adminUserIds) {
            notificationService.publish(new NotificationEvent(
                adminId, NotificationCategory.RECONCILIATION_MISMATCH,
                title, body,
                NotificationDataBuilder.reconciliationMismatch(drift, date),
                null));
        }
    }

    @Override
    public void withdrawalCompleted(long adminUserId, long amountL, String recipientUuid) {
        notificationService.publish(new NotificationEvent(
            adminUserId, NotificationCategory.WITHDRAWAL_COMPLETED,
            "Withdrawal completed",
            "Withdrawal of L$ " + amountL + " to " + recipientUuid + " completed.",
            NotificationDataBuilder.withdrawalCompleted(amountL, recipientUuid),
            null));
    }

    @Override
    public void withdrawalFailed(long adminUserId, long amountL, String recipientUuid, String reason) {
        notificationService.publish(new NotificationEvent(
            adminUserId, NotificationCategory.WITHDRAWAL_FAILED,
            "Withdrawal failed",
            "Withdrawal of L$ " + amountL + " to " + recipientUuid + " failed: " + reason + ". Open dashboard.",
            NotificationDataBuilder.withdrawalFailed(amountL, recipientUuid, reason),
            null));
    }

    @Override
    public void walletWithdrawalCompleted(long userId, long amountL, Long ledgerEntryId) {
        String title = String.format("Withdrawal completed: L$%,d", amountL);
        String body = "L$ " + amountL + " has been transferred to your SL avatar from your SLParcels wallet.";
        notificationService.publish(new NotificationEvent(
            userId, NotificationCategory.WALLET_WITHDRAWAL_COMPLETED, title, body,
            NotificationDataBuilder.walletWithdrawalCompleted(amountL, ledgerEntryId),
            null));
    }

    @Override
    public void walletWithdrawalReversed(long userId, long amountL, Long ledgerEntryId, String reason) {
        String title = String.format("Withdrawal reversed: L$%,d", amountL);
        String body = "Your L$ " + amountL + " withdrawal could not be completed and was credited back to your SLParcels wallet. Reason: " + reason;
        notificationService.publish(new NotificationEvent(
            userId, NotificationCategory.WALLET_WITHDRAWAL_REVERSED, title, body,
            NotificationDataBuilder.walletWithdrawalReversed(amountL, ledgerEntryId, reason),
            null));
    }

    @Override
    public void walletAdjusted(long userId, long deltaL, String notes) {
        String sign = deltaL >= 0 ? "+" : "";
        String title = String.format("Wallet adjusted by admin: %sL$%,d", sign, deltaL);
        String body = "An admin adjusted your wallet balance. Note: " + notes;
        notificationService.publish(new NotificationEvent(
            userId, NotificationCategory.WALLET_ADJUSTED, title, body,
            NotificationDataBuilder.walletAdmin(deltaL, notes),
            null));
    }

    @Override
    public void walletFrozen(long userId, String notes) {
        String title = "Wallet frozen by admin";
        String body = "All wallet outflows (withdrawals, penalty payments, listing fees, bids) are blocked until an admin unfreezes your wallet. Reason: " + notes;
        notificationService.publish(new NotificationEvent(
            userId, NotificationCategory.WALLET_FROZEN, title, body,
            NotificationDataBuilder.walletAdmin(0L, notes),
            null));
    }

    @Override
    public void walletUnfrozen(long userId, String notes) {
        String title = "Wallet unfrozen";
        String body = "Your wallet is no longer frozen. Note: " + notes;
        notificationService.publish(new NotificationEvent(
            userId, NotificationCategory.WALLET_UNFROZEN, title, body,
            NotificationDataBuilder.walletAdmin(0L, notes),
            null));
    }

    @Override
    public void walletPenaltyForgiven(long userId, long amountL, String notes) {
        String title = String.format("Penalty forgiven: L$%,d", amountL);
        String body = "An admin forgave L$ " + amountL + " of your penalty. Note: " + notes;
        notificationService.publish(new NotificationEvent(
            userId, NotificationCategory.WALLET_PENALTY_FORGIVEN, title, body,
            NotificationDataBuilder.walletAdmin(amountL, notes),
            null));
    }

    @Override
    public void walletDormancyReset(long userId, String notes) {
        String title = "Wallet dormancy reset";
        String body = "Your wallet's dormancy state was cleared by an admin. Note: " + notes;
        notificationService.publish(new NotificationEvent(
            userId, NotificationCategory.WALLET_DORMANCY_RESET, title, body,
            NotificationDataBuilder.walletAdmin(0L, notes),
            null));
    }

    @Override
    public void walletTermsCleared(long userId, String notes) {
        String title = "Wallet terms re-acceptance required";
        String body = "An admin reset your wallet terms acceptance. You'll be asked to re-accept the terms next time you visit the wallet page. Note: " + notes;
        notificationService.publish(new NotificationEvent(
            userId, NotificationCategory.WALLET_TERMS_CLEARED, title, body,
            NotificationDataBuilder.walletAdmin(0L, notes),
            null));
    }

    @Override
    public void walletWithdrawalForceCompleted(long userId, long amountL, Long ledgerEntryId, String notes) {
        String title = String.format("Withdrawal completed manually: L$%,d", amountL);
        String body = "An admin marked your L$ " + amountL + " withdrawal as completed. Note: " + notes;
        notificationService.publish(new NotificationEvent(
            userId, NotificationCategory.WITHDRAWAL_FORCE_COMPLETED, title, body,
            NotificationDataBuilder.walletWithdrawalReversed(amountL, ledgerEntryId, notes),
            null));
    }

    @Override
    public void walletWithdrawalForceFailed(long userId, long amountL, Long ledgerEntryId, String notes) {
        String title = String.format("Withdrawal failed and refunded: L$%,d", amountL);
        String body = "An admin marked your L$ " + amountL + " withdrawal as failed and refunded the L$ to your wallet. Note: " + notes;
        notificationService.publish(new NotificationEvent(
            userId, NotificationCategory.WITHDRAWAL_FORCE_FAILED, title, body,
            NotificationDataBuilder.walletWithdrawalReversed(amountL, ledgerEntryId, notes),
            null));
    }

    @Override
    public void listingCancelledBySellerFanout(long auctionId, List<Long> bidderUserIds,
                                                String parcelName, String reason) {
        // Cause-neutral copy — applies to both seller-driven cancel and admin-driven cancel.
        Map<String, Object> data = NotificationDataBuilder.listingCancelledBySeller(auctionId, parcelName, reason);
        String title = "Auction cancelled: " + parcelName;
        String body = "This auction has been cancelled. Your active proxy bid is no longer in effect. Reason: " + reason + ".";

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (Long bidderId : bidderUserIds) {
                    try {
                        UpsertResult result = requiresNewTxTemplate.execute(status -> {
                            UpsertResult r = notificationDao.upsert(bidderId,
                                NotificationCategory.LISTING_CANCELLED_BY_SELLER,
                                title, body, data, null);
                            slImChannelDispatcher.maybeQueueForFanout(
                                bidderId, NotificationCategory.LISTING_CANCELLED_BY_SELLER,
                                title, body, data, null);
                            return r;
                        });
                        wsBroadcaster.broadcastUpsert(bidderId, result, dtoFor(bidderId, result, data, title, body));
                    } catch (Exception ex) {
                        log.warn("Fan-out notification failed for userId={} auctionId={} category=LISTING_CANCELLED_BY_SELLER: {}",
                                 bidderId, auctionId, ex.toString());
                    }
                }
            }
        });
    }

    private NotificationDto dtoFor(
            long userId, UpsertResult result, Map<String, Object> data, String title, String body) {
        return new NotificationDto(
            result.publicId(),
            NotificationCategory.LISTING_CANCELLED_BY_SELLER,
            NotificationGroup.LISTING_STATUS,
            title, body, data, false,
            result.createdAt(), result.updatedAt()
        );
    }

    @Override
    public void listingAutoCancelledFromBulkSuspend(long sellerUserId, long auctionId, String parcelName) {
        // Reuse the LISTING_CANCELLED_BY_SELLER envelope so the seller's listing
        // feed groups the auto-cancel alongside other listing-cancellation
        // notifications. The specific BULK_SUSPEND_TIMER_EXPIRED reason is
        // surfaced both in the body copy and the data blob so downstream UIs
        // can distinguish this from a self-cancel.
        String title = "Auction auto-cancelled: " + parcelName;
        String body = "Your listing " + parcelName
            + " was auto-cancelled because the group suspension on this auction lapsed"
            + " without admin reinstatement. Active bids have been released.";
        Map<String, Object> data = NotificationDataBuilder.listingCancelledBySeller(
            auctionId, parcelName, "BULK_SUSPEND_TIMER_EXPIRED");
        notificationService.publish(new NotificationEvent(
            sellerUserId, NotificationCategory.LISTING_CANCELLED_BY_SELLER, title, body, data,
            /* coalesceKey */ null));
    }

    // ─────────────────── Realty groups — lifecycle dispatch (spec §8) ───────────────────
    //
    // Each method composes a Notification with category REALTY_GROUP_* and dispatches
    // once per resolved recipient. Recipient sets per spec §8:
    //
    //   invitation sent     → invitee
    //   invitation accepted → leader + INVITE_AGENTS delegates (excluding the new member)
    //   invitation declined → leader + INVITE_AGENTS delegates
    //   invitation expired  → leader + INVITE_AGENTS delegates
    //   member removed      → removed user only
    //   member left         → leader + INVITE_AGENTS delegates (excluding leftUser if they
    //                          themselves held INVITE_AGENTS)
    //   leadership transfer → old leader + new leader + all other current members
    //   dissolved           → every former member
    //   permissions changed → the affected member only
    //
    // Body copy follows SLParcels transactional voice: plain, professional, no em-dashes.

    @Override
    public void realtyGroupInvitationSent(RealtyGroupInvitation invitation) {
        RealtyGroup group = realtyGroupRepository.findById(invitation.getGroupId()).orElse(null);
        if (group == null) {
            log.warn("realtyGroupInvitationSent: group not found, groupId={} invitationId={}",
                invitation.getGroupId(), invitation.getId());
            return;
        }
        User inviter = userRepository.findById(invitation.getInvitedById()).orElse(null);
        String inviterName = inviter == null ? "A group leader" : inviter.getDisplayName();
        String title = "You were invited to join " + group.getName();
        String body = inviterName + " invited you to join the realty group " + group.getName()
            + ". Review the proposed permissions and accept or decline from your invitations dashboard.";
        Map<String, Object> data = NotificationDataBuilder.realtyGroupInvitation(
            group.getPublicId(), group.getName(), group.getSlug(),
            invitation.getPublicId(),
            inviter == null ? null : inviter.getPublicId(),
            inviterName);
        publishOne(invitation.getInvitedUserId(),
            NotificationCategory.REALTY_GROUP_INVITATION_SENT, title, body, data);
    }

    @Override
    public void realtyGroupInvitationAccepted(RealtyGroupInvitation invitation) {
        RealtyGroup group = realtyGroupRepository.findById(invitation.getGroupId()).orElse(null);
        if (group == null) return;
        User joiner = userRepository.findById(invitation.getInvitedUserId()).orElse(null);
        String joinerName = joiner == null ? "A new member" : joiner.getDisplayName();
        String title = joinerName + " joined " + group.getName();
        String body = joinerName + " accepted the invitation to join " + group.getName() + ".";
        Map<String, Object> data = NotificationDataBuilder.realtyGroupInvitation(
            group.getPublicId(), group.getName(), group.getSlug(),
            invitation.getPublicId(),
            joiner == null ? null : joiner.getPublicId(),
            joinerName);

        // Recipients: leader + INVITE_AGENTS delegates, excluding the new member themselves
        // (they're the actor; a join notification to themselves is noise).
        Set<Long> recipients = resolveLeaderAndInviteDelegates(group.getId(), invitation.getInvitedUserId());
        for (Long userId : recipients) {
            publishOne(userId, NotificationCategory.REALTY_GROUP_INVITATION_ACCEPTED, title, body, data);
        }
    }

    @Override
    public void realtyGroupInvitationDeclined(RealtyGroupInvitation invitation) {
        RealtyGroup group = realtyGroupRepository.findById(invitation.getGroupId()).orElse(null);
        if (group == null) return;
        User decliner = userRepository.findById(invitation.getInvitedUserId()).orElse(null);
        String declinerName = decliner == null ? "The invited user" : decliner.getDisplayName();
        String title = declinerName + " declined invitation to " + group.getName();
        String body = declinerName + " declined the invitation to join " + group.getName() + ".";
        Map<String, Object> data = NotificationDataBuilder.realtyGroupInvitation(
            group.getPublicId(), group.getName(), group.getSlug(),
            invitation.getPublicId(),
            decliner == null ? null : decliner.getPublicId(),
            declinerName);

        Set<Long> recipients = resolveLeaderAndInviteDelegates(group.getId(), invitation.getInvitedUserId());
        for (Long userId : recipients) {
            publishOne(userId, NotificationCategory.REALTY_GROUP_INVITATION_DECLINED, title, body, data);
        }
    }

    @Override
    public void realtyGroupInvitationExpired(RealtyGroupInvitation invitation) {
        RealtyGroup group = realtyGroupRepository.findById(invitation.getGroupId()).orElse(null);
        if (group == null) return;
        User invitee = userRepository.findById(invitation.getInvitedUserId()).orElse(null);
        String inviteeName = invitee == null ? "An invited user" : invitee.getDisplayName();
        String title = "Invitation to " + group.getName() + " expired";
        String body = "The invitation to " + inviteeName + " for " + group.getName()
            + " expired without a response. You can re-invite from the group's invitations tab.";
        Map<String, Object> data = NotificationDataBuilder.realtyGroupInvitation(
            group.getPublicId(), group.getName(), group.getSlug(),
            invitation.getPublicId(),
            invitee == null ? null : invitee.getPublicId(),
            inviteeName);

        // Recipients: leader + INVITE_AGENTS delegates. Note: the invitee never became a
        // member, so they're not in the delegate set; no exclusion needed.
        Set<Long> recipients = resolveLeaderAndInviteDelegates(group.getId());
        for (Long userId : recipients) {
            publishOne(userId, NotificationCategory.REALTY_GROUP_INVITATION_EXPIRED, title, body, data);
        }
    }

    @Override
    public void realtyGroupMemberRemoved(RealtyGroup group, User removedUser) {
        if (removedUser == null) return;
        String removedName = removedUser.getDisplayName();
        String title = "You were removed from " + group.getName();
        String body = "You are no longer a member of " + group.getName()
            + ". Contact the group leader if this was unexpected.";
        Map<String, Object> data = NotificationDataBuilder.realtyGroupMembership(
            group.getPublicId(), group.getName(), group.getSlug(),
            removedUser.getPublicId(), removedName);
        publishOne(removedUser.getId(),
            NotificationCategory.REALTY_GROUP_MEMBER_REMOVED, title, body, data);
    }

    @Override
    public void realtyGroupMemberLeft(RealtyGroup group, User leftUser) {
        if (leftUser == null) return;
        String leftName = leftUser.getDisplayName();
        String title = leftName + " left " + group.getName();
        String body = leftName + " left the realty group " + group.getName() + ".";
        Map<String, Object> data = NotificationDataBuilder.realtyGroupMembership(
            group.getPublicId(), group.getName(), group.getSlug(),
            leftUser.getPublicId(), leftName);

        // Recipients: leader + INVITE_AGENTS delegates, excluding the user who left
        // (if they themselves held INVITE_AGENTS, they'd otherwise self-notify).
        Set<Long> recipients = resolveLeaderAndInviteDelegates(group.getId(), leftUser.getId());
        for (Long userId : recipients) {
            publishOne(userId, NotificationCategory.REALTY_GROUP_MEMBER_LEFT, title, body, data);
        }
    }

    @Override
    public void realtyGroupLeadershipTransferred(RealtyGroup group, User oldLeader, User newLeader,
                                                  boolean oldLeaderStayed) {
        if (oldLeader == null || newLeader == null) return;
        String oldName = oldLeader.getDisplayName();
        String newName = newLeader.getDisplayName();
        String title = "Leadership of " + group.getName() + " transferred";
        String body = newName + " is now the leader of " + group.getName()
            + ". Previous leader " + oldName
            + (oldLeaderStayed ? " remains a member with full delegated permissions."
                                : " has left the group.");
        Map<String, Object> data = NotificationDataBuilder.realtyGroupLeadershipTransferred(
            group.getPublicId(), group.getName(), group.getSlug(),
            oldLeader.getPublicId(), oldName,
            newLeader.getPublicId(), newName,
            oldLeaderStayed);

        // Recipients: old leader + new leader + all OTHER current members (deduped).
        Set<Long> recipients = new LinkedHashSet<>();
        recipients.add(oldLeader.getId());
        recipients.add(newLeader.getId());
        for (RealtyGroupMember m : realtyGroupMemberRepository.findByGroupIdOrderByJoinedAtAsc(group.getId())) {
            recipients.add(m.getUserId());
        }
        for (Long userId : recipients) {
            publishOne(userId, NotificationCategory.REALTY_GROUP_LEADERSHIP_TRANSFERRED, title, body, data);
        }
    }

    @Override
    public void realtyGroupDissolved(RealtyGroup group, List<User> formerMembers) {
        if (formerMembers == null || formerMembers.isEmpty()) return;
        String title = group.getName() + " was dissolved";
        String body = "The realty group " + group.getName()
            + " has been dissolved. Any listings previously attached to it are no longer affiliated with a group.";
        Map<String, Object> data = NotificationDataBuilder.realtyGroupBase(
            group.getPublicId(), group.getName(), group.getSlug());

        Set<Long> seen = new HashSet<>();
        for (User member : formerMembers) {
            if (member == null) continue;
            if (!seen.add(member.getId())) continue;
            publishOne(member.getId(),
                NotificationCategory.REALTY_GROUP_DISSOLVED, title, body, data);
        }
    }

    @Override
    public void realtyGroupPermissionsChanged(RealtyGroup group, RealtyGroupMember member,
                                              Set<RealtyGroupPermission> added,
                                              Set<RealtyGroupPermission> removed) {
        if (member == null) return;
        Set<String> addedNames = added == null
            ? Set.of()
            : new TreeSet<>(toNames(added));
        Set<String> removedNames = removed == null
            ? Set.of()
            : new TreeSet<>(toNames(removed));
        String title = "Your permissions in " + group.getName() + " changed";
        StringBuilder body = new StringBuilder("Your permissions in ").append(group.getName()).append(" were updated.");
        if (!addedNames.isEmpty()) {
            body.append(" Granted: ").append(String.join(", ", addedNames)).append('.');
        }
        if (!removedNames.isEmpty()) {
            body.append(" Revoked: ").append(String.join(", ", removedNames)).append('.');
        }
        Map<String, Object> data = NotificationDataBuilder.realtyGroupPermissionsChanged(
            group.getPublicId(), group.getName(), group.getSlug(),
            addedNames, removedNames);
        publishOne(member.getUserId(),
            NotificationCategory.REALTY_GROUP_PERMISSIONS_CHANGED, title, body.toString(), data);
    }

    // ── Realty group helpers ────────────────────────────────────────────────────────

    /**
     * Resolves the recipient set "leader + members who hold INVITE_AGENTS", deduped,
     * minus any caller-provided exclusions (e.g. the actor whose action triggered the
     * notification). The returned set preserves insertion order: leader first, then
     * delegates in {@code joined_at} order. Exclusions are silently dropped.
     */
    Set<Long> resolveLeaderAndInviteDelegates(Long groupId, Long... exclusions) {
        Set<Long> excluded = new HashSet<>();
        if (exclusions != null) {
            for (Long e : exclusions) {
                if (e != null) excluded.add(e);
            }
        }
        Set<Long> out = new LinkedHashSet<>();
        Optional<RealtyGroup> g = realtyGroupRepository.findById(groupId);
        g.ifPresent(group -> {
            if (group.getLeaderId() != null && !excluded.contains(group.getLeaderId())) {
                out.add(group.getLeaderId());
            }
        });
        for (RealtyGroupMember m : realtyGroupMemberRepository.findByGroupIdOrderByJoinedAtAsc(groupId)) {
            if (excluded.contains(m.getUserId())) continue;
            // Skip the leader's member row — already added; this check also guards a
            // future bug where the leader row carries permissions that are otherwise ignored.
            if (g.isPresent() && g.get().getLeaderId() != null
                    && g.get().getLeaderId().equals(m.getUserId())) {
                continue;
            }
            if (m.permissionSet().contains(RealtyGroupPermission.INVITE_AGENTS)) {
                out.add(m.getUserId());
            }
        }
        return out;
    }

    // ── Realty groups — admin moderation (sub-project F §8, §9). Fan-out to every
    // current member. Task 28 will refine body copy + SL IM integration; for now
    // this routes through the existing in-app publish primitive so callers can
    // compile and the audit trail flows.

    @Override
    public void realtyGroupSuspended(RealtyGroup group, String reason,
                                     OffsetDateTime expiresAt) {
        if (group == null) return;
        boolean permanent = expiresAt == null;
        String title = (permanent ? "Group banned: " : "Group suspended: ") + group.getName();
        StringBuilder body = new StringBuilder();
        if (permanent) {
            body.append("An admin has banned ").append(group.getName())
                .append(". The group can no longer create listings, manage memberships, ")
                .append("or move wallet funds.");
        } else {
            body.append("An admin has suspended ").append(group.getName())
                .append(" until ").append(expiresAt)
                .append(". The group cannot create listings, manage memberships, ")
                .append("or move wallet funds until the suspension lifts.");
        }
        if (reason != null && !reason.isBlank()) {
            body.append(" Reason: ").append(reason).append('.');
        }
        Map<String, Object> data = NotificationDataBuilder.realtyGroupBase(
            group.getPublicId(), group.getName(), group.getSlug());
        data.put("reason", reason);
        data.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
        data.put("permanent", permanent);
        for (RealtyGroupMember m : realtyGroupMemberRepository.findByGroupIdOrderByJoinedAtAsc(group.getId())) {
            publishOne(m.getUserId(),
                NotificationCategory.REALTY_GROUP_SUSPENDED, title, body.toString(), data);
        }
    }

    @Override
    public void realtyGroupUnsuspended(RealtyGroup group) {
        if (group == null) return;
        String title = "Group reinstated: " + group.getName();
        String body = "The suspension on " + group.getName()
            + " has been lifted. The group can resume normal operations.";
        Map<String, Object> data = NotificationDataBuilder.realtyGroupBase(
            group.getPublicId(), group.getName(), group.getSlug());
        for (RealtyGroupMember m : realtyGroupMemberRepository.findByGroupIdOrderByJoinedAtAsc(group.getId())) {
            publishOne(m.getUserId(),
                NotificationCategory.REALTY_GROUP_UNSUSPENDED, title, body, data);
        }
    }

    @Override
    public void realtyGroupSlGroupDriftDetected(long leaderUserId, long groupId,
                                                String slGroupName, String driftReason) {
        // Phase 4 wiring — route through the existing in-app publish primitive so the
        // group leader gets notified the moment the reverify task flags drift. Body
        // copy + SL IM channel dispatch are refined in Task 28; carrying groupId +
        // driftReason on the data blob is enough for the frontend feed today.
        RealtyGroup group = realtyGroupRepository.findById(groupId).orElse(null);
        String groupName = group == null ? "your realty group" : group.getName();
        String slLabel = slGroupName == null || slGroupName.isBlank() ? "an SL group" : slGroupName;
        String title = "SL group drift detected: " + slLabel;
        String body = switch (driftReason) {
            case "FOUNDER_CHANGED" -> "The founder of " + slLabel
                + " (registered to " + groupName + ") has changed. The realty group may have lost"
                + " in-world ownership of this SL group. Re-verify or unregister it from your"
                + " group's SL groups tab.";
            case "GROUP_NOT_FOUND" -> "The SL group " + slLabel + " (registered to "
                + groupName + ") could not be found on Second Life. It may have been deleted."
                + " Unregister it from your group's SL groups tab.";
            case "FETCH_FAILED_REPEATEDLY" -> "Repeated attempts to re-verify " + slLabel
                + " (registered to " + groupName + ") against Second Life failed. The group"
                + " may have become unreachable. Re-verify from your group's SL groups tab once"
                + " SL is responsive again.";
            default -> "Drift was detected on " + slLabel + " (registered to "
                + groupName + "). Review the group's SL groups tab.";
        };
        Map<String, Object> data = NotificationDataBuilder.realtyGroupBase(
            group == null ? null : group.getPublicId(),
            group == null ? null : group.getName(),
            group == null ? null : group.getSlug());
        data.put("slGroupName", slGroupName);
        data.put("driftReason", driftReason);
        publishOne(leaderUserId,
            NotificationCategory.REALTY_GROUP_SL_GROUP_DRIFT_DETECTED, title, body, data);
    }

    // ── Group wallet withdrawal notifications (stub — fanout wired when Epic 09 dispatcher extended)

    @Override
    public void groupWalletWithdrawalCompleted(Long groupId, long amount, Long ledgerId) {
        log.info("[NOTIF] group {} withdrawal completed L${} (ledger {})", groupId, amount, ledgerId);
    }

    @Override
    public void groupWalletWithdrawalReversed(Long groupId, long amount, Long ledgerId, String reason) {
        log.warn("[NOTIF] group {} withdrawal reversed L${} (ledger {}) reason={}",
            groupId, amount, ledgerId, reason);
    }

    @Override
    public void groupWalletDormancyFlagged(Long groupId, int phase, long balance) {
        log.info("[NOTIF] group {} dormancy flagged phase={} balance=L${}", groupId, phase, balance);
    }

    @Override
    public void groupWalletDormancyAutoReturned(Long groupId, long amount) {
        log.info("[NOTIF] group {} dormancy auto-returned L${}", groupId, amount);
    }

    /** Common per-recipient publish path for realty group notifications. */
    private void publishOne(long userId, NotificationCategory category,
                            String title, String body, Map<String, Object> data) {
        notificationService.publish(new NotificationEvent(
            userId, category, title, body, data, /* coalesceKey */ null));
    }

    private static List<String> toNames(Set<RealtyGroupPermission> perms) {
        List<String> names = new ArrayList<>(perms.size());
        for (RealtyGroupPermission p : perms) names.add(p.name());
        return names;
    }
}
