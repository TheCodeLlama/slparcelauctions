package com.slparcelauctions.backend.wallet.me;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * In-memory rate limiter for the CSV export endpoint. 1 export per user
 * per 60 seconds. Process-local; can move to Redis if/when we go
 * multi-instance.
 */
@Component
@RequiredArgsConstructor
public class LedgerExportRateLimiter {

    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final Clock clock;
    private final ConcurrentHashMap<Long, Instant> lastExportAt = new ConcurrentHashMap<>();

    /**
     * @return true if allowed; false if blocked by rate limit
     */
    public boolean tryAcquire(Long userId) {
        Instant now = clock.instant();
        Instant prior = lastExportAt.get(userId);
        if (prior != null && Duration.between(prior, now).compareTo(WINDOW) < 0) {
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
