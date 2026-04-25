package com.slparcelauctions.backend.escrow;

public enum EscrowTransactionType {
    AUCTION_ESCROW_PAYMENT,
    AUCTION_ESCROW_PAYOUT,
    AUCTION_ESCROW_REFUND,
    AUCTION_ESCROW_COMMISSION,
    LISTING_FEE_PAYMENT,
    LISTING_FEE_REFUND,
    /**
     * Penalty payment from a seller at a SLPA terminal, paying down their
     * {@code User.penaltyBalanceOwed} debt accrued from cancelled-with-bids
     * offenses (Epic 08 sub-spec 2 §4.6).
     */
    LISTING_PENALTY_PAYMENT
}
