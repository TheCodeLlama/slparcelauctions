package com.slparcelauctions.backend.bot;

public enum BotTaskStatus {
    /** Claimable (VERIFY) or due (MONITOR_* where next_run_at <= now). */
    PENDING,
    /**
     * Claimed by a bot. There is NO automatic timeout sweep; a bot that
     * crashes or disconnects mid-task leaves its row IN_PROGRESS permanently.
     * Use the admin re-enqueue endpoint (POST /api/v1/admin/parcel-scan/
     * {publicId}/reenqueue for SCAN_PARCEL) to recover orphaned scans. The
     * design is intentionally one-shot per scan; see the parcel-scanner spec.
     */
    IN_PROGRESS,
    /** VERIFY SUCCESS terminal. MONITOR_* rows never reach this state. */
    COMPLETED,
    /** VERIFY FAILURE, PENDING timeout, IN_PROGRESS timeout (VERIFY only), or superseded. */
    FAILED,
    /** MONITOR_* terminal — cancelled by a lifecycle hook on entity termination. */
    CANCELLED
}
