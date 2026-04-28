package com.slparcelauctions.backend.notification;

import java.time.OffsetDateTime;
import java.util.List;

public interface NotificationPublisher {

    // Bidding
    void outbid(long bidderUserId, long auctionId, String parcelName,
                long currentBidL, boolean isProxyOutbid, OffsetDateTime endsAt);
    void proxyExhausted(long bidderUserId, long auctionId, String parcelName,
                        long proxyMaxL, OffsetDateTime endsAt);

    // Auction result — winner/loser
    void auctionWon(long winnerUserId, long auctionId, String parcelName, long winningBidL);
    void auctionLost(long bidderUserId, long auctionId, String parcelName, long winningBidL);

    // Auction result — seller's perspective
    void auctionEndedSold(long sellerUserId, long auctionId, String parcelName, long winningBidL);
    void auctionEndedReserveNotMet(long sellerUserId, long auctionId, String parcelName, long highestBidL);
    void auctionEndedNoBids(long sellerUserId, long auctionId, String parcelName);
    void auctionEndedBoughtNow(long sellerUserId, long auctionId, String parcelName, long buyNowL);

    // Escrow lifecycle
    void escrowFunded(long sellerUserId, long auctionId, long escrowId,
                      String parcelName, OffsetDateTime transferDeadline);
    void escrowTransferConfirmed(long userId, long auctionId, long escrowId, String parcelName);
    void escrowPayout(long sellerUserId, long auctionId, long escrowId,
                      String parcelName, long payoutL);
    void escrowExpired(long userId, long auctionId, long escrowId, String parcelName);
    void escrowDisputed(long userId, long auctionId, long escrowId,
                        String parcelName, String reasonCategory);
    void escrowFrozen(long userId, long auctionId, long escrowId,
                      String parcelName, String reason);
    void escrowPayoutStalled(long sellerUserId, long auctionId, long escrowId, String parcelName);
    void escrowTransferReminder(long sellerUserId, long auctionId, long escrowId,
                                 String parcelName, OffsetDateTime transferDeadline);

    // Listing status — seller-facing
    void listingVerified(long sellerUserId, long auctionId, String parcelName);
    void listingSuspended(long sellerUserId, long auctionId, String parcelName, String reason);
    void listingReinstated(long sellerUserId, long auctionId, String parcelName, OffsetDateTime newEndsAt);
    void listingReviewRequired(long sellerUserId, long auctionId, String parcelName, String reason);

    // Listing status — admin-facing actions
    void listingRemovedByAdmin(long sellerUserId, long auctionId, String parcelName, String reason);
    void listingWarned(long sellerUserId, long auctionId, String parcelName, String notes);

    // Reviews
    void reviewReceived(long revieweeUserId, long reviewId, long auctionId,
                        String parcelName, int rating);

    // Dispute
    void disputeFiledAgainstSeller(long sellerUserId, long auctionId, long escrowId,
                                    String parcelName, long amountL,
                                    String reasonCategory);

    // Dispute resolution
    void disputeResolved(long recipientUserId, String role,
                          long auctionId, long escrowId,
                          String parcelName, long amountL,
                          com.slparcelauctions.backend.admin.disputes.AdminDisputeAction action,
                          boolean alsoCancelListing);

    // Fan-out (afterCommit batch — see §3.9)
    void listingCancelledBySellerFanout(long auctionId, List<Long> activeBidderUserIds,
                                         String parcelName, String reason);

    // Admin infrastructure
    void reconciliationMismatch(List<Long> adminUserIds, long drift, String date);

    // Admin withdrawals
    void withdrawalCompleted(long adminUserId, long amountL, String recipientUuid);
    void withdrawalFailed(long adminUserId, long amountL, String recipientUuid, String reason);
}
