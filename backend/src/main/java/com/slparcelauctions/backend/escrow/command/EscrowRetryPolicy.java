package com.slparcelauctions.backend.escrow.command;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Shared retry schedule for terminal commands. Both the transport-failure
 * path in {@link com.slparcelauctions.backend.escrow.scheduler.TerminalCommandDispatcherTask}
 * and the terminal-reported-failure path in {@link TerminalCommandService#applyCallback}
 * must use the same curve so failure modes converge on the same stall
 * outcome at the final attempt.
 *
 * <p>The backoff ladder is config-driven via {@code slpa.escrow.command-retry-backoffs}
 * (a list of ISO-8601 durations). {@code MAX_ATTEMPTS} is derived as
 * {@code ladder.size() + 1}: after the last ladder entry is exhausted the
 * command stalls.
 */
@Component
public class EscrowRetryPolicy {

    private final List<Duration> backoff;
    private final int maxAttempts;

    public EscrowRetryPolicy(
            @Value("${slpa.escrow.command-retry-backoffs}") List<Duration> backoff) {
        this.backoff = List.copyOf(backoff);
        this.maxAttempts = backoff.size() + 1;
    }

    /** Total attempts before a command stalls (ladder size + 1). */
    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * Delay before the next attempt.
     *
     * @param attemptCount the number of attempts already made (i.e. the
     *                      attempt that just failed). Attempt 1 failed
     *                      returns the first ladder entry.
     */
    public Duration backoffFor(int attemptCount) {
        int idx = Math.min(attemptCount - 1, backoff.size() - 1);
        return backoff.get(Math.max(idx, 0));
    }
}
