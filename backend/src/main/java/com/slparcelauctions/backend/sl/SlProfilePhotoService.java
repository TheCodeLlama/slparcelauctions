package com.slparcelauctions.backend.sl;

import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import lombok.extern.slf4j.Slf4j;

/**
 * Resolves a Second Life resident's profile photo by scraping
 * {@code https://world.secondlife.com/resident/{uuid}} for the
 * {@code <img class="parcelimg" src="https://picture-service.secondlife.com/...">}
 * element, then fetching the JPEG bytes from picture-service.
 *
 * <p>Results are Redis-cached: positive entries (base64-encoded JPEG bytes)
 * for 1 hour at {@code sl:profile-photo:{uuid}}, negative entries (sentinel
 * {@code "NONE"}) for 5 minutes so a thundering-herd of no-photo lookups
 * can't hammer LL.
 *
 * <p>Every failure mode (network error, 404, parse failure, host-allow-list
 * violation, oversized body, missing {@code <img>}) collapses to
 * {@code Optional.empty()} + a warn-level log. Callers don't need to
 * differentiate "no profile photo" from "scrape failed" — both render the
 * same UI branch (upload-or-skip).
 */
@Service
@Slf4j
public class SlProfilePhotoService {

    static final String CACHE_PREFIX = "sl:profile-photo:";
    static final String NEGATIVE_SENTINEL = "NONE";
    static final Duration POSITIVE_TTL = Duration.ofHours(1);
    static final Duration NEGATIVE_TTL = Duration.ofMinutes(5);
    static final String ALLOWED_HOST_PREFIX = "https://picture-service.secondlife.com/";
    static final long MAX_IMAGE_BYTES = 2L * 1024 * 1024;

    private final WebClient slWorldWebClient;
    private final WebClient slPictureServiceWebClient;
    private final StringRedisTemplate redis;

    public SlProfilePhotoService(
            @Qualifier("slWorldWebClient") WebClient slWorldWebClient,
            @Qualifier("slPictureServiceWebClient") WebClient slPictureServiceWebClient,
            StringRedisTemplate redis) {
        this.slWorldWebClient = slWorldWebClient;
        this.slPictureServiceWebClient = slPictureServiceWebClient;
        this.redis = redis;
    }

    public Optional<byte[]> fetchProfilePhoto(UUID slAvatarUuid) {
        if (slAvatarUuid == null) return Optional.empty();
        String cacheKey = CACHE_PREFIX + slAvatarUuid;

        String cached = safeRedisGet(cacheKey);
        if (NEGATIVE_SENTINEL.equals(cached)) return Optional.empty();
        if (cached != null) {
            try {
                return Optional.of(Base64.getDecoder().decode(cached));
            } catch (IllegalArgumentException e) {
                log.warn("Discarding malformed cached profile-photo for {}: {}",
                        slAvatarUuid, e.getMessage());
                // fall through to re-scrape
            }
        }

        String html;
        try {
            html = slWorldWebClient.get()
                    .uri("/resident/{uuid}", slAvatarUuid)
                    .accept(MediaType.TEXT_HTML)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.warn("world.sl resident page returned {} for {}", e.getStatusCode(), slAvatarUuid);
            cacheNegative(cacheKey);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("world.sl resident page fetch failed for {}: {}", slAvatarUuid, e.getMessage());
            cacheNegative(cacheKey);
            return Optional.empty();
        }
        if (html == null || html.isEmpty()) {
            cacheNegative(cacheKey);
            return Optional.empty();
        }

        Document doc = Jsoup.parse(html);
        Element img = doc.selectFirst("img.parcelimg");
        if (img == null) {
            cacheNegative(cacheKey);
            return Optional.empty();
        }
        String src = img.attr("src");
        if (src.isEmpty() || !src.startsWith(ALLOWED_HOST_PREFIX)) {
            log.warn("Rejected non-allow-listed src '{}' on resident page for {}", src, slAvatarUuid);
            cacheNegative(cacheKey);
            return Optional.empty();
        }
        // Pull the path-suffix after the host so we hit the bound base URL.
        String pathAndQuery = src.substring("https://picture-service.secondlife.com".length());

        byte[] imageBytes;
        try {
            imageBytes = slPictureServiceWebClient.get()
                    .uri(pathAndQuery)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block();
        } catch (WebClientResponseException e) {
            log.warn("picture-service returned {} for {} ({})",
                    e.getStatusCode(), slAvatarUuid, src);
            cacheNegative(cacheKey);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("picture-service fetch failed for {} ({}): {}",
                    slAvatarUuid, src, e.getMessage());
            cacheNegative(cacheKey);
            return Optional.empty();
        }
        if (imageBytes == null || imageBytes.length == 0) {
            cacheNegative(cacheKey);
            return Optional.empty();
        }
        if (imageBytes.length > MAX_IMAGE_BYTES) {
            log.warn("picture-service body too large ({} bytes) for {}", imageBytes.length, slAvatarUuid);
            cacheNegative(cacheKey);
            return Optional.empty();
        }
        cachePositive(cacheKey, imageBytes);
        return Optional.of(imageBytes);
    }

    private String safeRedisGet(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis read failed for {}: {}", key, e.getMessage());
            return null;
        }
    }

    private void cacheNegative(String key) {
        try {
            redis.opsForValue().set(key, NEGATIVE_SENTINEL, NEGATIVE_TTL);
        } catch (Exception e) {
            log.warn("Redis write (negative) failed for {}: {}", key, e.getMessage());
        }
    }

    private void cachePositive(String key, byte[] bytes) {
        try {
            redis.opsForValue().set(key, Base64.getEncoder().encodeToString(bytes), POSITIVE_TTL);
        } catch (Exception e) {
            log.warn("Redis write (positive) failed for {}: {}", key, e.getMessage());
        }
    }
}
