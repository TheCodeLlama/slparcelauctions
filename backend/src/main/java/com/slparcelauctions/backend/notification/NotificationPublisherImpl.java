package com.slparcelauctions.backend.notification;

import com.slparcelauctions.backend.notification.NotificationDao.UpsertResult;
import com.slparcelauctions.backend.notification.dto.NotificationDto;
import com.slparcelauctions.backend.notification.slim.SlImChannelDispatcher;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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

    public NotificationPublisherImpl(
            NotificationService notificationService,
            NotificationDao notificationDao,
            NotificationWsBroadcasterPort wsBroadcaster,
            @Qualifier("requiresNewTxTemplate") TransactionTemplate requiresNewTxTemplate,
            SlImChannelDispatcher slImChannelDispatcher) {
        this.notificationService = notificationService;
        this.notificationDao = notificationDao;
        this.wsBroadcaster = wsBroadcaster;
        this.requiresNewTxTemplate = requiresNewTxTemplate;
        this.slImChannelDispatcher = slImChannelDispatcher;
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
        String body = "Your payout is delayed — we're investigating. No action needed from you.";
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
        String body = "Your listing has been removed by SLPA staff. Reason: " + reason + ".";
        notificationService.publish(new NotificationEvent(
            sellerUserId, NotificationCategory.LISTING_REMOVED_BY_ADMIN, title, body,
            NotificationDataBuilder.listingRemovedByAdmin(auctionId, parcelName, reason),
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
            result.id(),
            NotificationCategory.LISTING_CANCELLED_BY_SELLER,
            NotificationGroup.LISTING_STATUS,
            title, body, data, false,
            result.createdAt(), result.updatedAt()
        );
    }
}
