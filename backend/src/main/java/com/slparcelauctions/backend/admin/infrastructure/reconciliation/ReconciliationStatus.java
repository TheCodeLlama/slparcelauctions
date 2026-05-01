package com.slparcelauctions.backend.admin.infrastructure.reconciliation;

public enum ReconciliationStatus {
    /**
     * Observed L$ balance covers expected (escrow + wallet) within tolerance.
     */
    BALANCED,

    /**
     * Observed L$ balance differs from expected by more than tolerance.
     * The drift column captures the magnitude.
     */
    MISMATCH,

    /**
     * The run could not complete (no fresh terminal balance, transient
     * failure, etc).
     */
    ERROR,

    /**
     * Pre-check found that {@code users.reserved_lindens} (denorm) does not
     * match {@code SUM(bid_reservations.amount WHERE released_at IS NULL)}
     * (source). The main expected vs. observed comparison is aborted because
     * the expected number is wrong if denorms are wrong. See spec §11.1.
     */
    DENORM_DRIFT
}
