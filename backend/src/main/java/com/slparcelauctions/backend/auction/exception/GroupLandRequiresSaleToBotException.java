package com.slparcelauctions.backend.auction.exception;

/**
 * Thrown when a seller triggers verification for a group-owned parcel with
 * any method other than {@link com.slparcelauctions.backend.auction.VerificationMethod#SALE_TO_BOT}.
 *
 * <p>Per Epic 03 sub-spec 2 §7.2, group-owned land cannot be verified via
 * the World API (returns the group UUID as owner, not the seller) nor via
 * the Rezzable flow (in-world object sees the group, not the individual).
 * Only the sale-to-bot path can transfer sentinel-priced group land to the
 * escrow bot. The {@code AuctionExceptionHandler} maps this to HTTP 422
 * Unprocessable Entity so the frontend can surface a targeted error with
 * a clear remediation ("Pick the Sale-to-bot method").
 */
public class GroupLandRequiresSaleToBotException extends RuntimeException {

    public GroupLandRequiresSaleToBotException() {
        super("Group-owned land requires the Sale-to-bot verification method.");
    }
}
