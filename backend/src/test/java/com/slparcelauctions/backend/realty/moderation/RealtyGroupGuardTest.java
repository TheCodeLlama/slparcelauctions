package com.slparcelauctions.backend.realty.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.moderation.exception.RealtyGroupSuspendedException;

/**
 * Unit tests for {@link RealtyGroupGuard}. Mockito-driven with a fixed {@link Clock}
 * for determinism. Covers spec §5.2/§5.3 behaviours: Redis short-circuit hot path,
 * DB cold path fallback, and the defensive fallbacks when the Redis hash is missing
 * its field, holds an expired timestamp, or contains an unparseable value.
 */
@ExtendWith(MockitoExtension.class)
class RealtyGroupGuardTest {

    private static final OffsetDateTime FIXED_NOW =
        OffsetDateTime.of(2026, 5, 12, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final String REDIS_KEY = "realty_group_suspended:42";

    @Mock RealtyGroupSuspensionRepository suspensions;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    Clock clock;
    RealtyGroupGuard guard;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_NOW.toInstant(), ZoneOffset.UTC);
        guard = new RealtyGroupGuard(suspensions, redis, clock);
        // Every code path consults the Redis value first; stub leniently so individual
        // tests don't have to repeat the boilerplate.
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    private RealtyGroup buildGroup(Long id) {
        RealtyGroup g = RealtyGroup.builder()
            .name("Mainland Realty Co")
            .slug("mainland-realty-co")
            .leaderId(100L)
            .build();
        try {
            java.lang.reflect.Field f = findIdField(g.getClass());
            f.setAccessible(true);
            f.set(g, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return g;
    }

    private static java.lang.reflect.Field findIdField(Class<?> c) throws NoSuchFieldException {
        Class<?> cur = c;
        while (cur != null) {
            try {
                return cur.getDeclaredField("id");
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            }
        }
        throw new NoSuchFieldException("id field not found in " + c);
    }

    // ─────────────────── DB cold path ───────────────────

    @Test
    void requireGroupCanOperate_whenNoSuspensionRow_passes() {
        when(valueOps.get(REDIS_KEY)).thenReturn(null);
        when(suspensions.findActiveByGroupId(eq(42L), any(OffsetDateTime.class)))
            .thenReturn(Optional.empty());

        assertThatCode(() -> guard.requireGroupCanOperate(42L)).doesNotThrowAnyException();

        verify(suspensions).findActiveByGroupId(eq(42L), any(OffsetDateTime.class));
    }

    @Test
    void requireGroupCanOperate_whenActiveTimedSuspension_throwsRealtyGroupSuspendedExceptionWithExpiry() {
        OffsetDateTime expiresAt = FIXED_NOW.plusHours(24);
        RealtyGroup group = buildGroup(42L);
        RealtyGroupSuspension active = RealtyGroupSuspension.builder()
            .realtyGroup(group)
            .reason(SuspensionReason.FRAUD)
            .issuedAt(FIXED_NOW.minusHours(1))
            .expiresAt(expiresAt)
            .build();
        when(valueOps.get(REDIS_KEY)).thenReturn(null);
        when(suspensions.findActiveByGroupId(eq(42L), any(OffsetDateTime.class)))
            .thenReturn(Optional.of(active));

        assertThatThrownBy(() -> guard.requireGroupCanOperate(42L))
            .isInstanceOfSatisfying(RealtyGroupSuspendedException.class, ex -> {
                assertThat(ex.getStatus()).isEqualTo(RealtyGroupSuspendedException.Status.SUSPENDED);
                assertThat(ex.getExpiresAt()).isEqualTo(expiresAt);
                assertThat(ex.getReason()).isEqualTo("FRAUD");
            });
    }

    @Test
    void requireGroupCanOperate_whenActivePermanentBan_throwsRealtyGroupSuspendedExceptionWithBannedStatus() {
        RealtyGroup group = buildGroup(42L);
        RealtyGroupSuspension permanent = RealtyGroupSuspension.builder()
            .realtyGroup(group)
            .reason(SuspensionReason.TOS_VIOLATION)
            .issuedAt(FIXED_NOW.minusHours(2))
            .expiresAt(null)
            .build();
        when(valueOps.get(REDIS_KEY)).thenReturn(null);
        when(suspensions.findActiveByGroupId(eq(42L), any(OffsetDateTime.class)))
            .thenReturn(Optional.of(permanent));

        assertThatThrownBy(() -> guard.requireGroupCanOperate(42L))
            .isInstanceOfSatisfying(RealtyGroupSuspendedException.class, ex -> {
                assertThat(ex.getStatus()).isEqualTo(RealtyGroupSuspendedException.Status.BANNED);
                assertThat(ex.getExpiresAt()).isNull();
                assertThat(ex.getReason()).isEqualTo("TOS_VIOLATION");
            });
    }

    @Test
    void requireGroupCanOperate_whenLiftedRow_passes() {
        // The repository's findActiveByGroupId query filters lifted rows out, so a
        // lifted suspension surfaces as Optional.empty() to the guard. Stubbing the
        // post-filter result is the right level of abstraction for a unit test.
        when(valueOps.get(REDIS_KEY)).thenReturn(null);
        when(suspensions.findActiveByGroupId(eq(42L), any(OffsetDateTime.class)))
            .thenReturn(Optional.empty());

        assertThatCode(() -> guard.requireGroupCanOperate(42L)).doesNotThrowAnyException();
    }

    // ─────────────────── Redis hot path ───────────────────

    @Test
    void requireGroupCanOperate_usesRedisShortCircuitWhenPresent() {
        OffsetDateTime expiresAt = FIXED_NOW.plusHours(24);
        when(valueOps.get(REDIS_KEY)).thenReturn(expiresAt.toString());

        assertThatThrownBy(() -> guard.requireGroupCanOperate(42L))
            .isInstanceOfSatisfying(RealtyGroupSuspendedException.class, ex -> {
                assertThat(ex.getStatus()).isEqualTo(RealtyGroupSuspendedException.Status.SUSPENDED);
                assertThat(ex.getExpiresAt()).isEqualTo(expiresAt);
            });

        // DB must NOT be touched on the hot path.
        verify(suspensions, never()).findActiveByGroupId(anyLong(), any(OffsetDateTime.class));
    }

    @Test
    void requireGroupCanOperate_redisPermanentMarker_throwsBanned() {
        when(valueOps.get(REDIS_KEY)).thenReturn("PERMANENT");

        assertThatThrownBy(() -> guard.requireGroupCanOperate(42L))
            .isInstanceOfSatisfying(RealtyGroupSuspendedException.class, ex -> {
                assertThat(ex.getStatus()).isEqualTo(RealtyGroupSuspendedException.Status.BANNED);
                assertThat(ex.getExpiresAt()).isNull();
            });

        verify(suspensions, never()).findActiveByGroupId(anyLong(), any(OffsetDateTime.class));
    }

    @Test
    void requireGroupCanOperate_redisCorruptValue_fallsThroughToDbQuery() {
        when(valueOps.get(REDIS_KEY)).thenReturn("not-a-timestamp-and-not-PERMANENT");
        when(suspensions.findActiveByGroupId(eq(42L), any(OffsetDateTime.class)))
            .thenReturn(Optional.empty());

        assertThatCode(() -> guard.requireGroupCanOperate(42L)).doesNotThrowAnyException();

        // Falls through: DB was consulted even though Redis returned a value.
        verify(suspensions).findActiveByGroupId(eq(42L), any(OffsetDateTime.class));
    }

    @Test
    void requireGroupCanOperate_redisExpiredButPresent_fallsThroughToDbQuery() {
        // Redis entry holds an ISO timestamp that's already in the past relative to
        // the fixed clock — the entry is stale or hasn't been swept yet. The guard
        // must NOT throw based on the stale value; it must fall through to the DB.
        OffsetDateTime stale = FIXED_NOW.minusHours(1);
        when(valueOps.get(REDIS_KEY)).thenReturn(stale.toString());
        when(suspensions.findActiveByGroupId(eq(42L), any(OffsetDateTime.class)))
            .thenReturn(Optional.empty());

        assertThatCode(() -> guard.requireGroupCanOperate(42L)).doesNotThrowAnyException();

        verify(suspensions).findActiveByGroupId(eq(42L), any(OffsetDateTime.class));
    }
}
