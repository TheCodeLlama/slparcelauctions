package com.slparcelauctions.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;

class NotificationDataBuilderTest {

    private static final long AUCTION_ID = 42L;
    private static final long ESCROW_ID = 100L;
    private static final String PARCEL_NAME = "Hampton Hills";
    private static final OffsetDateTime DEADLINE = OffsetDateTime.parse("2026-04-28T18:00:00Z");

    // ── Bidding ───────────────────────────────────────────────────────────────

    @Test
    void outbid_writesExpectedKeys() {
        Map<String, Object> d = NotificationDataBuilder.outbid(
                AUCTION_ID, PARCEL_NAME, 5200L, true, DEADLINE);

        assertThat(d).containsOnlyKeys("auctionId", "parcelName", "currentBidL", "isProxyOutbid", "endsAt");
        assertThat(d).containsEntry("auctionId", AUCTION_ID);
        assertThat(d).containsEntry("currentBidL", 5200L);
        assertThat(d).containsEntry("isProxyOutbid", true);
        assertThat(d.get("endsAt")).isEqualTo(DEADLINE.toString());
    }

    @Test
    void outbid_nonProxyVariant_setsIsProxyOutbidFalse() {
        Map<String, Object> d = NotificationDataBuilder.outbid(
                AUCTION_ID, PARCEL_NAME, 3000L, false, DEADLINE);

        assertThat(d).containsEntry("isProxyOutbid", false);
        assertThat(d).containsEntry("currentBidL", 3000L);
    }

    @Test
    void proxyExhausted_writesExpectedKeys() {
        Map<String, Object> d = NotificationDataBuilder.proxyExhausted(
                AUCTION_ID, PARCEL_NAME, 8000L, DEADLINE);

        assertThat(d).containsOnlyKeys("auctionId", "parcelName", "proxyMaxL", "endsAt");
        assertThat(d).containsEntry("proxyMaxL", 8000L);
        assertThat(d.get("endsAt")).isEqualTo(DEADLINE.toString());
    }

    // ── Auction result ────────────────────────────────────────────────────────

    @Test
    void auctionWon_writesWinningBidL() {
        Map<String, Object> d = NotificationDataBuilder.auctionWon(AUCTION_ID, PARCEL_NAME, 12000L);

        assertThat(d).containsOnlyKeys("auctionId", "parcelName", "winningBidL");
        assertThat(d).containsEntry("winningBidL", 12000L);
    }

    @Test
    void auctionLost_writesWinningBidL() {
        Map<String, Object> d = NotificationDataBuilder.auctionLost(AUCTION_ID, PARCEL_NAME, 11000L);

        assertThat(d).containsOnlyKeys("auctionId", "parcelName", "winningBidL");
        assertThat(d).containsEntry("winningBidL", 11000L);
    }

    @Test
    void auctionEndedSold_writesWinningBidL() {
        Map<String, Object> d = NotificationDataBuilder.auctionEndedSold(AUCTION_ID, PARCEL_NAME, 9500L);

        assertThat(d).containsOnlyKeys("auctionId", "parcelName", "winningBidL");
        assertThat(d).containsEntry("winningBidL", 9500L);
    }

    @Test
    void auctionEndedReserveNotMet_writesHighestBidL() {
        Map<String, Object> d = NotificationDataBuilder.auctionEndedReserveNotMet(AUCTION_ID, PARCEL_NAME, 4000L);

        assertThat(d).containsOnlyKeys("auctionId", "parcelName", "highestBidL");
        assertThat(d).containsEntry("highestBidL", 4000L);
    }

    @Test
    void auctionEndedNoBids_writesMinimalKeys() {
        Map<String, Object> d = NotificationDataBuilder.auctionEndedNoBids(AUCTION_ID, PARCEL_NAME);

        assertThat(d).containsOnlyKeys("auctionId", "parcelName");
    }

    @Test
    void auctionEndedBoughtNow_writesBuyNowL() {
        Map<String, Object> d = NotificationDataBuilder.auctionEndedBoughtNow(AUCTION_ID, PARCEL_NAME, 20000L);

        assertThat(d).containsOnlyKeys("auctionId", "parcelName", "buyNowL");
        assertThat(d).containsEntry("buyNowL", 20000L);
    }

    // ── Escrow ────────────────────────────────────────────────────────────────

    @Test
    void escrowFunded_writesTransferDeadline() {
        Map<String, Object> d = NotificationDataBuilder.escrowFunded(
                AUCTION_ID, ESCROW_ID, PARCEL_NAME, DEADLINE);

        assertThat(d).containsKeys("auctionId", "escrowId", "parcelName", "transferDeadline");
        assertThat(d).containsEntry("escrowId", ESCROW_ID);
        assertThat(d.get("transferDeadline")).isEqualTo(DEADLINE.toString());
    }

