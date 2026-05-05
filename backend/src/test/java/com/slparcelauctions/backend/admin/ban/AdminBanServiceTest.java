package com.slparcelauctions.backend.admin.ban;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.ban.dto.CreateBanRequest;
import com.slparcelauctions.backend.admin.ban.exception.BanAlreadyLiftedException;
import com.slparcelauctions.backend.admin.ban.exception.BanTypeFieldMismatchException;
import com.slparcelauctions.backend.auth.RefreshTokenRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class AdminBanServiceTest {

    @Mock BanRepository banRepository;
    @Mock UserRepository userRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock BanCacheInvalidator cacheInvalidator;
    @Mock AdminActionService adminActionService;

    private static final Clock FIXED_CLOCK =
        Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC);

    private AdminBanService service;

    private static final Long ADMIN_ID = 1L;
    private static final Long LINKED_USER_ID = 99L;
    private static final UUID AVATAR_UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String IP = "10.0.0.1";

    private User adminUser;
    private User linkedUser;

    @BeforeEach
    void setUp() {
        service = new AdminBanService(banRepository, userRepository, refreshTokenRepository,
            cacheInvalidator, adminActionService, FIXED_CLOCK);

        adminUser = User.builder()
            .email("admin@x.com").username("admin")
            .passwordHash("x")
            .displayName("Admin")
            .build();
        setId(adminUser, ADMIN_ID);

        linkedUser = User.builder()
            .email("user@x.com").username("user")
            .passwordHash("x")
            .slAvatarUuid(AVATAR_UUID)
            .displayName("Linked User")
            .build();
        setId(linkedUser, LINKED_USER_ID);
    }

    // -------------------------------------------------------------------------
    // validateTypeMatchesFields
    // -------------------------------------------------------------------------

    @Test
    void create_ipBan_withAvatarUuid_throwsMismatch() {
        CreateBanRequest req = new CreateBanRequest(
            BanType.IP, IP, AVATAR_UUID, null, BanReasonCategory.SPAM, "reason");

        assertThatThrownBy(() -> service.create(req, ADMIN_ID))
            .isInstanceOf(BanTypeFieldMismatchException.class)
            .hasMessageContaining("IP ban");
    }

    @Test
    void create_ipBan_withNoIpAddress_throwsMismatch() {
        CreateBanRequest req = new CreateBanRequest(
            BanType.IP, null, null, null, BanReasonCategory.SPAM, "reason");

        assertThatThrownBy(() -> service.create(req, ADMIN_ID))
            .isInstanceOf(BanTypeFieldMismatchException.class)
            .hasMessageContaining("IP ban");
    }

    @Test
    void create_avatarBan_withIpAddress_throwsMismatch() {
        CreateBanRequest req = new CreateBanRequest(
            BanType.AVATAR, IP, AVATAR_UUID, null, BanReasonCategory.SPAM, "reason");

        assertThatThrownBy(() -> service.create(req, ADMIN_ID))
            .isInstanceOf(BanTypeFieldMismatchException.class)
            .hasMessageContaining("AVATAR ban");
    }

    @Test
    void create_avatarBan_withNoAvatarUuid_throwsMismatch() {
        CreateBanRequest req = new CreateBanRequest(
            BanType.AVATAR, null, null, null, BanReasonCategory.SPAM, "reason");

        assertThatThrownBy(() -> service.create(req, ADMIN_ID))
            .isInstanceOf(BanTypeFieldMismatchException.class)
            .hasMessageContaining("AVATAR ban");
    }

    @Test
    void create_bothBan_withMissingIp_throwsMismatch() {
        CreateBanRequest req = new CreateBanRequest(
            BanType.BOTH, null, AVATAR_UUID, null, BanReasonCategory.SPAM, "reason");

        assertThatThrownBy(() -> service.create(req, ADMIN_ID))
            .isInstanceOf(BanTypeFieldMismatchException.class)
            .hasMessageContaining("BOTH ban");
    }

    @Test
    void create_bothBan_withMissingAvatar_throwsMismatch() {
        CreateBanRequest req = new CreateBanRequest(
            BanType.BOTH, IP, null, null, BanReasonCategory.SPAM, "reason");

        assertThatThrownBy(() -> service.create(req, ADMIN_ID))
            .isInstanceOf(BanTypeFieldMismatchException.class)
            .hasMessageContaining("BOTH ban");
    }

    // -------------------------------------------------------------------------
    // tv-bump behavior
    // -------------------------------------------------------------------------

    @Test
    void create_avatarBan_bumpsMatchingUserTokenVersion() {
        CreateBanRequest req = new CreateBanRequest(
            BanType.AVATAR, null, AVATAR_UUID, null, BanReasonCategory.TOS_ABUSE, "reason");

        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(adminUser));
        Ban savedBan = buildBan(BanType.AVATAR, null, AVATAR_UUID, adminUser);
        when(banRepository.save(any())).thenReturn(savedBan);
        when(userRepository.findBySlAvatarUuid(AVATAR_UUID)).thenReturn(Optional.of(linkedUser));
        when(userRepository.bumpTokenVersion(LINKED_USER_ID)).thenReturn(1);
        when(refreshTokenRepository.findIpSummaryByUserId(anyLong())).thenReturn(Collections.emptyList());

        service.create(req, ADMIN_ID);

        verify(userRepository).bumpTokenVersion(LINKED_USER_ID);
    }

    @Test
    void create_avatarBan_noMatchingUser_skipsTvBump() {
        CreateBanRequest req = new CreateBanRequest(
            BanType.AVATAR, null, AVATAR_UUID, null, BanReasonCategory.TOS_ABUSE, "reason");

        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(adminUser));
        Ban savedBan = buildBan(BanType.AVATAR, null, AVATAR_UUID, adminUser);
        when(banRepository.save(any())).thenReturn(savedBan);
        when(userRepository.findBySlAvatarUuid(AVATAR_UUID)).thenReturn(Optional.empty());

        service.create(req, ADMIN_ID);

        verify(userRepository, never()).bumpTokenVersion(anyLong());
    }

    @Test
    void create_ipOnlyBan_doesNotBumpAnyTokenVersion() {
        CreateBanRequest req = new CreateBanRequest(
            BanType.IP, IP, null, null, BanReasonCategory.SPAM, "reason");

        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(adminUser));
        Ban savedBan = buildBan(BanType.IP, IP, null, adminUser);
        when(banRepository.save(any())).thenReturn(savedBan);

        service.create(req, ADMIN_ID);

        verify(userRepository, never()).bumpTokenVersion(anyLong());
    }

    @Test
    void create_bothBan_bumpsMatchingUserTokenVersion() {
        CreateBanRequest req = new CreateBanRequest(
            BanType.BOTH, IP, AVATAR_UUID, null, BanReasonCategory.SHILL_BIDDING, "reason");

        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(adminUser));
        Ban savedBan = buildBan(BanType.BOTH, IP, AVATAR_UUID, adminUser);
        when(banRepository.save(any())).thenReturn(savedBan);
        when(userRepository.findBySlAvatarUuid(AVATAR_UUID)).thenReturn(Optional.of(linkedUser));
        when(userRepository.bumpTokenVersion(LINKED_USER_ID)).thenReturn(1);
        when(refreshTokenRepository.findIpSummaryByUserId(anyLong())).thenReturn(Collections.emptyList());

        service.create(req, ADMIN_ID);

        verify(userRepository).bumpTokenVersion(LINKED_USER_ID);
    }

    // -------------------------------------------------------------------------
    // Cache invalidation and admin action recording
    // -------------------------------------------------------------------------

    @Test
    void create_invalidatesCache_andWritesAdminAction() {
        CreateBanRequest req = new CreateBanRequest(
            BanType.IP, IP, null, null, BanReasonCategory.SPAM, "reason text");

        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(adminUser));
        Ban savedBan = buildBan(BanType.IP, IP, null, adminUser);
        when(banRepository.save(any())).thenReturn(savedBan);

        service.create(req, ADMIN_ID);

        verify(cacheInvalidator).invalidate(eq(IP), eq(null));
        verify(adminActionService).record(
            eq(ADMIN_ID),
            eq(com.slparcelauctions.backend.admin.audit.AdminActionType.CREATE_BAN),
            eq(com.slparcelauctions.backend.admin.audit.AdminActionTargetType.BAN),
            any(),
            eq("reason text"),
            any());
    }

    // -------------------------------------------------------------------------
    // lift
    // -------------------------------------------------------------------------

    @Test
    void lift_alreadyLifted_throws() {
        Ban alreadyLifted = buildBan(BanType.IP, IP, null, adminUser);
        alreadyLifted.setLiftedAt(OffsetDateTime.now(FIXED_CLOCK).minusHours(1));

        when(banRepository.findById(42L)).thenReturn(Optional.of(alreadyLifted));

        assertThatThrownBy(() -> service.lift(42L, ADMIN_ID, "some reason"))
            .isInstanceOf(BanAlreadyLiftedException.class)
            .hasMessageContaining("42");
    }

    @Test
    void lift_invalidatesCache_andWritesAdminAction() {
        Ban ban = buildBan(BanType.IP, IP, null, adminUser);

        when(banRepository.findById(42L)).thenReturn(Optional.of(ban));
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(adminUser));
        when(banRepository.save(any())).thenReturn(ban);

        service.lift(42L, ADMIN_ID, "lift reason");

        verify(cacheInvalidator).invalidate(eq(IP), eq(null));
        verify(adminActionService).record(
            eq(ADMIN_ID),
            eq(com.slparcelauctions.backend.admin.audit.AdminActionType.LIFT_BAN),
            eq(com.slparcelauctions.backend.admin.audit.AdminActionTargetType.BAN),
            eq(42L),
            eq("lift reason"),
            eq(null));
        assertThat(ban.getLiftedAt()).isNotNull();
        assertThat(ban.getLiftedByUser()).isEqualTo(adminUser);
        assertThat(ban.getLiftedReason()).isEqualTo("lift reason");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Ban buildBan(BanType type, String ip, UUID avatarUuid, User admin) {
        return Ban.builder()
            .banType(type)
            .ipAddress(ip)
            .slAvatarUuid(avatarUuid)
            .reasonCategory(BanReasonCategory.SPAM)
            .notes("reason")
            .adminUser(admin)
            .build();
    }

    /** Reflectively set the id field (id is declared in BaseEntity). */
    private void setId(User user, Long id) {
        try {
            java.lang.reflect.Field f = com.slparcelauctions.backend.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(user, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
