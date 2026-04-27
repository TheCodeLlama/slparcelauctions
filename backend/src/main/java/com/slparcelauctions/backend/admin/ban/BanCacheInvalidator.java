package com.slparcelauctions.backend.admin.ban;

import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Clears {@link BanCheckService} Redis cache entries when a ban is created or
 * lifted. Call {@link #invalidate} in the same transaction (or immediately
 * after commit) as the ban-create or ban-lift operation so that the next
 * request re-reads from the DB rather than serving a stale cached value.
 */
@Component
@RequiredArgsConstructor
public class BanCacheInvalidator {

    private static final String IP_KEY = "bans:active:ip:";
    private static final String AVATAR_KEY = "bans:active:avatar:";

    private final StringRedisTemplate redis;

    /**
     * Deletes the cache keys for the given identifiers. Null arguments are
     * silently skipped.
     *
     * @param ipAddress   the IP address key to evict (may be null)
     * @param slAvatarUuid the avatar UUID key to evict (may be null)
     */
    public void invalidate(String ipAddress, UUID slAvatarUuid) {
        if (ipAddress != null && !ipAddress.isBlank()) {
            redis.delete(IP_KEY + ipAddress);
        }
        if (slAvatarUuid != null) {
            redis.delete(AVATAR_KEY + slAvatarUuid);
        }
    }
}
