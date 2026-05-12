package com.slparcelauctions.backend.realty.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.slparcelauctions.backend.realty.reports.exception.ReportRateLimitedException;

/**
 * Unit tests for {@link RealtyGroupReportRateLimiter}. Mocks {@link StringRedisTemplate}
 * with a single in-memory counter so we can drive the increment-then-check semantics
 * deterministically.
 */
@ExtendWith(MockitoExtension.class)
class RealtyGroupReportRateLimiterTest {

    private static final LocalDate FIXED_DATE = LocalDate.of(2026, 5, 12);

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    Clock clock;
    RealtyGroupReportRateLimiter limiter;
    AtomicLong counter;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_DATE.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        limiter = new RealtyGroupReportRateLimiter(redis, clock);
        counter = new AtomicLong();
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.increment(any(String.class)))
            .thenAnswer(inv -> counter.incrementAndGet());
        lenient().when(redis.expire(any(String.class), any(Duration.class)))
            .thenReturn(true);
    }

    @Test
    void checkAndIncrement_underLimit_passes() {
        // First 5 increments should all be accepted (DAILY_LIMIT = 5).
        for (int i = 0; i < 5; i++) {
            limiter.checkAndIncrement(42L);
        }

        // The Redis key is shaped report_rl:{reporterId}:{yyyy-mm-dd} — spec §12.1
        // requires this exact shape so it shares state with the listing-report path.
        String expectedKey = "report_rl:42:" + FIXED_DATE;
        verify(valueOps, org.mockito.Mockito.times(5)).increment(eq(expectedKey));
        // TTL is set exactly once — on the first INCR that flipped the counter to 1.
        verify(redis).expire(eq(expectedKey), eq(Duration.ofDays(1)));
        assertThat(counter.get()).isEqualTo(5L);
    }

    @Test
    void checkAndIncrement_overLimit_throws() {
        // Drain the daily quota.
        for (int i = 0; i < 5; i++) {
            limiter.checkAndIncrement(42L);
        }

        // The 6th call is over-limit and must throw a 429-mapped exception.
        assertThatThrownBy(() -> limiter.checkAndIncrement(42L))
            .isInstanceOf(ReportRateLimitedException.class);
    }

    @Test
    void checkAndIncrement_firstHit_setsOneDayTtl() {
        when(valueOps.increment(any(String.class))).thenReturn(1L);

        limiter.checkAndIncrement(7L);

        verify(redis).expire(eq("report_rl:7:" + FIXED_DATE), eq(Duration.ofDays(1)));
    }

    @Test
    void checkAndIncrement_subsequentHits_doNotResetTtl() {
        // INCR returns 3 — not the first hit, TTL should not be touched.
        when(valueOps.increment(any(String.class))).thenReturn(3L);

        limiter.checkAndIncrement(7L);

        verify(redis, never()).expire(any(String.class), any(Duration.class));
    }
}
