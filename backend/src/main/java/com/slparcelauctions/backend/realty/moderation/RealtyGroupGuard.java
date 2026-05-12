package com.slparcelauctions.backend.realty.moderation;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.realty.moderation.exception.RealtyGroupSuspendedException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Enforces that a realty group is allowed to operate (create listings, withdraw funds,
 * change membership, register an SL group, etc.) by short-circuiting through Redis and
 * falling back to a DB query against {@code realty_group_suspensions}.
 *
 * <p>Wire-aligned with {@link RealtyGroupSuspensionService}'s write pattern (spec §8):
 * the suspension entry at key {@code realty_group_suspended:{groupId}} is a plain
 * Redis string whose value is either:
 * <ul>
 *   <li>{@code "PERMANENT"} — permanent ban, throw with {@link RealtyGroupSuspendedException.Status#BANNED}.</li>
 *   <li>An ISO-8601 timestamp strictly after {@code now} — timed suspension, throw with
 *       {@link RealtyGroupSuspendedException.Status#SUSPENDED} and that expiry.</li>
 *   <li>An ISO-8601 timestamp at or before {@code now} — stale entry, fall through to DB.</li>
 *   <li>Missing or unparseable — fall through to DB.</li>
 * </ul>
 *
 * <p>Sub-project F spec §5.2, §5.3.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RealtyGroupGuard {

    /** Prefix for the Redis short-circuit key; mirrors {@link RealtyGroupSuspensionService#REDIS_KEY_PREFIX}. */
    static final String REDIS_KEY_PREFIX = "realty_group_suspended:";
    /** Marker stored at the key when the suspension is a permanent ban. */
    static final String REDIS_PERMANENT_MARKER = "PERMANENT";

    private final RealtyGroupSuspensionRepository suspensions;
    private final StringRedisTemplate redis;
    private final Clock clock;

    /**
     * Throws {@link RealtyGroupSuspendedException} if the realty group with the given
     * internal id is currently suspended or banned. Returns silently otherwise.
     *
     * @param groupId the {@code realty_groups.id} primary key of the group to check
     */
    public void requireGroupCanOperate(Long groupId) {
        String redisKey = REDIS_KEY_PREFIX + groupId;
        OffsetDateTime now = OffsetDateTime.now(clock);

        String cached = redis.opsForValue().get(redisKey);
        if (cached != null) {
            if (REDIS_PERMANENT_MARKER.equals(cached)) {
                throw new RealtyGroupSuspendedException(
                    RealtyGroupSuspendedException.Status.BANNED, null, null);
            }
            try {
                OffsetDateTime expiresAt = OffsetDateTime.parse(cached);
                if (expiresAt.isAfter(now)) {
                    throw new RealtyGroupSuspendedException(
                        RealtyGroupSuspendedException.Status.SUSPENDED, expiresAt, null);
                }
                // Stale entry — Redis key hasn't been swept yet. Fall through to DB.
            } catch (DateTimeParseException ex) {
                // Corrupt value — log and fall through to DB so a bad cache row doesn't
                // strand callers in a permanent 409.
                log.warn("Unparseable Redis suspension value for group {}: '{}'", groupId, cached);
            }
        }

        Optional<RealtyGroupSuspension> active = suspensions.findActiveByGroupId(groupId, now);
        if (active.isEmpty()) {
            return;
        }
        RealtyGroupSuspension row = active.get();
        OffsetDateTime expiresAt = row.getExpiresAt();
        String reason = row.getReason() == null ? null : row.getReason().name();
        if (expiresAt == null) {
            throw new RealtyGroupSuspendedException(
                RealtyGroupSuspendedException.Status.BANNED, null, reason);
        }
        throw new RealtyGroupSuspendedException(
            RealtyGroupSuspendedException.Status.SUSPENDED, expiresAt, reason);
    }
}
