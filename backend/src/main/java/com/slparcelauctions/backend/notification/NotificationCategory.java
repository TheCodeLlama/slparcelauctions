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
    ESCROW_SELL_TO_SET(NotificationGroup.ESCROW),
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
    LISTING_CANCELLED_DURING_ESCROW(NotificationGroup.LISTING_STATUS),
    LISTING_WARNED(NotificationGroup.LISTING_STATUS),
    REVIEW_RECEIVED(NotificationGroup.REVIEWS),
    REVIEW_RESPONSE_WINDOW_CLOSING(NotificationGroup.REVIEWS),
    SYSTEM_ANNOUNCEMENT(NotificationGroup.SYSTEM),
    DISPUTE_FILED_AGAINST_SELLER(NotificationGroup.ESCROW),
    DISPUTE_RESOLVED(NotificationGroup.ESCROW),
    RECONCILIATION_MISMATCH(NotificationGroup.ADMIN_OPS),
    WITHDRAWAL_COMPLETED(NotificationGroup.ADMIN_OPS),
    WITHDRAWAL_FAILED(NotificationGroup.ADMIN_OPS),
    WALLET_WITHDRAWAL_COMPLETED(NotificationGroup.SYSTEM),
    WALLET_WITHDRAWAL_REVERSED(NotificationGroup.SYSTEM),
    WALLET_ADJUSTED(NotificationGroup.SYSTEM),
    WALLET_FROZEN(NotificationGroup.SYSTEM),
    WALLET_UNFROZEN(NotificationGroup.SYSTEM),
    WALLET_PENALTY_FORGIVEN(NotificationGroup.SYSTEM),
    WALLET_DORMANCY_RESET(NotificationGroup.SYSTEM),
    WALLET_TERMS_CLEARED(NotificationGroup.SYSTEM),
    WITHDRAWAL_FORCE_COMPLETED(NotificationGroup.SYSTEM),
    WITHDRAWAL_FORCE_FAILED(NotificationGroup.SYSTEM),

    // Realty groups -- lifecycle (spec section 8). Email default ON, SL IM default OFF,
    // in-app feed always (driven by NotificationGroup.REALTY_GROUP preferences).
    REALTY_GROUP_INVITATION_SENT(NotificationGroup.REALTY_GROUP),
    REALTY_GROUP_INVITATION_ACCEPTED(NotificationGroup.REALTY_GROUP),
    REALTY_GROUP_INVITATION_DECLINED(NotificationGroup.REALTY_GROUP),
    REALTY_GROUP_INVITATION_EXPIRED(NotificationGroup.REALTY_GROUP),
    REALTY_GROUP_MEMBER_REMOVED(NotificationGroup.REALTY_GROUP),
    REALTY_GROUP_MEMBER_LEFT(NotificationGroup.REALTY_GROUP),
    REALTY_GROUP_LEADERSHIP_TRANSFERRED(NotificationGroup.REALTY_GROUP),
    REALTY_GROUP_DISSOLVED(NotificationGroup.REALTY_GROUP),
    REALTY_GROUP_PERMISSIONS_CHANGED(NotificationGroup.REALTY_GROUP),

    // Realty groups -- admin moderation (sub-project F section 8, section 9). Fired to every member
    // when an admin suspends/bans a group (and when the suspension is lifted).
    REALTY_GROUP_SUSPENDED(NotificationGroup.REALTY_GROUP),
    REALTY_GROUP_UNSUSPENDED(NotificationGroup.REALTY_GROUP),

    // Realty groups -- SL group drift detected by the periodic reverify task
    // (sub-project F section 13.2). Routes to the realty group's leader so they can
    // re-register or contact admin. Drift reasons: FOUNDER_CHANGED,
    // GROUP_NOT_FOUND, FETCH_FAILED_REPEATEDLY.
    REALTY_GROUP_SL_GROUP_DRIFT_DETECTED(NotificationGroup.REALTY_GROUP),

    // Realty groups -- admin-side notification when a group's open report count
    // crosses the configured threshold (sub-project G spec section 12). Fan-out target
    // is the set of admin users (Role.ADMIN), not group members. One-shot per
    // cycle: re-armed once openReportCount returns to 0.
    GROUP_REPORT_THRESHOLD_REACHED(NotificationGroup.ADMIN_OPS),

    // Coupons -- fired by CouponService.createGrant when a non-REDEMPTION grant
    // lands and the parent coupon's notifyOnGrant flag is on. SYSTEM group so it
    // bypasses per-group SL IM preferences (material change to the user's wallet
    // privileges, same posture as wallet admin ops). REDEMPTION grants are
    // silent -- the user just typed the code so they know.
    COUPON_GRANTED(NotificationGroup.SYSTEM),

    // Customer support tickets. User-facing categories (admin replied, resolved)
    // route through SYSTEM so the SL IM dispatcher bypasses per-group prefs --
    // a support reply is a material response to a user-initiated thread and
    // should be hard to miss. Admin-facing categories (opened, user replied)
    // fan out to every admin via the in-app feed only; admins get the queue
    // page + sidebar badge for browsing, no IM spam.
    SUPPORT_TICKET_ADMIN_REPLIED(NotificationGroup.SYSTEM),
    SUPPORT_TICKET_RESOLVED(NotificationGroup.SYSTEM),
    SUPPORT_TICKET_OPENED(NotificationGroup.SYSTEM),
    SUPPORT_TICKET_USER_REPLIED(NotificationGroup.SYSTEM);

    private final NotificationGroup group;

    NotificationCategory(NotificationGroup group) {
        this.group = group;
    }

    public NotificationGroup getGroup() {
        return group;
    }
}
