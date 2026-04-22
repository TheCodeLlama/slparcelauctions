package com.slparcelauctions.backend.bot;

public enum BotTaskType {
    /** One-shot Method C verification; terminal on callback. */
    VERIFY,
    /** Recurring auction monitoring; re-armed every slpa.bot.monitor-auction-interval (default PT30M). */
    MONITOR_AUCTION,
    /** Recurring escrow monitoring; re-armed every slpa.bot.monitor-escrow-interval (default PT15M). */
    MONITOR_ESCROW
}
