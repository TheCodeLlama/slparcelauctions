package com.slparcelauctions.backend.wallet.me;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/**
 * Unit-level coverage for {@link LedgerExportRateLimiter}. Uses a small
 * mutable clock so we can drive the window boundaries deterministically.
 */
class LedgerExportRateLimiterTest {

    /**
     * Minimal mutable clock — wraps a {@link Instant} we can advance in
     * tests without touching the real wall clock.
     */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) { this.now = now; }

        void advanceSeconds(long seconds) { this.now = this.now.plusSeconds(seconds); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void firstCall_returnsTrue() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-30T12:00:00Z"));
        LedgerExportRateLimiter limiter = new LedgerExportRateLimiter(clock);

        assertThat(limiter.tryAcquire(1L)).isTrue();
    }

    @Test
    void secondCallInsideWindow_returnsFalse() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-30T12:00:00Z"));
        LedgerExportRateLimiter limiter = new LedgerExportRateLimiter(clock);

        assertThat(limiter.tryAcquire(1L)).isTrue();
        clock.advanceSeconds(30);
        assertThat(limiter.tryAcquire(1L)).isFalse();
    }

    @Test
    void afterWindow_returnsTrueAgain() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-30T12:00:00Z"));
        LedgerExportRateLimiter limiter = new LedgerExportRateLimiter(clock);

        assertThat(limiter.tryAcquire(1L)).isTrue();
        clock.advanceSeconds(60);
        // Boundary: prior + WINDOW (>= 60s) — should be allowed.
        assertThat(limiter.tryAcquire(1L)).isTrue();
    }

    @Test
    void afterWindow_well_pastWindow_returnsTrue() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-30T12:00:00Z"));
        LedgerExportRateLimiter limiter = new LedgerExportRateLimiter(clock);

        assertThat(limiter.tryAcquire(1L)).isTrue();
        clock.advanceSeconds(120);
        assertThat(limiter.tryAcquire(1L)).isTrue();
    }

    @Test
    void differentUsers_haveSeparateBuckets() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-30T12:00:00Z"));
        LedgerExportRateLimiter limiter = new LedgerExportRateLimiter(clock);

        assertThat(limiter.tryAcquire(1L)).isTrue();
        // user 2 should still be allowed even though user 1 just consumed.
        assertThat(limiter.tryAcquire(2L)).isTrue();
        assertThat(limiter.tryAcquire(1L)).isFalse();
        assertThat(limiter.tryAcquire(2L)).isFalse();
    }

    @Test
    void resetForTesting_clearsState() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-30T12:00:00Z"));
        LedgerExportRateLimiter limiter = new LedgerExportRateLimiter(clock);

        assertThat(limiter.tryAcquire(1L)).isTrue();
        limiter.resetForTesting();
        assertThat(limiter.tryAcquire(1L)).isTrue();
    }
}
