package com.slparcelauctions.backend.stats;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Read-through cache for the bundled public stats DTO. Single Redis key
 * at a 60s TTL — all four counts refresh together so a partial-cache
 * race cannot serve a mismatched mix of fresh and stale numbers.
 *
 * <p>Reads and writes are wrapped in try/catch so a Redis or
 * deserialization failure degrades to a recompute (or just returns the
 * fresh value without caching) rather than a 5xx — same posture as
 * {@code SearchResponseCache} and {@code FeaturedCache}.
 *
 * <p>Constructor uses an explicit {@code @Qualifier} parameter rather
 * than {@code @RequiredArgsConstructor} because Lombok will not
 * propagate the field-level {@code @Qualifier} to the generated
 * parameter.
 */
@Component
@Slf4j
public class PublicStatsCache {

    public static final String KEY = "slpa:stats:public";
    public static final Duration TTL = Duration.ofSeconds(60);

    private final RedisTemplate<String, Object> redis;

    public PublicStatsCache(@Qualifier("epic07RedisTemplate") RedisTemplate<String, Object> redis) {
        this.redis = redis;
    }

    public PublicStatsDto getOrCompute(Supplier<PublicStatsDto> compute) {
        Object cached = null;
        try {
            cached = redis.opsForValue().get(KEY);
        } catch (RuntimeException e) {
            log.warn("Failed to read public stats cache: {}", e.toString());
        }
        if (cached instanceof PublicStatsDto dto) {
            log.debug("Public stats cache HIT");
            return dto;
        }
        log.debug("Public stats cache MISS");
        PublicStatsDto fresh = compute.get();
        try {
            redis.opsForValue().set(KEY, fresh, TTL);
        } catch (RuntimeException e) {
            log.warn("Failed to cache public stats: {}", e.toString());
        }
        return fresh;
    }
}
