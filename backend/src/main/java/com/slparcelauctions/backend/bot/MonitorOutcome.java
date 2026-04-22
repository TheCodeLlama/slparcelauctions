package com.slparcelauctions.backend.bot;

/**
 * Observation-only vocabulary emitted by the bot worker's monitor handler.
 * Deliberately omits {@code FRAUD_DETECTED} — fraud interpretation is the
 * backend dispatcher's responsibility. See Epic 06 spec §2.5, §6.
 */
public enum MonitorOutcome {
    /** Everything still matches expected values. MONITOR_AUCTION only. */
    ALL_GOOD,
    /** Observed AuthBuyerID differs from expected. */
    AUTH_BUYER_CHANGED,
    /** Observed SalePrice differs from expected. */
    PRICE_MISMATCH,
    /** Observed OwnerID differs from expected (auction) or is neither seller nor winner (escrow). */
    OWNER_CHANGED,
    /** Bot could not enter the parcel (access list, ban, estate-level block). */
    ACCESS_DENIED,
    /** Escrow: observed owner == expected winner. */
    TRANSFER_COMPLETE,
    /** Escrow: seller has configured sale-to-winner; price below threshold. */
    TRANSFER_READY,
    /** Escrow: no relevant change — neither transfer nor fraud. */
    STILL_WAITING
}
