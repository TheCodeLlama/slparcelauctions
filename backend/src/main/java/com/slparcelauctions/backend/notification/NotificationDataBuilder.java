package com.slparcelauctions.backend.notification;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-category builder methods for the notification {@code data} JSON
 * blob. The contract: each category writes a stable set of keys here,
 * and the frontend reads them via a discriminated union typed by
 * {@code category}. Adding a field to an existing category = one line
 * here; no schema migration. Type-safe at the publish call site (named
 * parameters) without sealing the persistence type.
 */
public final class NotificationDataBuilder {

    private NotificationDataBuilder() {}

    public static Map<String, Object> outbid(long auctionId, String parcelName,
                                              long currentBidL, boolean isProxyOutbid,
                                              OffsetDateTime endsAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("auctionId", auctionId);
        m.put("parcelName", parcelName);
        m.put("currentBidL", currentBidL);
        m.put("isProxyOutbid", isProxyOutbid);
        m.put("endsAt", endsAt.toString());
        return m;
    }

    public static Map<String, Object> proxyExhausted(long auctionId, String parcelName,
                                                      long proxyMaxL, OffsetDateTime endsAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("auctionId", auctionId);
        m.put("parcelName", parcelName);
        m.put("proxyMaxL", proxyMaxL);
        m.put("endsAt", endsAt.toString());
        return m;
    }

    public static Map<String, Object> auctionWon(long auctionId, String parcelName, long winningBidL) {
        return base(auctionId, parcelName, "winningBidL", winningBidL);
    }

    public static Map<String, Object> auctionLost(long auctionId, String parcelName, long winningBidL) {
        return base(auctionId, parcelName, "winningBidL", winningBidL);
    }

    public static Map<String, Object> auctionEndedSold(long auctionId, String parcelName, long winningBidL) {
        return base(auctionId, parcelName, "winningBidL", winningBidL);
    }

    public static Map<String, Object> auctionEndedReserveNotMet(long auctionId, String parcelName, long highestBidL) {
        return base(auctionId, parcelName, "highestBidL", highestBidL);
    }

    public static Map<String, Object> auctionEndedNoBids(long auctionId, String parcelName) {
        return base(auctionId, parcelName);
    }

    public static Map<String, Object> auctionEndedBoughtNow(long auctionId, String parcelName, long buyNowL) {
        return base(auctionId, parcelName, "buyNowL", buyNowL);
    }

    public static Map<String, Object> escrowFunded(long auctionId, long escrowId, String parcelName,
                                                    OffsetDateTime transferDeadline) {
        Map<String, Object> m = baseEscrow(auctionId, escrowId, parcelName);
        m.put("transferDeadline", transferDeadline.toString());
        return m;
    }

    public static Map<String, Object> escrowTransferConfirmed(long auctionId, long escrowId, String parcelName) {
        return baseEscrow(auctionId, escrowId, parcelName);
    }

    public static Map<String, Object> escrowPayout(long auctionId, long escrowId, String parcelName, long payoutL) {
        Map<String, Object> m = baseEscrow(auctionId, escrowId, parcelName);
        m.put("payoutL", payoutL);
        return m;
    }

    public static Map<String, Object> escrowExpired(long auctionId, long escrowId, String parcelName) {
        return baseEscrow(auctionId, escrowId, parcelName);
    }

    public static Map<String, Object> escrowDisputed(long auctionId, long escrowId, String parcelName, String reasonCategory) {
        Map<String, Object> m = baseEscrow(auctionId, escrowId, parcelName);
        m.put("reasonCategory", reasonCategory);
        return m;
    }

    public static Map<String, Object> escrowFrozen(long auctionId, long escrowId, String parcelName, String reason) {
        Map<String, Object> m = baseEscrow(auctionId, escrowId, parcelName);
        m.put("reason", reason);
        return m;
    }

    public static Map<String, Object> escrowPayoutStalled(long auctionId, long escrowId, String parcelName) {
        return baseEscrow(auctionId, escrowId, parcelName);
    }

    public static Map<String, Object> escrowTransferReminder(long auctionId, long escrowId, String parcelName,
                                                              OffsetDateTime transferDeadline) {
        Map<String, Object> m = baseEscrow(auctionId, escrowId, parcelName);
        m.put("transferDeadline", transferDeadline.toString());
        return m;
    }

    public static Map<String, Object> listingVerified(long auctionId, String parcelName) {
        return base(auctionId, parcelName);
    }

    public static Map<String, Object> listingSuspended(long auctionId, String parcelName, String reason) {
        Map<String, Object> m = base(auctionId, parcelName);
        m.put("reason", reason);
        return m;
    }

    public static Map<String, Object> listingReinstated(long auctionId, String parcelName, OffsetDateTime newEndsAt) {
        Map<String, Object> m = base(auctionId, parcelName);
        m.put("newEndsAt", newEndsAt == null ? null : newEndsAt.toString());
        return m;
    }

    public static Map<String, Object> listingReviewRequired(long auctionId, String parcelName, String reason) {
        Map<String, Object> m = base(auctionId, parcelName);
        m.put("reason", reason);
        return m;
    }

    public static Map<String, Object> reviewReceived(long reviewId, long auctionId, String parcelName, int rating) {
        Map<String, Object> m = base(auctionId, parcelName);
        m.put("reviewId", reviewId);
        m.put("rating", rating);
        return m;
    }

    public static Map<String, Object> listingCancelledBySeller(long auctionId, String parcelName, String reason) {
        Map<String, Object> m = base(auctionId, parcelName);
        m.put("reason", reason);
        return m;
    }

    public static Map<String, Object> listingRemovedByAdmin(long auctionId, String parcelName, String reason) {
        Map<String, Object> m = base(auctionId, parcelName);
        m.put("reason", reason);
        return m;
    }

    private static Map<String, Object> base(long auctionId, String parcelName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("auctionId", auctionId);
        m.put("parcelName", parcelName);
        return m;
    }

    private static Map<String, Object> base(long auctionId, String parcelName, String extraKey, Object extraValue) {
        Map<String, Object> m = base(auctionId, parcelName);
        m.put(extraKey, extraValue);
        return m;
    }

    private static Map<String, Object> baseEscrow(long auctionId, long escrowId, String parcelName) {
        Map<String, Object> m = base(auctionId, parcelName);
        m.put("escrowId", escrowId);
        return m;
    }
}
