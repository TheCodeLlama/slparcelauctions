package com.slparcelauctions.backend.sl;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.slparcelauctions.backend.auction.search.exception.RegionLookupUnavailableException;
import com.slparcelauctions.backend.sl.dto.GridCoordinates;
import com.slparcelauctions.backend.sl.dto.RegionResolution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Case-insensitive Redis cache in front of {@link SlMapApiClient#resolve}.
 *
 * <p>Cache policy:
 * <ul>
 *   <li>Positive hits live 7 days — region grid coordinates change
 *       extremely rarely on the live grid.</li>
 *   <li>Negative hits live 10 minutes via a sentinel string — long
 *       enough to absorb a Grid Survey outage, short enough that a new
 *       region coming online is not invisible for a week.</li>
 *   <li>Upstream errors are NOT cached — the next caller retries and
 *       gets a fresh {@link RegionLookupUnavailableException}.</li>
 * </ul>
 *
 * <p>Keys are {@code slpa:grid-coord:<lowercased-trimmed-name>}; values
 * are {@code "<x>,<y>"} pairs (or the {@code __NOT_FOUND__} sentinel).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CachedRegionResolver {

    public static final Duration POSITIVE_TTL = Duration.ofDays(7);
    public static final Duration NEGATIVE_TTL = Duration.ofMinutes(10);
    private static final String NEG_SENTINEL = "__NOT_FOUND__";
    private static final String KEY_PREFIX = "slpa:grid-coord:";

    private final SlMapApiClient slMapApiClient;
    private final StringRedisTemplate redis;

    public Optional<GridCoordinates> resolve(String regionName) {
        if (regionName == null || regionName.isBlank()) {
            return Optional.empty();
        }

        String key = KEY_PREFIX + regionName.trim().toLowerCase(Locale.ROOT);
        String cached = readCache(key);
        if (cached != null) {
            if (NEG_SENTINEL.equals(cached)) {
                log.debug("Region '{}' is negatively cached", regionName);
                return Optional.empty();
            }
            return Optional.of(parse(cached));
        }

        RegionResolution result = slMapApiClient.resolve(regionName);
        return switch (result) {
            case RegionResolution.Found f -> {
                writeCache(key, f.gridX() + "," + f.gridY(), POSITIVE_TTL);
                yield Optional.of(new GridCoordinates(f.gridX(), f.gridY()));
            }
            case RegionResolution.NotFound nf -> {
                writeCache(key, NEG_SENTINEL, NEGATIVE_TTL);
                yield Optional.empty();
            }
            case RegionResolution.UpstreamError err -> {
                throw new RegionLookupUnavailableException(
                        "Grid Survey lookup failed for '" + regionName + "': " + err.reason());
            }
        };
    }

    private String readCache(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.warn("Failed to read grid-coord cache for key {}: {}", key, e.toString());
            return null;
        }
    }

    private void writeCache(String key, String value, Duration ttl) {
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (RuntimeException e) {
            log.warn("Failed to write grid-coord cache for key {}: {}", key, e.toString());
        }
    }

    private GridCoordinates parse(String v) {
        String[] parts = v.split(",");
        return new GridCoordinates(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
    }
}
