package com.slparcelauctions.backend.bot;

/**
 * Bot worker task type. After the ownership-only verification refactor
 * (spec 2026-05-16) the VERIFY / MONITOR_AUCTION / MONITOR_ESCROW values
 * are retired: verification + active-state monitoring run from the
 * backend via the SL World API, and the bot worker no longer participates
 * in those flows. The enum is intentionally kept empty as future-extension
 * scaffolding (e.g. SL IM dispatch, group-fund withdrawals) -- the
 * BotTask + BotTaskService + BotTaskRepository surfaces stay in place
 * so a future task type can plug in without re-introducing the JPA
 * mapping + claim plumbing from scratch.
 */
public enum BotTaskType {
    /**
     * Escrow Set-Sell-To verification (spec 2026-05-17). The bot teleports
     * to the parcel and reads {@code ParcelSnapshot.AuthBuyerId} /
     * {@code SalePrice} — data the SL World API cannot see. This single-purpose
     * recurring task is NOT a revival of the retired multi-check
     * {@code MONITOR_ESCROW}. Task creation at funding + the result callback
     * are wired in Phase 3; Phase 2 only references the type for the manual
     * "Verify Sell To" expedite path.
     */
    VERIFY_SELL_TO
}
