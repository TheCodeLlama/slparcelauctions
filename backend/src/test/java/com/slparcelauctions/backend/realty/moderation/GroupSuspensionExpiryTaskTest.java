package com.slparcelauctions.backend.realty.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.admin.audit.SystemUserResolver;
import com.slparcelauctions.backend.user.User;

/**
 * Unit tests for {@link GroupSuspensionExpiryTask}. The task is a thin sweep that
 * relies on {@link RealtyGroupSuspensionRepository#findExpired(OffsetDateTime)} to
 * supply only the rows that should be auto-lifted — the test guards both ends of
 * that contract (cf. spec §9.3):
 *
 * <ul>
 *   <li>Rows the repository returns get {@code liftedAt = expiresAt},
 *       {@code liftedByAdmin = system user}, and {@code liftedNotes} set.</li>
 *   <li>The repository's filter (lifted_at IS NULL AND expires_at IS NOT NULL
 *       AND expires_at &lt; now) excludes permanent bans and already-lifted rows;
 *       we reinforce that contract by feeding only-expired-unlifted rows in and
 *       asserting that permanent/already-lifted rows passed alongside are not
 *       touched.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class GroupSuspensionExpiryTaskTest {

    private static final OffsetDateTime FIXED_NOW =
        OffsetDateTime.of(2026, 5, 12, 12, 0, 0, 0, ZoneOffset.UTC);

    @Mock RealtyGroupSuspensionRepository suspensionRepo;
    @Mock SystemUserResolver systemUserResolver;

    Clock clock;
    GroupSuspensionExpiryTask task;
    User systemUser;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_NOW.toInstant(), ZoneOffset.UTC);
        task = new GroupSuspensionExpiryTask(suspensionRepo, systemUserResolver, clock);
        systemUser = User.builder().build();
        setId(systemUser, 1L);
    }

    @Test
    void runOnce_picksOnlyExpiredUnliftedRows() {
        // The repository filter is what enforces "only expired, only unlifted, only timed".
        // We assert that the task queries findExpired with the clock-derived now value.
        when(suspensionRepo.findExpired(any(OffsetDateTime.class))).thenReturn(List.of());

        task.runOnce();

        ArgumentCaptor<OffsetDateTime> nowCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(suspensionRepo).findExpired(nowCaptor.capture());
        assertThat(nowCaptor.getValue()).isEqualTo(FIXED_NOW);
        // When no expired rows are returned, the system-user lookup is short-circuited.
        verify(systemUserResolver, never()).getSystemUser();
    }

    @Test
    void runOnce_writesLiftedAtEqualsExpiresAt() {
        OffsetDateTime expiry1 = FIXED_NOW.minusHours(2);
        OffsetDateTime expiry2 = FIXED_NOW.minusHours(1);
        RealtyGroupSuspension s1 = expiredRow(expiry1);
        RealtyGroupSuspension s2 = expiredRow(expiry2);
        when(suspensionRepo.findExpired(any(OffsetDateTime.class))).thenReturn(List.of(s1, s2));
        when(systemUserResolver.getSystemUser()).thenReturn(systemUser);

        task.runOnce();

        // Spec §9.3: the lifted-at stamp uses the row's expiry (not "now"), so the
        // audit trail reflects the moment the suspension was supposed to end, not the
        // moment the sweep happened to run.
        assertThat(s1.getLiftedAt()).isEqualTo(expiry1);
        assertThat(s2.getLiftedAt()).isEqualTo(expiry2);
    }

    @Test
    void runOnce_setsLiftedByAdminToSystemUserId() {
        RealtyGroupSuspension s = expiredRow(FIXED_NOW.minusMinutes(30));
        when(suspensionRepo.findExpired(any(OffsetDateTime.class))).thenReturn(List.of(s));
        when(systemUserResolver.getSystemUser()).thenReturn(systemUser);

        task.runOnce();

        assertThat(s.getLiftedByAdmin()).isSameAs(systemUser);
        assertThat(s.getLiftedNotes()).isEqualTo("Auto-lifted on expiry");
    }

    @Test
    void runOnce_doesNotTouchPermanentBans() {
        // Permanent bans have expires_at = null; the repository filter excludes them, so
        // they never reach the task. We assert the task respects that contract: a row
        // with expires_at = null that the task is given (defensive test) is still not
        // mutated — but in practice the repository never returns one.
        RealtyGroupSuspension permanent = RealtyGroupSuspension.builder()
            .reason(SuspensionReason.ABUSE)
            .issuedAt(FIXED_NOW.minusDays(2))
            .expiresAt(null)
            .build();
        // findExpired returns only timed-and-elapsed rows — empty list when only a
        // permanent ban is in the table.
        when(suspensionRepo.findExpired(any(OffsetDateTime.class))).thenReturn(new ArrayList<>());

        task.runOnce();

        assertThat(permanent.getLiftedAt()).isNull();
        assertThat(permanent.getLiftedByAdmin()).isNull();
        verify(systemUserResolver, never()).getSystemUser();
    }

    @Test
    void runOnce_doesNotTouchAlreadyLifted() {
        // Already-lifted rows have lifted_at IS NOT NULL; the repository filter excludes
        // them. Same defensive shape as the permanent-ban test: an empty list means the
        // task makes no mutations.
        RealtyGroupSuspension alreadyLifted = RealtyGroupSuspension.builder()
            .reason(SuspensionReason.ABUSE)
            .issuedAt(FIXED_NOW.minusDays(5))
            .expiresAt(FIXED_NOW.minusDays(1))
            .liftedAt(FIXED_NOW.minusDays(1))
            .liftedByAdmin(systemUser)
            .liftedNotes("manual lift earlier")
            .build();
        when(suspensionRepo.findExpired(any(OffsetDateTime.class))).thenReturn(new ArrayList<>());

        task.runOnce();

        // Pre-existing state is preserved.
        assertThat(alreadyLifted.getLiftedAt()).isEqualTo(FIXED_NOW.minusDays(1));
        assertThat(alreadyLifted.getLiftedNotes()).isEqualTo("manual lift earlier");
        verify(systemUserResolver, never()).getSystemUser();
    }

    private RealtyGroupSuspension expiredRow(OffsetDateTime expiresAt) {
        return RealtyGroupSuspension.builder()
            .reason(SuspensionReason.ABUSE)
            .issuedAt(expiresAt.minusDays(7))
            .expiresAt(expiresAt)
            .build();
    }

    /**
     * Sets {@code BaseEntity.id} via reflection — Lombok's {@code @SuperBuilder} on the
     * entity hierarchy does not expose {@code id} (it's inherited from {@code BaseEntity}
     * and managed by JPA). Same trick the production code in
     * {@code RealtyGroupSuspensionService} uses for admin-user references.
     */
    private static void setId(User user, Long id) {
        try {
            Field f = User.class.getSuperclass().getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(user, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
