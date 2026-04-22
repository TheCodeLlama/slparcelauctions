package com.slparcelauctions.backend.escrow.command;

/**
 * Lifecycle state of a {@link TerminalCommand} row. {@code QUEUED} is the
 * initial landing state; the dispatcher flips to {@code IN_FLIGHT} when it
 * successfully POSTs to a terminal and stamps {@code dispatchedAt}. The
 * terminal's async callback moves the row to {@code COMPLETED} on success or
 * {@code FAILED} on a reported failure. A terminal that never calls back is
 * detected by the dispatcher's IN_FLIGHT staleness sweep and requeued as
 * {@code FAILED} so the next tick picks it up.
 */
public enum TerminalCommandStatus {
    QUEUED,
    IN_FLIGHT,
    COMPLETED,
    FAILED
}
