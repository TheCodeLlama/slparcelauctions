package com.slparcelauctions.backend.realty.reports;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.realty.reports.exception.ReportRateLimitedException;

import lombok.RequiredArgsConstructor;

/**
 * Redis INCR/EXPIRE-backed per-reporter daily quota for user-submitted reports. The
 * Redis key is {@code report_rl:{reporterId}:{yyyy-mm-dd}} and is intentionally
 * shared with the listing-report path (spec §12.1) — the 5/day limit is a single
 * bucket across both entity types so a spammer cannot rotate target types to
 * multiply their cap.
 *
 * <p>Behaviour:
 * <ol>
 *   <li>{@code INCR} the day's key. On the first hit ({@code count == 1L}) set a
 *       1-day TTL so the bucket auto-resets at the next UTC day boundary.</li>
 *   <li>If the new count exceeds {@link #DAILY_LIMIT}, throw
 *       {@link ReportRateLimitedException} (mapped to 429 by the controller layer).</li>
 * </ol>
 *
 * <p>Counting is increment-first so a denied request still consumes the slot — this
 * is intentional. The alternative (rolled-back INCR) opens a window where a
 * coordinated burst can squeeze past the cap by overlapping check-and-decrement
 * races; the cheaper "deny still counts" rule is consistent with how upstream
 * web-tier rate limiters behave.
 *
 * <p>Sub-project F spec §8, §12.1.
 */
@Component
@RequiredArgsConstructor
public class RealtyGroupReportRateLimiter {

    /** Shared daily cap across listing + realty-group reports per spec §12.1. */
    static final int DAILY_LIMIT = 5;

    /**
     * Redis key prefix. Intentionally shared with the listing-report path so the
     * quota is total across both entity types — see class javadoc.
     */
    static final String KEY_PREFIX = "report_rl:";

    private final StringRedisTemplate redis;
    private final Clock clock;

    /**
     * Increments the reporter's daily counter and throws if the new count exceeds the
     * shared {@link #DAILY_LIMIT}. Safe to call before doing any DB work — over-limit
     * requests short-circuit before the transaction opens.
     *
     * @throws ReportRateLimitedException when the reporter has exhausted today's quota
     */
    public void checkAndIncrement(Long reporterId) {
        String date = LocalDate.now(clock).toString();
        String key = KEY_PREFIX + reporterId + ":" + date;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            // First hit of the day — set the TTL so the bucket auto-resets. A 1-day
            // TTL is fine; the date-keyed shape means yesterday's keys are
            // effectively dead anyway, the EXPIRE is just a cleanup hint.
            redis.expire(key, Duration.ofDays(1));
        }
        if (count != null && count > DAILY_LIMIT) {
            throw new ReportRateLimitedException(DAILY_LIMIT);
        }
    }
}
