package com.slparcelauctions.backend.escrow.command;

import java.time.Duration;
import java.util.List;

/**
 * Shared retry schedule for terminal commands. Both the transport-failure
 * path in {@link com.slparcelauctions.backend.escrow.scheduler.TerminalCommandDispatcherTask}
 * and the terminal-reported-failure path in {@link TerminalCommandService#applyCallback}
 * must use the same curve so failure modes converge on the same stall
 * outcome at attempt 4.
 */
public final class EscrowRetryPolicy {

    private EscrowRetryPolicy() {}

    public static final List<Duration> BACKOFF = List.of(
            Duration.ofMinutes(1),  // after attempt 1 → retry at +1min
            Duration.ofMinutes(5),  // after attempt 2 → retry at +5min
            Duration.ofMinutes(15)  // after attempt 3 → retry at +15min
    );

    public static final int MAX_ATTEMPTS = BACKOFF.size() + 1;  // = 4; after 4th failure → stall

    public static Duration backoffFor(int attemptCount) {
        // attemptCount is the number of attempts already made (i.e. the
        // attempt that just failed). Returns the delay before the next
        // attempt. Attempt 1 failed → BACKOFF[0] = 1min.
        int idx = Math.min(attemptCount - 1, BACKOFF.size() - 1);
        return BACKOFF.get(Math.max(idx, 0));
    }
}
