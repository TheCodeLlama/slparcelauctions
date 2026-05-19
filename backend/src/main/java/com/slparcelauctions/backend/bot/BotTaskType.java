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
    VERIFY_SELL_TO,

    /**
     * Escrow Buy-Parcel ownership verification. Dispatched when the seller or
     * winner clicks "Verify purchase" on the escrow page during the Buy-Parcel
     * sub-phase — the bot teleports to the parcel and reports the live owner
     * UUID, so the backend can confirm-transfer, consume a role attempt
     * (definitive negative: seller/group still owns), or freeze the escrow
     * (stranger / parcel deleted). The 30-min background ownership polling
     * keeps using the cheaper World API path; this task type covers only the
     * user-initiated manual verify so the seller and winner can grey-out the
     * button while a bot is en-route. {@code resultData} carries
     * {@code requestingRole} ("SELLER" / "BUYER") so the callback consumes the
     * correct attempt counter.
     */
    VERIFY_BUY_OWNER
}
