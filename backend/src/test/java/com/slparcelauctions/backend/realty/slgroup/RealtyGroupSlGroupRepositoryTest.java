package com.slparcelauctions.backend.realty.slgroup;

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
import com.slparcelauctions.backend.realty.moderation.RealtyGroupSuspension;
import com.slparcelauctions.backend.realty.moderation.RealtyGroupSuspensionRepository;
import com.slparcelauctions.backend.realty.moderation.SuspensionReason;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Repository test for {@link RealtyGroupSlGroupRepository}. Mirrors the
 * {@code @SpringBootTest} + {@code @ActiveProfiles("dev")} pattern used by
 * {@code RealtyGroupFkIntegrationTest} — this codebase does not use {@code @DataJpaTest}.
 *
 * <p>Spec: §3.1, plan Task 2.
 *
 * <p>Test cleanup uses the {@code rgslg-%@test.local} email pattern to scope test-row
 * deletion to this class.
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
class RealtyGroupSlGroupRepositoryTest {

    @Autowired UserRepository userRepository;
    @Autowired RealtyGroupRepository realtyGroupRepository;
    @Autowired RealtyGroupSlGroupRepository repository;
    @Autowired RealtyGroupSuspensionRepository suspensionRepository;
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
                         (SELECT id FROM users WHERE email LIKE 'rgslg-%@test.local'))
                        OR issued_by_admin_id IN
                         (SELECT id FROM users WHERE email LIKE 'rgslg-%@test.local')
                    """);
                stmt.execute("""
                    DELETE FROM realty_group_sl_groups
                     WHERE realty_group_id IN (
                       SELECT id FROM realty_groups WHERE leader_id IN
                         (SELECT id FROM users WHERE email LIKE 'rgslg-%@test.local'))
                    """);
                stmt.execute("""
                    DELETE FROM realty_group_members
                     WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'rgslg-%@test.local')
                    """);
                stmt.execute("""
                    DELETE FROM realty_groups
                     WHERE leader_id IN (SELECT id FROM users WHERE email LIKE 'rgslg-%@test.local')
                    """);
                stmt.execute("DELETE FROM users WHERE email LIKE 'rgslg-%@test.local'");
            }
        }
    }

    @Test
    void persistsAndRetrievesByPublicId() {
        RealtyGroup group = persistGroup();

        RealtyGroupSlGroup row = RealtyGroupSlGroup.builder()
            .realtyGroupId(group.getId())
            .slGroupUuid(UUID.randomUUID())
            .slGroupName("My SL Group " + suffix())
            .verified(false)
            .verificationCode("ABC123")
            .verificationCodeExpiresAt(OffsetDateTime.now().plusHours(24))
            .build();
        UUID capturedPublicId = row.getPublicId();

        RealtyGroupSlGroup saved = repository.save(row);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getPublicId()).isEqualTo(capturedPublicId);

        var found = repository.findByPublicId(capturedPublicId);
        assertThat(found).isPresent();
        assertThat(found.get().getRealtyGroupId()).isEqualTo(group.getId());
        assertThat(found.get().getSlGroupUuid()).isEqualTo(row.getSlGroupUuid());
        assertThat(found.get().isVerified()).isFalse();
    }

    @Test
    void findVerifiedForListing_returnsOnlyVerifiedRows() {
        RealtyGroup group = persistGroup();
        UUID slGroupUuid = UUID.randomUUID();

        RealtyGroupSlGroup pending = RealtyGroupSlGroup.builder()
            .realtyGroupId(group.getId())
            .slGroupUuid(slGroupUuid)
            .slGroupName("Pending " + suffix())
            .verified(false)
            .verificationCode("XYZ789")
            .verificationCodeExpiresAt(OffsetDateTime.now().plusHours(24))
            .build();
        pending = repository.save(pending);

        assertThat(repository.findVerifiedForListing(group.getId(), slGroupUuid))
            .as("pending row must not satisfy the listing gate")
            .isEmpty();

        pending.setVerified(true);
        pending.setVerifiedAt(OffsetDateTime.now());
        pending.setVerifiedVia(SlGroupVerifyMethod.FOUNDER_TERMINAL);
        repository.save(pending);

        assertThat(repository.findVerifiedForListing(group.getId(), slGroupUuid))
            .as("verified row should be returned")
            .isPresent();
    }

    /**
     * Sub-project G section 14 -- reverse-search ban-evasion gate. The query
     * returns {@code true} only when the SL group UUID is currently registered
     * to a realty group that has an active (unlifted) suspension row.
     */
    @Test
    void existsForSuspendedRealtyGroup_trueWhenRegisteredOnSuspendedGroup() {
        RealtyGroup group = persistGroup();
        User admin = persistAdmin();
        UUID slGroupUuid = UUID.randomUUID();

        repository.save(RealtyGroupSlGroup.builder()
            .realtyGroupId(group.getId())
            .slGroupUuid(slGroupUuid)
            .slGroupName("Pending " + suffix())
            .verified(false)
            .verificationCode("CODE-" + suffix())
            .verificationCodeExpiresAt(OffsetDateTime.now().plusHours(24))
            .build());

        // No suspension yet -> gate is open.
        assertThat(repository.existsForSuspendedRealtyGroup(slGroupUuid)).isFalse();

        // Active (unlifted) suspension on the realty group -> gate fires.
        suspensionRepository.save(RealtyGroupSuspension.builder()
            .realtyGroup(group)
            .issuedByAdmin(admin)
            .reason(SuspensionReason.FRAUD)
            .notes("test")
            .issuedAt(OffsetDateTime.now())
            .expiresAt(null) // permanent ban
            .build());

        assertThat(repository.existsForSuspendedRealtyGroup(slGroupUuid)).isTrue();
    }

    @Test
    void existsForSuspendedRealtyGroup_falseWhenSuspensionIsLifted() {
        RealtyGroup group = persistGroup();
        User admin = persistAdmin();
        UUID slGroupUuid = UUID.randomUUID();

        repository.save(RealtyGroupSlGroup.builder()
            .realtyGroupId(group.getId())
            .slGroupUuid(slGroupUuid)
            .slGroupName("Pending " + suffix())
            .verified(false)
            .verificationCode("CODE-" + suffix())
            .verificationCodeExpiresAt(OffsetDateTime.now().plusHours(24))
            .build());

        RealtyGroupSuspension lifted = RealtyGroupSuspension.builder()
            .realtyGroup(group)
            .issuedByAdmin(admin)
            .reason(SuspensionReason.FRAUD)
            .notes("test")
            .issuedAt(OffsetDateTime.now().minusDays(2))
            .expiresAt(null)
            .liftedAt(OffsetDateTime.now().minusDays(1))
            .liftedByAdmin(admin)
            .build();
        suspensionRepository.save(lifted);

        // Lifted suspension is not active -> gate stays open.
        assertThat(repository.existsForSuspendedRealtyGroup(slGroupUuid)).isFalse();
    }

    @Test
    void existsForSuspendedRealtyGroup_falseWhenUuidNotRegistered() {
        // No SL group registration at all -> nothing to find.
        assertThat(repository.existsForSuspendedRealtyGroup(UUID.randomUUID())).isFalse();
    }

    // ─────────────────────── helpers ───────────────────────

    private User persistAdmin() {
        return userRepository.save(User.builder()
            .username("rgslg-a-" + UUID.randomUUID().toString().substring(0, 6))
            .email("rgslg-a-" + UUID.randomUUID() + "@test.local")
            .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
            .displayName("admin")
            .verified(true)
            .slAvatarUuid(UUID.randomUUID())
            .build());
    }

    private RealtyGroup persistGroup() {
        User leader = userRepository.save(User.builder()
            .username("rgslg-" + UUID.randomUUID().toString().substring(0, 8))
            .email("rgslg-" + UUID.randomUUID() + "@test.local")
            .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
            .displayName("leader")
            .verified(true)
            .slAvatarUuid(UUID.randomUUID())
            .build());
        return realtyGroupRepository.save(RealtyGroup.builder()
            .name("RG SLG Test " + suffix())
            .slug("rgslg-" + UUID.randomUUID().toString().substring(0, 8))
            .leaderId(leader.getId())
            .build());
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
