package com.slparcelauctions.backend.realty.reports;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Repository test for {@link RealtyGroupReportRepository}. Mirrors the
 * {@code @SpringBootTest} + {@code @ActiveProfiles("dev")} pattern used by
 * {@link com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroupRepositoryTest} —
 * this codebase does not use {@code @DataJpaTest} because of Postgres-specific
 * features in the schema (partial indexes, JSONB, citext, etc.).
 *
 * <p>Spec: §8, plan Task 3.
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
class RealtyGroupReportRepositoryTest {

    @Autowired UserRepository userRepository;
    @Autowired RealtyGroupRepository realtyGroupRepository;
    @Autowired RealtyGroupReportRepository repository;
    @Autowired DataSource dataSource;

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                stmt.execute("""
                    DELETE FROM realty_group_reports
                     WHERE realty_group_id IN (
                       SELECT id FROM realty_groups WHERE leader_id IN
                         (SELECT id FROM users WHERE email LIKE 'rgr-rep-%@test.local'))
                        OR reporter_user_id IN
                         (SELECT id FROM users WHERE email LIKE 'rgr-rep-%@test.local')
                    """);
                stmt.execute("""
                    DELETE FROM realty_group_members
                     WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'rgr-rep-%@test.local')
                    """);
                stmt.execute("""
                    DELETE FROM realty_groups
                     WHERE leader_id IN (SELECT id FROM users WHERE email LIKE 'rgr-rep-%@test.local')
                    """);
                stmt.execute("DELETE FROM users WHERE email LIKE 'rgr-rep-%@test.local'");
            }
        }
    }

    @Test
    void findByPublicId_returnsTheSavedRow() {
        Fixture f = newFixture();
        RealtyGroupReport r = repository.save(buildReport(f, RealtyGroupReportStatus.OPEN));

        var found = repository.findByPublicId(r.getPublicId());
        assertThat(found).isPresent();
        assertThat(found.get().getReason()).isEqualTo(RealtyGroupReportReason.FRAUDULENT_LISTINGS);
    }

    @Test
    void existsOpenByGroupAndReporter_trueOnlyForOpenRows() {
        Fixture f = newFixture();
        repository.save(buildReport(f, RealtyGroupReportStatus.OPEN));

        assertThat(repository.existsOpenByGroupAndReporter(
                f.group.getId(), f.reporter.getId())).isTrue();

        // a different reporter has no open row
        Fixture other = newFixture();
        assertThat(repository.existsOpenByGroupAndReporter(
                f.group.getId(), other.reporter.getId())).isFalse();
    }

    @Test
    void existsOpenByGroupAndReporter_falseWhenStatusResolved() {
        Fixture f = newFixture();
        repository.save(buildReport(f, RealtyGroupReportStatus.RESOLVED));

        assertThat(repository.existsOpenByGroupAndReporter(
                f.group.getId(), f.reporter.getId())).isFalse();
    }

    @Test
    void findByStatus_pagesByStatus() {
        Fixture f = newFixture();
        repository.save(buildReport(f, RealtyGroupReportStatus.OPEN));
        repository.save(buildReport(newFixtureWithGroup(f.group), RealtyGroupReportStatus.OPEN));
        repository.save(buildReport(newFixtureWithGroup(f.group), RealtyGroupReportStatus.RESOLVED));

        Page<RealtyGroupReport> openPage = repository.findByStatus(
                RealtyGroupReportStatus.OPEN, PageRequest.of(0, 50));
        long openCountForThisGroup = openPage.getContent().stream()
                .filter(r -> r.getRealtyGroup().getId().equals(f.group.getId()))
                .count();
        assertThat(openCountForThisGroup).isEqualTo(2);

        Page<RealtyGroupReport> resolvedPage = repository.findByStatus(
                RealtyGroupReportStatus.RESOLVED, PageRequest.of(0, 50));
        long resolvedCountForThisGroup = resolvedPage.getContent().stream()
                .filter(r -> r.getRealtyGroup().getId().equals(f.group.getId()))
                .count();
        assertThat(resolvedCountForThisGroup).isEqualTo(1);
    }

    @Test
    void findByGroupId_returnsAllReportsNewestFirst() throws Exception {
        Fixture f = newFixture();
        RealtyGroupReport first = repository.save(buildReport(f, RealtyGroupReportStatus.OPEN));
        // small sleep ensures distinct createdAt at millisecond precision
        Thread.sleep(10);
        RealtyGroupReport second = repository.save(buildReport(
                newFixtureWithGroup(f.group), RealtyGroupReportStatus.OPEN));

        var rows = repository.findByGroupId(f.group.getId());
        assertThat(rows).extracting(RealtyGroupReport::getId)
                .containsExactly(second.getId(), first.getId());
    }

    // ─────────────────────── helpers ───────────────────────

    private record Fixture(User leader, User reporter, RealtyGroup group) {}

    private Fixture newFixture() {
        User leader = persistUser("l");
        User reporter = persistUser("r");
        RealtyGroup group = realtyGroupRepository.save(RealtyGroup.builder()
            .name("RG Report Test " + suffix())
            .slug("rgr-rep-" + UUID.randomUUID().toString().substring(0, 8))
            .leaderId(leader.getId())
            .build());
        return new Fixture(leader, reporter, group);
    }

    /** A second/Nth reporter on the same group — keeps the partial unique index happy. */
    private Fixture newFixtureWithGroup(RealtyGroup group) {
        return new Fixture(null, persistUser("r"), group);
    }

    private User persistUser(String roleTag) {
        return userRepository.save(User.builder()
            .username("rgr-rep-" + roleTag + "-" + UUID.randomUUID().toString().substring(0, 6))
            .email("rgr-rep-" + roleTag + "-" + UUID.randomUUID() + "@test.local")
            .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
            .displayName(roleTag)
            .verified(true)
            .slAvatarUuid(UUID.randomUUID())
            .build());
    }

    private RealtyGroupReport buildReport(Fixture f, RealtyGroupReportStatus status) {
        return RealtyGroupReport.builder()
            .realtyGroup(f.group)
            .reporter(f.reporter)
            .reason(RealtyGroupReportReason.FRAUDULENT_LISTINGS)
            .details("test details")
            .status(status)
            .build();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
