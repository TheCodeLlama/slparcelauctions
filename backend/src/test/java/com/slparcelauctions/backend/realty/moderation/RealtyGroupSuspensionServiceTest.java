package com.slparcelauctions.backend.realty.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.auction.monitoring.BulkListingSuspendService;
import com.slparcelauctions.backend.auction.monitoring.BulkListingSuspendService.BulkSuspendResult;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.moderation.exception.SuspensionAlreadyActiveException;
import com.slparcelauctions.backend.realty.moderation.exception.SuspensionNotFoundException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Unit tests for {@link RealtyGroupSuspensionService}. Mockito-driven, fixed clock for
 * determinism. Covers spec §8, §9 behaviours: issue/lift writes the row, fires admin
 * action + notification, optionally invokes the bulk listings service, and writes a
 * Redis short-circuit string with TTL. The {@code BulkListingSuspendService} is mocked
 * here — the real implementation lands in Task 11.
 */
@ExtendWith(MockitoExtension.class)
class RealtyGroupSuspensionServiceTest {

    private static final OffsetDateTime FIXED_NOW =
        OffsetDateTime.of(2026, 5, 12, 10, 0, 0, 0, ZoneOffset.UTC);

    @Mock RealtyGroupRepository groups;
    @Mock RealtyGroupSuspensionRepository suspensions;
    @Mock AdminActionService adminActionService;
    @Mock NotificationPublisher notificationPublisher;
    @Mock BulkListingSuspendService bulkListingSuspendService;
    @Mock UserRepository userRepository;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    Clock clock;
    RealtyGroupSuspensionService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_NOW.toInstant(), ZoneOffset.UTC);
        service = new RealtyGroupSuspensionService(
            groups, suspensions, adminActionService, notificationPublisher,
            bulkListingSuspendService, userRepository, redis, clock);
        // Most tests use the redis opsForValue.set call; stub leniently so happy-path
        // tests don't NPE and unrelated tests don't complain about unused stubs.
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        // The service uses a Hibernate proxy to populate admin FK columns without a
        // SELECT. Mock it to return a User stamped with the requested id.
        lenient().when(userRepository.getReferenceById(any(Long.class)))
            .thenAnswer(inv -> {
                Long adminId = inv.getArgument(0);
                User u = User.builder().build();
                setId(u, adminId);
                return u;
            });
    }

    private RealtyGroup buildGroup(Long id) {
        RealtyGroup g = RealtyGroup.builder()
            .name("Mainland Realty Co")
            .slug("mainland-realty-co")
            .leaderId(100L)
            .build();
        setId(g, id);
        return g;
    }

    private User buildAdmin(Long id) {
        User u = new User();
        u.setUsername("admin-" + id);
        setId(u, id);
        return u;
    }

    /** Reflection helper: set the inherited Long id on a BaseEntity subclass. */
    private static void setId(Object entity, Long id) {
        try {
            java.lang.reflect.Field f = entity.getClass().getSuperclass()
                .getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            // Fall back to one-level up in case the inheritance chain is shorter.
            try {
                java.lang.reflect.Field f = findIdField(entity.getClass());
                f.setAccessible(true);
                f.set(entity, id);
            } catch (ReflectiveOperationException ee) {
                throw new RuntimeException(ee);
            }
        }
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

    // ─────────────────── issue() ───────────────────

    @Test
    void issue_happyPath_writesRowAndAuditAndNotifies() {
        RealtyGroup group = buildGroup(42L);
        UUID groupPid = group.getPublicId();
        OffsetDateTime expires = FIXED_NOW.plusHours(24);
        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(group));
        when(suspensions.findActiveByGroupId(eq(42L), any(OffsetDateTime.class)))
            .thenReturn(Optional.empty());
        when(suspensions.save(any(RealtyGroupSuspension.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        RealtyGroupSuspension result = service.issue(
            groupPid, 9L, SuspensionReason.FRAUD, "fraud detected", expires, false);

        // Row state
        ArgumentCaptor<RealtyGroupSuspension> rowCap = ArgumentCaptor.forClass(RealtyGroupSuspension.class);
        verify(suspensions).save(rowCap.capture());
        RealtyGroupSuspension saved = rowCap.getValue();
        assertThat(saved.getRealtyGroup()).isSameAs(group);
        assertThat(saved.getReason()).isEqualTo(SuspensionReason.FRAUD);
        assertThat(saved.getNotes()).isEqualTo("fraud detected");
        assertThat(saved.getExpiresAt()).isEqualTo(expires);
        assertThat(saved.getLiftedAt()).isNull();
        assertThat(saved.getIssuedAt()).isEqualTo(FIXED_NOW);
        assertThat(saved.getIssuedByAdmin().getId()).isEqualTo(9L);

        // Audit (timed -> REALTY_GROUP_SUSPEND)
        verify(adminActionService).record(
            eq(9L),
            eq(AdminActionType.REALTY_GROUP_SUSPEND),
            eq(AdminActionTargetType.REALTY_GROUP),
            eq(42L),
            any(), any());

        // Notification: per-member fanout helper.
        verify(notificationPublisher).realtyGroupSuspended(eq(group), eq("FRAUD"), eq(expires));

        // Redis short-circuit string with TTL written atomically via opsForValue.set
        verify(valueOps).set(eq("realty_group_suspended:42"), eq(expires.toString()), any(Duration.class));

        // Bulk path NOT invoked
        verify(bulkListingSuspendService, never()).suspendAll(any(), any(), any(), any(), any());

        assertThat(result).isSameAs(saved);
    }

    @Test
    void issue_whenAnActiveSuspensionExists_throwsSuspensionAlreadyActive() {
        RealtyGroup group = buildGroup(42L);
        UUID groupPid = group.getPublicId();
        RealtyGroupSuspension active = RealtyGroupSuspension.builder()
            .realtyGroup(group)
            .reason(SuspensionReason.ABUSE)
            .issuedAt(FIXED_NOW.minusHours(2))
            .expiresAt(FIXED_NOW.plusHours(24))
            .build();
        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(group));
        when(suspensions.findActiveByGroupId(eq(42L), any(OffsetDateTime.class)))
            .thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.issue(
                groupPid, 9L, SuspensionReason.FRAUD, "dup", FIXED_NOW.plusHours(12), false))
            .isInstanceOf(SuspensionAlreadyActiveException.class);

        verify(suspensions, never()).save(any());
        verify(adminActionService, never()).record(any(), any(), any(), any(), any(), any());
        verify(notificationPublisher, never()).realtyGroupSuspended(any(), any(), any());
        verify(bulkListingSuspendService, never()).suspendAll(any(), any(), any(), any(), any());
    }

    @Test
    void issue_withBulkSuspendListings_invokesBulkListingSuspendService() {
        RealtyGroup group = buildGroup(42L);
        UUID groupPid = group.getPublicId();
        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(group));
        when(suspensions.findActiveByGroupId(eq(42L), any(OffsetDateTime.class)))
            .thenReturn(Optional.empty());
        when(suspensions.save(any(RealtyGroupSuspension.class)))
            .thenAnswer(inv -> {
                RealtyGroupSuspension r = inv.getArgument(0);
                setId(r, 999L);
                return r;
            });
        when(bulkListingSuspendService.suspendAll(eq(42L), eq(9L), eq("FRAUD"), eq("fraud"), eq(999L)))
            .thenReturn(new BulkSuspendResult(UUID.randomUUID(), 7));

        service.issue(groupPid, 9L, SuspensionReason.FRAUD, "fraud", FIXED_NOW.plusHours(24), true);

        // The group-level notes ("fraud") must cascade into the bulk-listings
        // service so listing-level audit rows carry the same admin context as
        // the group-level audit row.
        verify(bulkListingSuspendService).suspendAll(eq(42L), eq(9L), eq("FRAUD"), eq("fraud"), eq(999L));
    }

    @Test
    void issue_withNullExpiresAt_writesPermanentBanWithAdminActionTypeBan() {
        RealtyGroup group = buildGroup(42L);
        UUID groupPid = group.getPublicId();
        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(group));
        when(suspensions.findActiveByGroupId(eq(42L), any(OffsetDateTime.class)))
            .thenReturn(Optional.empty());
        when(suspensions.save(any(RealtyGroupSuspension.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        RealtyGroupSuspension result = service.issue(
            groupPid, 9L, SuspensionReason.TOS_VIOLATION, "permaban", null, false);

        assertThat(result.getExpiresAt()).isNull();
        verify(adminActionService).record(
            eq(9L),
            eq(AdminActionType.REALTY_GROUP_BAN),
            eq(AdminActionTargetType.REALTY_GROUP),
            eq(42L),
            any(), any());
        verify(notificationPublisher).realtyGroupSuspended(eq(group), eq("TOS_VIOLATION"), isNull());

        // Redis string for a permanent ban uses the "PERMANENT" marker; TTL is capped
        // at REDIS_MAX_TTL (5 minutes) per spec §8.
        verify(valueOps).set(eq("realty_group_suspended:42"), eq("PERMANENT"), eq(Duration.ofMinutes(5)));
    }

    // ─────────────────── lift() ───────────────────

    @Test
    void lift_happyPath_setsLiftedAtAndAdminAndNotifies() {
        RealtyGroup group = buildGroup(42L);
        UUID groupPid = group.getPublicId();
        RealtyGroupSuspension active = RealtyGroupSuspension.builder()
            .realtyGroup(group)
            .issuedByAdmin(buildAdmin(7L))
            .reason(SuspensionReason.FRAUD)
            .issuedAt(FIXED_NOW.minusHours(6))
            .expiresAt(FIXED_NOW.plusHours(18))
            .build();
        setId(active, 555L);
        UUID susPid = active.getPublicId();
        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(group));
        when(suspensions.findByPublicId(susPid)).thenReturn(Optional.of(active));
        when(suspensions.save(any(RealtyGroupSuspension.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        RealtyGroupSuspension result = service.lift(
            groupPid, susPid, 9L, "false positive, lifted", false);

        assertThat(result.getLiftedAt()).isEqualTo(FIXED_NOW);
        assertThat(result.getLiftedByAdmin().getId()).isEqualTo(9L);
        assertThat(result.getLiftedNotes()).isEqualTo("false positive, lifted");

        verify(adminActionService).record(
            eq(9L),
            eq(AdminActionType.REALTY_GROUP_UNSUSPEND),
            eq(AdminActionTargetType.REALTY_GROUP),
            eq(42L),
            any(), any());
        verify(notificationPublisher).realtyGroupUnsuspended(eq(group));
        verify(redis).delete(eq("realty_group_suspended:42"));
        verify(bulkListingSuspendService, never()).reinstateAll(any(), any(), any());
    }

    @Test
    void lift_whenAlreadyLifted_throwsSuspensionNotFound() {
        RealtyGroup group = buildGroup(42L);
        UUID groupPid = group.getPublicId();
        RealtyGroupSuspension lifted = RealtyGroupSuspension.builder()
            .realtyGroup(group)
            .issuedByAdmin(buildAdmin(7L))
            .reason(SuspensionReason.FRAUD)
            .issuedAt(FIXED_NOW.minusHours(6))
            .expiresAt(FIXED_NOW.plusHours(18))
            .liftedAt(FIXED_NOW.minusHours(1))
            .liftedByAdmin(buildAdmin(7L))
            .build();
        UUID susPid = lifted.getPublicId();
        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(group));
        when(suspensions.findByPublicId(susPid)).thenReturn(Optional.of(lifted));

        assertThatThrownBy(() -> service.lift(groupPid, susPid, 9L, "again", false))
            .isInstanceOf(SuspensionNotFoundException.class);

        verify(suspensions, never()).save(any());
        verify(notificationPublisher, never()).realtyGroupUnsuspended(any());
        verify(redis, never()).delete(anyString());
    }

    @Test
    void lift_withBulkReinstateListings_invokesBulkListingSuspendService() {
        RealtyGroup group = buildGroup(42L);
        UUID groupPid = group.getPublicId();
        RealtyGroupSuspension active = RealtyGroupSuspension.builder()
            .realtyGroup(group)
            .issuedByAdmin(buildAdmin(7L))
            .reason(SuspensionReason.FRAUD)
            .issuedAt(FIXED_NOW.minusHours(6))
            .expiresAt(FIXED_NOW.plusHours(18))
            .build();
        UUID susPid = active.getPublicId();
        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(group));
        when(suspensions.findByPublicId(susPid)).thenReturn(Optional.of(active));
        when(suspensions.save(any(RealtyGroupSuspension.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        when(bulkListingSuspendService.reinstateAll(eq(42L), eq(9L), anyString()))
            .thenReturn(3);

        service.lift(groupPid, susPid, 9L, "reinstate listings too", true);

        verify(bulkListingSuspendService).reinstateAll(eq(42L), eq(9L), eq("reinstate listings too"));
    }

    // ─────────────────── findActive() ───────────────────

    @Test
    void findActive_returnsActiveRow_orEmpty() {
        RealtyGroup group = buildGroup(42L);
        RealtyGroupSuspension active = RealtyGroupSuspension.builder()
            .realtyGroup(group)
            .reason(SuspensionReason.FRAUD)
            .issuedAt(FIXED_NOW.minusHours(2))
            .expiresAt(FIXED_NOW.plusHours(24))
            .build();
        when(suspensions.findActiveByGroupId(eq(42L), any(OffsetDateTime.class)))
            .thenReturn(Optional.of(active));
        assertThat(service.findActive(42L)).hasValue(active);

        when(suspensions.findActiveByGroupId(eq(99L), any(OffsetDateTime.class)))
            .thenReturn(Optional.empty());
        assertThat(service.findActive(99L)).isEmpty();
    }

    // ─────────────────── listHistory() guard test (sanity, not in plan §step 1 list) ───────────────────

    @Test
    void listHistory_returnsRepositoryResult() {
        RealtyGroup group = buildGroup(42L);
        UUID groupPid = group.getPublicId();
        RealtyGroupSuspension a = RealtyGroupSuspension.builder()
            .realtyGroup(group).reason(SuspensionReason.FRAUD)
            .issuedAt(FIXED_NOW.minusHours(48)).build();
        RealtyGroupSuspension b = RealtyGroupSuspension.builder()
            .realtyGroup(group).reason(SuspensionReason.ABUSE)
            .issuedAt(FIXED_NOW.minusHours(2)).build();
        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(group));
        when(suspensions.findHistoryByGroupId(42L)).thenReturn(List.of(b, a));

        assertThat(service.listHistory(groupPid)).containsExactly(b, a);
    }

    // Suppress unused-import warning when Instant goes unreferenced after refactors.
    @SuppressWarnings("unused")
    private static void touchInstant() {
        Instant.now();
    }
}