    @Test
    void escrowTransferConfirmed_writesBaseEscrowKeys() {
        Map<String, Object> d = NotificationDataBuilder.escrowTransferConfirmed(
                AUCTION_ID, ESCROW_ID, PARCEL_NAME);

        assertThat(d).containsOnlyKeys("auctionId", "parcelName", "escrowId");
    }

    @Test
    void escrowPayout_writesPayoutL() {
        Map<String, Object> d = NotificationDataBuilder.escrowPayout(
                AUCTION_ID, ESCROW_ID, PARCEL_NAME, 15000L);

        assertThat(d).containsKeys("auctionId", "escrowId", "parcelName", "payoutL");
        assertThat(d).containsEntry("payoutL", 15000L);
    }

    @Test
    void escrowExpired_writesBaseEscrowKeys() {
        Map<String, Object> d = NotificationDataBuilder.escrowExpired(AUCTION_ID, ESCROW_ID, PARCEL_NAME);

        assertThat(d).containsOnlyKeys("auctionId", "parcelName", "escrowId");
    }

    @Test
    void escrowDisputed_writesReasonCategory() {
        Map<String, Object> d = NotificationDataBuilder.escrowDisputed(
                AUCTION_ID, ESCROW_ID, PARCEL_NAME, "SELLER_NO_TRANSFER");

        assertThat(d).containsKeys("auctionId", "escrowId", "parcelName", "reasonCategory");
        assertThat(d).containsEntry("reasonCategory", "SELLER_NO_TRANSFER");
    }

    @Test
    void escrowFrozen_writesReason() {
        Map<String, Object> d = NotificationDataBuilder.escrowFrozen(
                AUCTION_ID, ESCROW_ID, PARCEL_NAME, "Suspected fraud");

        assertThat(d).containsKeys("auctionId", "escrowId", "parcelName", "reason");
        assertThat(d).containsEntry("reason", "Suspected fraud");
    }

    @Test
    void escrowPayoutStalled_writesBaseEscrowKeys() {
        Map<String, Object> d = NotificationDataBuilder.escrowPayoutStalled(AUCTION_ID, ESCROW_ID, PARCEL_NAME);

        assertThat(d).containsOnlyKeys("auctionId", "parcelName", "escrowId");
    }

    @Test
    void escrowTransferReminder_writesTransferDeadline() {
        Map<String, Object> d = NotificationDataBuilder.escrowTransferReminder(
                AUCTION_ID, ESCROW_ID, PARCEL_NAME, DEADLINE);

        assertThat(d).containsKeys("auctionId", "escrowId", "parcelName", "transferDeadline");
        assertThat(d.get("transferDeadline")).isEqualTo(DEADLINE.toString());
    }

    // ── Listing status ────────────────────────────────────────────────────────

    @Test
    void listingVerified_writesMinimalKeys() {
        Map<String, Object> d = NotificationDataBuilder.listingVerified(AUCTION_ID, PARCEL_NAME);

        assertThat(d).containsOnlyKeys("auctionId", "parcelName");
    }

    @Test
    void listingSuspended_writesReason() {
        Map<String, Object> d = NotificationDataBuilder.listingSuspended(
                AUCTION_ID, PARCEL_NAME, "Ownership verification failed");

        assertThat(d).containsKeys("auctionId", "parcelName", "reason");
        assertThat(d).containsEntry("reason", "Ownership verification failed");
    }

    @Test
    void listingReviewRequired_writesReason() {
        Map<String, Object> d = NotificationDataBuilder.listingReviewRequired(
                AUCTION_ID, PARCEL_NAME, "Disputed boundary");

        assertThat(d).containsKeys("auctionId", "parcelName", "reason");
        assertThat(d).containsEntry("reason", "Disputed boundary");
    }

    @Test
    void listingCancelledBySeller_writesReasonField() {
        Map<String, Object> d = NotificationDataBuilder.listingCancelledBySeller(
                AUCTION_ID, PARCEL_NAME, "ownership lost");

        assertThat(d).containsOnlyKeys("auctionId", "parcelName", "reason");
        assertThat(d).containsEntry("reason", "ownership lost");
    }

    // ── Reviews ───────────────────────────────────────────────────────────────

    @Test
    void reviewReceived_writesReviewIdAndRating() {
        Map<String, Object> d = NotificationDataBuilder.reviewReceived(77L, AUCTION_ID, PARCEL_NAME, 5);

        assertThat(d).containsKeys("auctionId", "parcelName", "reviewId", "rating");
        assertThat(d).containsEntry("reviewId", 77L);
        assertThat(d).containsEntry("rating", 5);
    }
}
