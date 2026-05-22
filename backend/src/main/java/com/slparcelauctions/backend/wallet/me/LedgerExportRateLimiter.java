package com.slparcelauctions.backend.wallet.me;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * In-memory rate limiter for the CSV export endpoint. One export per user
 * per {@code slpa.wallet.ledger-export-rate-limit} window. Process-local;
 * can move to Redis if/when we go multi-instance.
 */
@Component
public class LedgerExportRateLimiter {

    private final Duration window;
    private final Clock clock;
    private final ConcurrentHashMap<Long, Instant> lastExportAt = new ConcurrentHashMap<>();

    public LedgerExportRateLimiter(
            Clock clock,
            @Value("${slpa.wallet.ledger-export-rate-limit}") Duration window) {
        this.clock = clock;
        this.window = window;
    }

    /**
     * @return true if allowed; false if blocked by rate limit
     */
    public boolean tryAcquire(Long userId) {
        Instant now = clock.instant();
        Instant prior = lastExportAt.get(userId);
        if (prior != null && Duration.between(prior, now).compareTo(window) < 0) {
            return false;
        }
        lastExportAt.put(userId, now);
        return true;
    }

    /**
     * Test helper: clear the rate-limit window for all users. Used by slice
     * tests to ensure a clean state between cases. Production callers should
     * not invoke this.
     */
    public void resetForTesting() {
        lastExportAt.clear();
    }
}
