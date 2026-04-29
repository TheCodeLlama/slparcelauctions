package com.slparcelauctions.backend.admin.ban;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.ban.exception.UserBannedException;

import lombok.RequiredArgsConstructor;

/**
 * Redis-cached ban check service. Enforces that neither the requesting
 * IP address nor the authenticated SL avatar UUID matches an active ban.
 *
 * <p>Cache strategy:
 * <ul>
 *   <li>Positive hit (ban found): stores the ban's {@code expiresAt} ISO-8601
 *       string, or the sentinel {@code "perm"} for permanent bans.</li>
 *   <li>Negative hit (no ban): stores the sentinel {@code "0"} to prevent
 *       every unbanned user's request from hitting the DB.</li>
 *   <li>TTL: 5 minutes for both positive and negative entries.</li>
 * </ul>
 *
 * <p>Cache keys:
 * <ul>
 *   <li>{@code bans:active:ip:<ip>}</li>
 *   <li>{@code bans:active:avatar:<uuid>}</li>
 * </ul>
 *
 * <p>Cache invalidation on ban-create/lift is handled by
 * {@link BanCacheInvalidator}.
 */
@Service
@RequiredArgsConstructor
public class BanCheckService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final String IP_KEY = "bans:active:ip:";
    private static final String AVATAR_KEY = "bans:active:avatar:";
    private static final String NEGATIVE = "0";

    private final BanRepository banRepository;
    private final StringRedisTemplate redis;
    private final Clock clock;

    /**
     * Checks both IP and avatar identifiers. Throws {@link UserBannedException}
     * on the first active ban found. A {@code null} argument skips that check.
     *
     * @param ipAddress   the caller's resolved IP address (may be null)
     * @param slAvatarUuid the authenticated avatar UUID (may be null for unauthenticated requests)
     */
    @Transactional(readOnly = true)
    public void assertNotBanned(String ipAddress, UUID slAvatarUuid) {
        if (ipAddress != null && !ipAddress.isBlank()) {
            checkIp(ipAddress);
        }
        if (slAvatarUuid != null) {
            checkAvatar(slAvatarUuid);
        }
    }

    private void checkIp(String ip) {
        String cached = redis.opsForValue().get(IP_KEY + ip);
        if (NEGATIVE.equals(cached)) return;
        if (cached != null) {
            throw new UserBannedException("perm".equals(cached) ? null
                : OffsetDateTime.parse(cached));
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Ban> hits = banRepository.findActiveByIp(ip, now);
        if (hits.isEmpty()) {
            redis.opsForValue().set(IP_KEY + ip, NEGATIVE, CACHE_TTL);
            return;
        }
        Ban ban = hits.get(0);
        String value = ban.getExpiresAt() == null ? "perm" : ban.getExpiresAt().toString();
        redis.opsForValue().set(IP_KEY + ip, value, CACHE_TTL);
        throw new UserBannedException(ban.getExpiresAt());
    }

    private void checkAvatar(UUID uuid) {
        String key = AVATAR_KEY + uuid;
        String cached = redis.opsForValue().get(key);
        if (NEGATIVE.equals(cached)) return;
        if (cached != null) {
            throw new UserBannedException("perm".equals(cached) ? null
                : OffsetDateTime.parse(cached));
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Ban> hits = banRepository.findActiveByAvatar(uuid, now);
        if (hits.isEmpty()) {
            redis.opsForValue().set(key, NEGATIVE, CACHE_TTL);
            return;
        }
        Ban ban = hits.get(0);
        String value = ban.getExpiresAt() == null ? "perm" : ban.getExpiresAt().toString();
        redis.opsForValue().set(key, value, CACHE_TTL);
        throw new UserBannedException(ban.getExpiresAt());
    }
}
