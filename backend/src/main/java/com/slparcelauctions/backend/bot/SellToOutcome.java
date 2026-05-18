package com.slparcelauctions.backend.bot;

/**
 * Bot {@code VERIFY_SELL_TO} task classification result (spec §5.2). This is
 * the <b>frozen bot wire contract</b> — the .NET bot mirrors these names
 * byte-for-byte (Phase 7). Do not rename or reorder values.
 */
public enum SellToOutcome {
    SELL_TO_OK, OWNER_ALREADY_WINNER, SELL_TO_NOT_SET, WRONG_BUYER,
    PRICE_NOT_ZERO, ACCESS_DENIED, PARCEL_NOT_FOUND, BOT_ERROR
}
