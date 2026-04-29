package com.slparcelauctions.backend.auction.fraud;

/**
 * Reasons a fraud flag can be raised during ownership monitoring. See spec §8.2
 * (ownership-monitoring outcomes) and §8.3 (state transitions).
 */
public enum FraudFlagReason {
    OWNERSHIP_CHANGED_TO_UNKNOWN,
    PARCEL_DELETED_OR_MERGED,
    WORLD_API_FAILURE_THRESHOLD,
    /**
     * Raised when an escrow payment arrives from an avatar UUID that does not
     * match the auction winner's registered {@code sl_avatar_uuid}. The
     * terminal refunds the L$, the escrow stays in {@code ESCROW_PENDING}, and
     * the flag gives the Epic 10 admin dashboard a review handle. See spec
     * §4 / §13.2.
     */
    ESCROW_WRONG_PAYER,
    /**
     * Raised by the escrow ownership monitor (spec §4.5) when a
     * {@code TRANSFER_PENDING} escrow's World API owner is neither the seller
     * nor the auction winner. The escrow is frozen, refund queued, and the
     * admin dashboard can review the observed owner UUID from the evidence
     * payload.
     */
    ESCROW_UNKNOWN_OWNER,
    /**
     * Raised by the escrow ownership monitor when the parcel's World API page
     * 404s (deleted, merged, or returned to Linden Lab) while the escrow is
     * still in {@code TRANSFER_PENDING}. The escrow is frozen and the refund
     * pipeline kicks in.
     */
    ESCROW_PARCEL_DELETED,
    /**
     * Raised by the escrow ownership monitor after
     * {@code slpa.escrow.ownershipApiFailureThreshold} consecutive World API
     * failures on the same escrow. The freeze protects the transfer window
     * from stalling indefinitely on a persistently unreachable upstream.
     */
    ESCROW_WORLD_API_FAILURE,
    /**
     * Raised by the bot auction monitor (Epic 06) when observed AuthBuyerID
     * no longer equals the primary escrow account UUID during an active
     * BOT-tier auction. Seller has revoked the sale-to-bot setting — treated
     * as fraud because a bot-verified listing depends on that setting.
     */
    BOT_AUTH_BUYER_REVOKED,
    /**
     * Raised by the bot auction monitor when observed SalePrice no longer
     * matches the sentinel. Seller has retargeted or lowered the price —
     * treated as fraud on an active BOT-tier auction.
     */
    BOT_PRICE_DRIFT,
    /**
     * Raised by the bot auction or escrow monitor when observed OwnerID no
     * longer matches the expected seller (auction) or is neither seller nor
     * winner (escrow). Treated as fraud in both flows.
     */
    BOT_OWNERSHIP_CHANGED,
    /**
     * Raised when the bot has received ACCESS_DENIED on a parcel for
     * slpa.bot.access-denied-streak-threshold consecutive monitor cycles
     * (default 3). Seller has revoked bot access — treated as fraud on
     * active auctions; the escrow flow uses markReviewRequired instead.
     */
    BOT_ACCESS_REVOKED,
    /**
     * Raised by the post-cancel ownership watcher (Epic 08 sub-spec 2 §6) when
     * a CANCELLED auction's parcel ownership flips to a non-seller avatar
     * within {@code slpa.cancellation.post-cancel-watch-hours} of cancellation
     * (default 48h). Strong signal of an off-platform deal.
     */
    CANCEL_AND_SELL
}
