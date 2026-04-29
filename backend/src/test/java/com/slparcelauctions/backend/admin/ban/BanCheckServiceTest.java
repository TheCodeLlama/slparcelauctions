package com.slparcelauctions.backend.admin.ban;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.slparcelauctions.backend.admin.ban.exception.UserBannedException;

@ExtendWith(MockitoExtension.class)
class BanCheckServiceTest {

    @Mock BanRepository banRepository;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    private Clock fixedClock;
    private BanCheckService service;

    private static final String TEST_IP = "192.168.1.1";
    private static final UUID TEST_UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String IP_KEY = "bans:active:ip:" + TEST_IP;
    private static final String AVATAR_KEY = "bans:active:avatar:" + TEST_UUID;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC);
        // lenient: the noop test (both args null) never calls opsForValue()
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        service = new BanCheckService(banRepository, redis, fixedClock);
    }

    // -------------------------------------------------------------------------
    // Cache hit — NEGATIVE sentinel ("0")
    // -------------------------------------------------------------------------

    @Test
    void cacheHitNegative_ip_noDbQueryNoThrow() {
        when(valueOps.get(IP_KEY)).thenReturn("0");

        service.assertNotBanned(TEST_IP, null);

        verify(banRepository, never()).findActiveByIp(any(), any());
    }

    @Test
    void cacheHitNegative_avatar_noDbQueryNoThrow() {
        when(valueOps.get(AVATAR_KEY)).thenReturn("0");

        service.assertNotBanned(null, TEST_UUID);

        verify(banRepository, never()).findActiveByAvatar(any(), any());
    }

    // -------------------------------------------------------------------------
    // Cache hit — "perm" sentinel
    // -------------------------------------------------------------------------

    @Test
    void cacheHitPerm_ip_throwsWithNullExpiresAt() {
        when(valueOps.get(IP_KEY)).thenReturn("perm");

        assertThatThrownBy(() -> service.assertNotBanned(TEST_IP, null))
            .isInstanceOf(UserBannedException.class)
            .satisfies(ex -> assertThat(((UserBannedException) ex).getExpiresAt()).isNull());

        verify(banRepository, never()).findActiveByIp(any(), any());
    }

    @Test
    void cacheHitPerm_avatar_throwsWithNullExpiresAt() {
        when(valueOps.get(AVATAR_KEY)).thenReturn("perm");

        assertThatThrownBy(() -> service.assertNotBanned(null, TEST_UUID))
            .isInstanceOf(UserBannedException.class)
            .satisfies(ex -> assertThat(((UserBannedException) ex).getExpiresAt()).isNull());

        verify(banRepository, never()).findActiveByAvatar(any(), any());
    }

    // -------------------------------------------------------------------------
    // Cache hit — ISO-8601 date string
    // -------------------------------------------------------------------------

    @Test
    void cacheHitIsoDate_ip_throwsWithParsedExpiresAt() {
        String isoDate = "2026-06-01T00:00:00Z";
        when(valueOps.get(IP_KEY)).thenReturn(isoDate);

        assertThatThrownBy(() -> service.assertNotBanned(TEST_IP, null))
            .isInstanceOf(UserBannedException.class)
            .satisfies(ex -> assertThat(((UserBannedException) ex).getExpiresAt())
                .isEqualTo(OffsetDateTime.parse(isoDate)));
    }

    @Test
    void cacheHitIsoDate_avatar_throwsWithParsedExpiresAt() {
        String isoDate = "2026-06-01T00:00:00Z";
        when(valueOps.get(AVATAR_KEY)).thenReturn(isoDate);

        assertThatThrownBy(() -> service.assertNotBanned(null, TEST_UUID))
            .isInstanceOf(UserBannedException.class)
            .satisfies(ex -> assertThat(((UserBannedException) ex).getExpiresAt())
                .isEqualTo(OffsetDateTime.parse(isoDate)));
    }

    // -------------------------------------------------------------------------
    // Cache miss → DB hit positive
    // -------------------------------------------------------------------------

    @Test
    void cacheMissDbHitPositive_ip_cachesAndThrows() {
        when(valueOps.get(IP_KEY)).thenReturn(null);
        OffsetDateTime expiresAt = OffsetDateTime.parse("2027-01-01T00:00:00Z");
        Ban ban = Ban.builder()
            .banType(BanType.IP)
            .ipAddress(TEST_IP)
            .reasonCategory(BanReasonCategory.SPAM)
            .expiresAt(expiresAt)
            .build();
        when(banRepository.findActiveByIp(eq(TEST_IP), any())).thenReturn(List.of(ban));

        assertThatThrownBy(() -> service.assertNotBanned(TEST_IP, null))
            .isInstanceOf(UserBannedException.class)
            .satisfies(ex -> assertThat(((UserBannedException) ex).getExpiresAt())
                .isEqualTo(expiresAt));

        verify(valueOps).set(eq(IP_KEY), eq(expiresAt.toString()), any());
    }

    @Test
    void cacheMissDbHitPositivePerm_ip_cachesPermSentinelAndThrows() {
        when(valueOps.get(IP_KEY)).thenReturn(null);
        Ban ban = Ban.builder()
            .banType(BanType.IP)
            .ipAddress(TEST_IP)
            .reasonCategory(BanReasonCategory.SPAM)
            .expiresAt(null)   // permanent
            .build();
        when(banRepository.findActiveByIp(eq(TEST_IP), any())).thenReturn(List.of(ban));

        assertThatThrownBy(() -> service.assertNotBanned(TEST_IP, null))
            .isInstanceOf(UserBannedException.class)
            .satisfies(ex -> assertThat(((UserBannedException) ex).getExpiresAt()).isNull());

        verify(valueOps).set(eq(IP_KEY), eq("perm"), any());
    }

    // -------------------------------------------------------------------------
    // Cache miss → DB miss (negative)
    // -------------------------------------------------------------------------

    @Test
    void cacheMissDbEmpty_ip_cachesNegativeSentinel() {
        when(valueOps.get(IP_KEY)).thenReturn(null);
        when(banRepository.findActiveByIp(eq(TEST_IP), any())).thenReturn(List.of());

        service.assertNotBanned(TEST_IP, null);   // no throw

        verify(valueOps).set(eq(IP_KEY), eq("0"), any());
    }

    @Test
    void cacheMissDbEmpty_avatar_cachesNegativeSentinel() {
        when(valueOps.get(AVATAR_KEY)).thenReturn(null);
        when(banRepository.findActiveByAvatar(eq(TEST_UUID), any())).thenReturn(List.of());

        service.assertNotBanned(null, TEST_UUID);   // no throw

        verify(valueOps).set(eq(AVATAR_KEY), eq("0"), any());
    }

    // -------------------------------------------------------------------------
    // Null / blank argument handling
    // -------------------------------------------------------------------------

    @Test
    void assertNotBanned_bothNull_isNoop() {
        service.assertNotBanned(null, null);

        verify(redis, never()).opsForValue();
        verify(banRepository, never()).findActiveByIp(anyString(), any());
        verify(banRepository, never()).findActiveByAvatar(any(), any());
    }

    @Test
    void assertNotBanned_nullUuid_onlyIpCheckFires() {
        when(valueOps.get(IP_KEY)).thenReturn("0");

        service.assertNotBanned(TEST_IP, null);

        verify(banRepository, never()).findActiveByAvatar(any(), any());
    }

    @Test
    void assertNotBanned_nullIp_onlyAvatarCheckFires() {
        when(valueOps.get(AVATAR_KEY)).thenReturn("0");

        service.assertNotBanned(null, TEST_UUID);

        verify(banRepository, never()).findActiveByIp(anyString(), any());
    }

    @Test
    void assertNotBanned_blankIp_skipsIpCheck() {
        when(valueOps.get(AVATAR_KEY)).thenReturn("0");

        service.assertNotBanned("   ", TEST_UUID);

        verify(banRepository, never()).findActiveByIp(anyString(), any());
    }
}
