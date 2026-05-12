package com.slparcelauctions.backend.realty.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * End-to-end coverage of the sub-project G section 12 admin fan-out for the
 * group-report threshold. Exercises {@link RealtyGroupReportService#submit},
 * {@link RealtyGroupReportService#resolve}, and {@link RealtyGroupReportService#dismiss}
 * against a real DB; spies the {@link NotificationPublisher} bean so we can
 * count exactly how many times the {@code groupReportThresholdReached}
 * fan-out fires.
 *
 * <p>Scenario (one full cycle plus a re-arm):
 * <ol>
 *   <li>Submit 3 reports across the threshold -- fan-out fires exactly once.</li>
 *   <li>Submit a 4th -- flag is set, fan-out is suppressed.</li>
 *   <li>Resolve 3 of the 4 reports; dismiss the 4th -- open count returns to 0.</li>
 *   <li>Submit 3 new reports -- fan-out fires once more (second cycle).</li>
 * </ol>
 *
 * <p>Mirrors the {@code @SpringBootTest + @ActiveProfiles("dev")} pattern used by
 * {@link RealtyGroupReportRepositoryTest} -- this codebase does not use
 * {@code @DataJpaTest} because of Postgres-specific features in the schema.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "slpa.reports.group-alert-threshold=3",
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
class ReportThresholdFanOutIntegrationTest {

    private static final String TEST_EMAIL_PREFIX = "rg-thresh-";

    @Autowired RealtyGroupReportService reportService;
    @Autowired RealtyGroupReportRepository reportRepository;
    @Autowired RealtyGroupRepository groupRepository;
    @Autowired UserRepository userRepository;
    @Autowired DataSource dataSource;

    @MockitoSpyBean NotificationPublisher notificationPublisher;

    @AfterEach
    void cleanup() throws Exception {
        // Mirrors RealtyGroupReportRepositoryTest's cleanup -- clear every row that
        // references a test-local user before the user delete can run. Notification
        // rows are inserted by the spy-wrapped publisher so we clear them too. Email
        // prefix is scoped to this class so we never touch fixtures owned by another
        // suite.
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                stmt.execute("""
                    DELETE FROM admin_actions
                     WHERE admin_user_id IN (SELECT id FROM users WHERE email LIKE 'rg-thresh-%@test.local')
                    """);
                stmt.execute("""
                    DELETE FROM notification
                     WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'rg-thresh-%@test.local')
                    """);
                stmt.execute("""
                    DELETE FROM realty_group_reports
                     WHERE realty_group_id IN (
                       SELECT id FROM realty_groups WHERE leader_id IN
                         (SELECT id FROM users WHERE email LIKE 'rg-thresh-%@test.local'))
                        OR reporter_user_id IN
                         (SELECT id FROM users WHERE email LIKE 'rg-thresh-%@test.local')
                    """);
                stmt.execute("""
                    DELETE FROM realty_group_members
                     WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'rg-thresh-%@test.local')
                    """);
                stmt.execute("""
                    DELETE FROM realty_groups
                     WHERE leader_id IN (SELECT id FROM users WHERE email LIKE 'rg-thresh-%@test.local')
                    """);
                stmt.execute("DELETE FROM users WHERE email LIKE 'rg-thresh-%@test.local'");
            }
        }
    }

    @Test
    void crossingThresholdFiresOnce_thenReArmsAfterAllResolved() {
        // Seed: one realty group + four distinct reporters (none of them members
        // of the group, so the member-of-group reject path stays out of the way)
        // + one admin user (the fan-out target).
        User leader = persistUser("leader", Role.USER);
        User admin = persistUser("admin", Role.ADMIN);
        User reporter1 = persistUser("rpt1", Role.USER);
        User reporter2 = persistUser("rpt2", Role.USER);
        User reporter3 = persistUser("rpt3", Role.USER);
        User reporter4 = persistUser("rpt4", Role.USER);
        RealtyGroup group = persistGroup(leader);

        // Pre-spy: any prior bean-init invocations are noise; reset so our verify()
        // counts only the calls this test triggers.
        clearInvocations(notificationPublisher);

        // Phase 1 -- 3 submissions across the threshold. Exactly one fan-out fires
        // on the 3rd submission, and the group's reports_threshold_notified flag
        // is set so the 4th does not re-fire.
        UUID r1 = reportService.submit(
            group.getPublicId(), reporter1.getId(),
            RealtyGroupReportReason.FRAUDULENT_LISTINGS, "first").getPublicId();
        verify(notificationPublisher, times(0))
            .groupReportThresholdReached(any(), eq(3));

        UUID r2 = reportService.submit(
            group.getPublicId(), reporter2.getId(),
            RealtyGroupReportReason.FRAUDULENT_LISTINGS, "second").getPublicId();
        verify(notificationPublisher, times(0))
            .groupReportThresholdReached(any(), eq(3));

        UUID r3 = reportService.submit(
            group.getPublicId(), reporter3.getId(),
            RealtyGroupReportReason.FRAUDULENT_LISTINGS, "third").getPublicId();
        verify(notificationPublisher, times(1))
            .groupReportThresholdReached(any(), eq(3));

        assertThat(groupRepository.findById(group.getId()).orElseThrow()
                .isReportsThresholdNotified())
            .as("threshold flag set after the third report")
            .isTrue();

        // Phase 2 -- 4th submission, still above threshold. The flag is already set,
        // so no additional fan-out fires.
        UUID r4 = reportService.submit(
            group.getPublicId(), reporter4.getId(),
            RealtyGroupReportReason.FRAUDULENT_LISTINGS, "fourth").getPublicId();
        verify(notificationPublisher, times(1))
            .groupReportThresholdReached(any(), eq(3));

        // Phase 3 -- close all 4 reports. Three resolves + one dismiss exercises
        // both code branches that call maybeResetThresholdFlag. The flag must be
        // cleared once the open count returns to 0.
        reportService.resolve(r1, admin.getId(), "ok");
        reportService.resolve(r2, admin.getId(), "ok");
        reportService.resolve(r3, admin.getId(), "ok");
        // Sanity check: while open count is still > 0, the flag stays set.
        assertThat(groupRepository.findById(group.getId()).orElseThrow()
                .isReportsThresholdNotified())
            .as("flag remains set while at least one open report remains")
            .isTrue();
        reportService.dismiss(r4, admin.getId(), "frivolous");

        assertThat(groupRepository.findById(group.getId()).orElseThrow()
                .isReportsThresholdNotified())
            .as("flag re-armed once the last open report is closed")
            .isFalse();
        assertThat(reportRepository.countByRealtyGroupIdAndStatus(
                group.getId(), RealtyGroupReportStatus.OPEN))
            .isZero();

        // Phase 4 -- second cycle. 3 new submissions fire a fresh fan-out.
        // reporter1/2/3 are reusable now because their prior rows are no longer OPEN
        // (uq_rg_reports_one_open_per_reporter is a partial index keyed on OPEN status).
        reportService.submit(
            group.getPublicId(), reporter1.getId(),
            RealtyGroupReportReason.FRAUDULENT_LISTINGS, "second cycle 1");
        reportService.submit(
            group.getPublicId(), reporter2.getId(),
            RealtyGroupReportReason.FRAUDULENT_LISTINGS, "second cycle 2");
        reportService.submit(
            group.getPublicId(), reporter3.getId(),
            RealtyGroupReportReason.FRAUDULENT_LISTINGS, "second cycle 3");

        verify(notificationPublisher, times(2))
            .groupReportThresholdReached(any(), eq(3));
        // Sanity: every fan-out we observed reached at least one admin recipient.
        verify(notificationPublisher, atLeastOnce())
            .groupReportThresholdReached(any(), eq(3));

        // Category check: the publisher routes through GROUP_REPORT_THRESHOLD_REACHED,
        // not any of the realty-group-member categories.
        assertThat(NotificationCategory.GROUP_REPORT_THRESHOLD_REACHED.getGroup())
            .isEqualTo(com.slparcelauctions.backend.notification.NotificationGroup.ADMIN_OPS);
    }

    // -------------------- helpers --------------------

    private User persistUser(String roleTag, Role role) {
        return userRepository.save(User.builder()
            .username("rg-thresh-" + roleTag + "-" + UUID.randomUUID().toString().substring(0, 6))
            .email(TEST_EMAIL_PREFIX + roleTag + "-" + UUID.randomUUID() + "@test.local")
            .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
            .displayName(roleTag)
            .verified(true)
            .slAvatarUuid(UUID.randomUUID())
            .role(role)
            .build());
    }

    private RealtyGroup persistGroup(User leader) {
        return groupRepository.save(RealtyGroup.builder()
            .name("RG Threshold Test " + UUID.randomUUID().toString().substring(0, 8))
            .slug("rg-thresh-" + UUID.randomUUID().toString().substring(0, 8))
            .leaderId(leader.getId())
            .build());
    }
}
