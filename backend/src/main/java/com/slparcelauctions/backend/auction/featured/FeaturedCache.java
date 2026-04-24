package com.slparcelauctions.backend.auction.featured;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Per-endpoint read-through cache for the featured rows. Each
 * {@link FeaturedCategory} maps to its own Redis key, so a stampede on
 * one row will not refresh the others.
 *
 * <p>Reads are wrapped in try/catch so a deserialization failure (e.g.
 * stale schema) degrades to a recompute rather than a 5xx — same posture
 * as {@code SearchResponseCache}. Writes are similarly defensive.
 *
 * <p>Constructor uses an explicit {@code @Qualifier} parameter rather
 * than {@code @RequiredArgsConstructor} because Lombok will not propagate
 * the field-level {@code @Qualifier} to the generated parameter.
 */
@Component
@Slf4j
public class FeaturedCache {

    public static final Duration TTL = Duration.ofSeconds(60);

    private final RedisTemplate<String, Object> redis;

    public FeaturedCache(@Qualifier("epic07RedisTemplate") RedisTemplate<String, Object> redis) {
        this.redis = redis;
    }

    public FeaturedResponse getOrCompute(FeaturedCategory category,
                                         Supplier<FeaturedResponse> compute) {
        Object cached = null;
        try {
            cached = redis.opsForValue().get(category.cacheKey());
        } catch (RuntimeException e) {
            log.warn("Failed to read featured cache for {}: {}",
                    category, e.toString());
        }
        if (cached instanceof FeaturedResponse resp) {
            log.debug("Featured cache HIT: {}", category);
            return resp;
        }
        log.debug("Featured cache MISS: {}", category);
        FeaturedResponse computed = compute.get();
        try {
            redis.opsForValue().set(category.cacheKey(), computed, TTL);
        } catch (RuntimeException e) {
            log.warn("Failed to cache featured response for {}: {}",
                    category, e.toString());
        }
        return computed;
    }
}
