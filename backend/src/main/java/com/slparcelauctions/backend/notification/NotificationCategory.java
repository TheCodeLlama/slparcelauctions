package com.slparcelauctions.backend.notification;

public enum NotificationCategory {
    OUTBID(NotificationGroup.BIDDING),
    PROXY_EXHAUSTED(NotificationGroup.BIDDING),
    AUCTION_WON(NotificationGroup.AUCTION_RESULT),
    AUCTION_LOST(NotificationGroup.AUCTION_RESULT),
    AUCTION_ENDED_SOLD(NotificationGroup.AUCTION_RESULT),
    AUCTION_ENDED_RESERVE_NOT_MET(NotificationGroup.AUCTION_RESULT),
    AUCTION_ENDED_NO_BIDS(NotificationGroup.AUCTION_RESULT),
    AUCTION_ENDED_BOUGHT_NOW(NotificationGroup.AUCTION_RESULT),
    ESCROW_FUNDED(NotificationGroup.ESCROW),
    ESCROW_TRANSFER_CONFIRMED(NotificationGroup.ESCROW),
    ESCROW_PAYOUT(NotificationGroup.ESCROW),
    ESCROW_EXPIRED(NotificationGroup.ESCROW),
    ESCROW_DISPUTED(NotificationGroup.ESCROW),
    ESCROW_FROZEN(NotificationGroup.ESCROW),
    ESCROW_PAYOUT_STALLED(NotificationGroup.ESCROW),
    ESCROW_TRANSFER_REMINDER(NotificationGroup.ESCROW),
    LISTING_VERIFIED(NotificationGroup.LISTING_STATUS),
    LISTING_SUSPENDED(NotificationGroup.LISTING_STATUS),
    LISTING_REINSTATED(NotificationGroup.LISTING_STATUS),
    LISTING_REVIEW_REQUIRED(NotificationGroup.LISTING_STATUS),
    LISTING_CANCELLED_BY_SELLER(NotificationGroup.LISTING_STATUS),
    LISTING_REMOVED_BY_ADMIN(NotificationGroup.LISTING_STATUS),
    LISTING_WARNED(NotificationGroup.LISTING_STATUS),
    REVIEW_RECEIVED(NotificationGroup.REVIEWS),
    SYSTEM_ANNOUNCEMENT(NotificationGroup.SYSTEM);

    private final NotificationGroup group;

    NotificationCategory(NotificationGroup group) {
        this.group = group;
    }

    public NotificationGroup getGroup() {
        return group;
    }
}
