package com.slparcelauctions.backend.auction.exception;

/**
 * Raised by {@code BidEligibilityService.assertCanBid} when the bidder is a current member
 * of the auction's {@code realty_group_id}. Mapped to {@code 403 GROUP_MEMBER_CANNOT_BID}
 * by {@code AuctionExceptionHandler}. Sibling to {@link SellerCannotBidException} — same
 * shape, distinct code so the frontend can render a group-aware message.
 */
public class GroupMemberCannotBidException extends RuntimeException {

    public GroupMemberCannotBidException() {
        super("Group members cannot bid on their group's listings.");
    }
}
