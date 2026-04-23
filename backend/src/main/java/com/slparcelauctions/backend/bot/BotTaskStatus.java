package com.slparcelauctions.backend.bot;

public enum BotTaskStatus {
    /** Claimable (VERIFY) or due (MONITOR_* where next_run_at <= now). */
    PENDING,
    /** Claimed; times out after slpa.bot-task.in-progress-timeout (default PT20M). */
    IN_PROGRESS,
    /** VERIFY SUCCESS terminal. MONITOR_* rows never reach this state. */
    COMPLETED,
    /** VERIFY FAILURE, PENDING timeout, IN_PROGRESS timeout (VERIFY only), or superseded. */
    FAILED,
    /** MONITOR_* terminal — cancelled by a lifecycle hook on entity termination. */
    CANCELLED
}
