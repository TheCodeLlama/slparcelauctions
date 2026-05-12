package com.slparcelauctions.backend.realty.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Repository test for {@link RealtyGroupSuspensionRepository}. Mirrors the
 * {@code @SpringBootTest} + {@code @ActiveProfiles("dev")} pattern used by
 * {@link com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroupRepositoryTest} —
 * this codebase does not use {@code @DataJpaTest} because of Postgres-specific
 * features in the schema (partial indexes, JSONB, citext, etc.).
 *
 * <p>Spec: §8, plan Task 3.
 *
 * <p>Test cleanup uses the {@code rgs-susp-%@test.local} email pattern to scope
 * test-row deletion to this class.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.realty.invitation-expiry.enabled=false"
})
class RealtyGroupSuspensionRepositoryTest {

    @Autowired UserRepository userRepository;
    @Autowired RealtyGroupRepository realtyGroupRepository;
    @Autowired RealtyGroupSuspensionRepository repository;
    @Autowired DataSource dataSource;

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                stmt.execute("""
                    DELETE FROM realty_group_suspensions
                     WHERE realty_group_id IN (
                       SELECT id FROM realty_groups WHERE leader_id IN
                         (SELECT id FROM users WHERE email LIKE 'rgs-susp-%@test.local'))
                        OR issued_by_admin_id IN
                         (SELECT id FROM users WHERE email LIKE 'rgs-susp-%@test.local')
                    """);
                stmt.execute("""
                    DELETE FROM realty_group_members
                     WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'rgs-susp-%@test.local')
                    """);
                stmt.execute("""
                    DELETE FROM realty_groups
                     WHERE leader_id IN (SELECT id FROM users WHERE email LIKE 'rgs-susp-%@test.local')
                    """);
                stmt.execute("DELETE FROM users WHERE email LIKE 'rgs-susp-%@test.local'");
            }
        }
    }

    @Test
    void findByPublicId_returnsTheSavedRow() {
        Fixture f = newFixture();
        RealtyGroupSuspension s = repository.save(buildTimedSuspension(f, OffsetDateTime.now().plusHours(48)));

        var found = repository.findByPublicId(s.getPublicId());
        assertThat(found).isPresent();
        assertThat(found.get().getRealtyGroup().getId()).isEqualTo(f.group.getId());
        assertThat(found.get().getReason()).isEqualTo(SuspensionReason.FRAUD);
    }

    @Test
    void findActiveByGroupId_returnsActiveTimedRow() {
        Fixture f = newFixture();
        OffsetDateTime now = OffsetDateTime.now();
        repository.save(buildTimedSuspension(f, now.plusHours(48)));

        var found = repository.findActiveByGroupId(f.group.getId(), now);
        assertThat(found).isPresent();
        assertThat(found.get().getLiftedAt()).isNull();
    }

    @Test
    void findActiveByGroupId_returnsActivePermanentRow() {
        Fixture f = newFixture();
        OffsetDateTime now = OffsetDateTime.now();
        RealtyGroupSuspension permanent = buildTimedSuspension(f, null); // expiresAt == null
        repository.save(permanent);

        var found = repository.findActiveByGroupId(f.group.getId(), now);
        assertThat(found).isPresent();
        assertThat(found.get().getExpiresAt()).isNull();
    }

    @Test
    void findActiveByGroupId_skipsExpiredRow() {
        Fixture f = newFixture();
        OffsetDateTime now = OffsetDateTime.now();
        repository.save(buildTimedSuspension(f, now.minusHours(1)));

        assertThat(repository.findActiveByGroupId(f.group.getId(), now)).isEmpty();
    }

    @Test
    void findActiveByGroupId_skipsLiftedRow() {
        Fixture f = newFixture();
        OffsetDateTime now = OffsetDateTime.now();
        RealtyGroupSuspension lifted = buildTimedSuspension(f, now.plusHours(48));
        lifted.setLiftedAt(now.minusMinutes(5));
        lifted.setLiftedByAdmin(f.admin);
        repository.save(lifted);

        assertThat(repository.findActiveByGroupId(f.group.getId(), now)).isEmpty();
    }

    @Test
    void findHistoryByGroupId_returnsAllRowsNewestFirst() {
        Fixture f = newFixture();
        OffsetDateTime base = OffsetDateTime.now().minusHours(10);

        RealtyGroupSuspension older = buildTimedSuspension(f, base.plusHours(48));
        older.setIssuedAt(base);
        repository.save(older);

        RealtyGroupSuspension newer = buildTimedSuspension(f, base.plusHours(60));
        newer.setIssuedAt(base.plusHours(5));
        repository.save(newer);

        var history = repository.findHistoryByGroupId(f.group.getId());
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getIssuedAt()).isAfter(history.get(1).getIssuedAt());
    }

    @Test
    void findExpired_returnsOnlyUnliftedTimedRowsPastExpiry() {
        Fixture f = newFixture();
        OffsetDateTime now = OffsetDateTime.now();

        // expired + unlifted -> should match
        RealtyGroupSuspension expiredUnlifted = repository.save(
                buildTimedSuspension(f, now.minusHours(1)));

        // expired + already lifted -> filtered out
        RealtyGroupSuspension expiredLifted = buildTimedSuspension(f, now.minusHours(2));
        expiredLifted.setLiftedAt(now.minusMinutes(1));
        expiredLifted.setLiftedByAdmin(f.admin);
        repository.save(expiredLifted);

        // permanent ban (expiresAt == null) -> filtered out
        repository.save(buildTimedSuspension(f, null));

        // not yet expired -> filtered out
        repository.save(buildTimedSuspension(f, now.plusHours(48)));

        var expired = repository.findExpired(now);
        assertThat(expired).hasSize(1);
        assertThat(expired.get(0).getId()).isEqualTo(expiredUnlifted.getId());
    }

    // ─────────────────────── helpers ───────────────────────

    private record Fixture(User leader, User admin, RealtyGroup group) {}

    private Fixture newFixture() {
        User leader = userRepository.save(User.builder()
            .username("rgs-susp-l-" + UUID.randomUUID().toString().substring(0, 6))
            .email("rgs-susp-l-" + UUID.randomUUID() + "@test.local")
            .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
            .displayName("leader")
            .verified(true)
            .slAvatarUuid(UUID.randomUUID())
            .build());
        User admin = userRepository.save(User.builder()
            .username("rgs-susp-a-" + UUID.randomUUID().toString().substring(0, 6))
            .email("rgs-susp-a-" + UUID.randomUUID() + "@test.local")
            .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
            .displayName("admin")
            .verified(true)
            .slAvatarUuid(UUID.randomUUID())
            .build());
        RealtyGroup group = realtyGroupRepository.save(RealtyGroup.builder()
            .name("RG Susp Test " + suffix())
            .slug("rgs-susp-" + UUID.randomUUID().toString().substring(0, 8))
            .leaderId(leader.getId())
            .build());
        return new Fixture(leader, admin, group);
    }

    private RealtyGroupSuspension buildTimedSuspension(Fixture f, OffsetDateTime expiresAt) {
        return RealtyGroupSuspension.builder()
            .realtyGroup(f.group)
            .issuedByAdmin(f.admin)
            .reason(SuspensionReason.FRAUD)
            .notes("test")
            .issuedAt(OffsetDateTime.now())
            .expiresAt(expiresAt)
            .build();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
