package com.slparcelauctions.backend.realty.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.reports.exception.AlreadyReportedException;
import com.slparcelauctions.backend.realty.reports.exception.CannotReportOwnGroupException;
import com.slparcelauctions.backend.realty.reports.exception.ReportNotFoundException;
import com.slparcelauctions.backend.realty.reports.exception.ReportRateLimitedException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Unit tests for {@link RealtyGroupReportService}. Mockito-driven, fixed clock for
 * determinism. Covers spec §8 / §12.1 / §12.3 behaviour: pre-checks (group exists,
 * not a member, under quota), unique-violation translation, admin-action records
 * on resolve/dismiss, and the dismissed-counter bump on dismissal.
 */
@ExtendWith(MockitoExtension.class)
class RealtyGroupReportServiceTest {

    private static final OffsetDateTime FIXED_NOW =
        OffsetDateTime.of(2026, 5, 12, 10, 0, 0, 0, ZoneOffset.UTC);

    @Mock RealtyGroupReportRepository reportRepo;
    @Mock RealtyGroupRepository groupRepo;
    @Mock RealtyGroupMemberRepository memberRepo;
    @Mock UserRepository userRepo;
    @Mock RealtyGroupReportRateLimiter rateLimiter;
    @Mock AdminActionService adminActionService;
    @Mock NotificationPublisher notificationPublisher;

    Clock clock;
    ReportsProperties reportsProps;
    RealtyGroupReportService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_NOW.toInstant(), ZoneOffset.UTC);
        // Real props bean -- the threshold-fan-out path reads getGroupAlertThreshold().
        // Default 3 is fine for these unit tests; the dedicated integration test
        // exercises the cross-threshold behaviour end-to-end.
        reportsProps = new ReportsProperties();
        service = new RealtyGroupReportService(
            reportRepo, groupRepo, memberRepo, userRepo,
            rateLimiter, adminActionService,
            notificationPublisher, reportsProps,
            clock);
        // userRepo.getReferenceById is used to populate FK proxies without a SELECT;
        // stub leniently so the happy paths don't NPE.
        lenient().when(userRepo.getReferenceById(any(Long.class)))
            .thenAnswer(inv -> {
                Long uid = inv.getArgument(0);
                User u = new User();
                setId(u, uid);
                return u;
            });
    }

    // ─────────────────── helpers ───────────────────

    private RealtyGroup buildGroup(Long id) {
        RealtyGroup g = RealtyGroup.builder()
            .name("Mainland Realty Co")
            .slug("mainland-realty-co")
            .leaderId(100L)
            .build();
        setId(g, id);
        return g;
    }

    private User buildUser(Long id) {
        User u = new User();
        u.setUsername("user-" + id);
        setId(u, id);
        return u;
    }

    private RealtyGroupReport buildReport(
            Long id, RealtyGroup group, User reporter, RealtyGroupReportStatus status) {
        RealtyGroupReport r = RealtyGroupReport.builder()
            .realtyGroup(group)
            .reporter(reporter)
            .reason(RealtyGroupReportReason.FRAUDULENT_LISTINGS)
            .details("misleading attribution on multiple listings")
            .status(status)
            .build();
        setId(r, id);
        return r;
    }

    /** Reflection helper: set the inherited Long id on a BaseEntity subclass. */
    private static void setId(Object entity, Long id) {
        try {
            java.lang.reflect.Field f = findIdField(entity.getClass());
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
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

    // ─────────────────── submit() ───────────────────

    @Test
    void submit_happyPath_insertsOpenRowAndReturnsDto() {
        RealtyGroup group = buildGroup(42L);
        UUID groupPid = group.getPublicId();
        when(groupRepo.findByPublicId(groupPid)).thenReturn(Optional.of(group));
        when(memberRepo.findByGroupIdAndUserId(42L, 7L)).thenReturn(Optional.empty());
        when(reportRepo.saveAndFlush(any(RealtyGroupReport.class)))
            .thenAnswer(inv -> {
                RealtyGroupReport r = inv.getArgument(0);
                setId(r, 1001L);
                return r;
            });

        RealtyGroupReport result = service.submit(
            groupPid, 7L, RealtyGroupReportReason.FRAUDULENT_LISTINGS, "details body");

        // Rate-limit gate ran exactly once, before the save.
        verify(rateLimiter).checkAndIncrement(7L);

        // Captured row reflects the submission shape.
        ArgumentCaptor<RealtyGroupReport> cap = ArgumentCaptor.forClass(RealtyGroupReport.class);
        verify(reportRepo).saveAndFlush(cap.capture());
        RealtyGroupReport saved = cap.getValue();
        assertThat(saved.getRealtyGroup()).isSameAs(group);
        assertThat(saved.getReporter().getId()).isEqualTo(7L);
        assertThat(saved.getReason()).isEqualTo(RealtyGroupReportReason.FRAUDULENT_LISTINGS);
        assertThat(saved.getDetails()).isEqualTo("details body");
        assertThat(saved.getStatus()).isEqualTo(RealtyGroupReportStatus.OPEN);
        assertThat(saved.getResolvedAt()).isNull();
        assertThat(saved.getResolvedByAdmin()).isNull();

        assertThat(result).isSameAs(saved);
    }

    @Test
    void submit_whenGroupNotFound_throwsRealtyGroupNotFound() {
        UUID groupPid = UUID.randomUUID();
        when(groupRepo.findByPublicId(groupPid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(
                groupPid, 7L, RealtyGroupReportReason.HARASSMENT, "details"))
            .isInstanceOf(RealtyGroupNotFoundException.class);

        verify(rateLimiter, never()).checkAndIncrement(any());
        verify(reportRepo, never()).saveAndFlush(any());
    }

    @Test
    void submit_whenReporterIsMember_throwsCannotReportOwnGroup() {
        RealtyGroup group = buildGroup(42L);
        UUID groupPid = group.getPublicId();
        when(groupRepo.findByPublicId(groupPid)).thenReturn(Optional.of(group));
        // Member rows have an id but the service only checks for presence.
        when(memberRepo.findByGroupIdAndUserId(42L, 7L))
            .thenReturn(Optional.of(new RealtyGroupMember()));

        assertThatThrownBy(() -> service.submit(
                groupPid, 7L, RealtyGroupReportReason.HARASSMENT, "details"))
            .isInstanceOf(CannotReportOwnGroupException.class);

        // Member-of-group rejection short-circuits BEFORE the rate-limiter gate so
        // an internal-dispute spammer can't drain their daily quota by repeatedly
        // hitting their own group.
        verify(rateLimiter, never()).checkAndIncrement(any());
        verify(reportRepo, never()).saveAndFlush(any());
    }

    @Test
    void submit_whenAlreadyOpenReport_throwsAlreadyReported() {
        RealtyGroup group = buildGroup(42L);
        UUID groupPid = group.getPublicId();
        when(groupRepo.findByPublicId(groupPid)).thenReturn(Optional.of(group));
        when(memberRepo.findByGroupIdAndUserId(42L, 7L)).thenReturn(Optional.empty());
        // Simulate the partial-unique index tripping on a racy second submission.
        when(reportRepo.saveAndFlush(any(RealtyGroupReport.class)))
            .thenThrow(new DataIntegrityViolationException(
                "duplicate key value violates uq_rg_reports_one_open_per_reporter"));

        assertThatThrownBy(() -> service.submit(
                groupPid, 7L, RealtyGroupReportReason.SPAM, "dup"))
            .isInstanceOf(AlreadyReportedException.class);

        verify(rateLimiter).checkAndIncrement(7L);
    }

    @Test
    void submit_whenRateLimited_throws429RateLimitedException() {
        RealtyGroup group = buildGroup(42L);
        UUID groupPid = group.getPublicId();
        when(groupRepo.findByPublicId(groupPid)).thenReturn(Optional.of(group));
        when(memberRepo.findByGroupIdAndUserId(42L, 7L)).thenReturn(Optional.empty());
        doThrow(new ReportRateLimitedException(5))
            .when(rateLimiter).checkAndIncrement(7L);

        assertThatThrownBy(() -> service.submit(
                groupPid, 7L, RealtyGroupReportReason.SPAM, "details"))
            .isInstanceOf(ReportRateLimitedException.class);

        // Rate-limit denial short-circuits before the DB write.
        verify(reportRepo, never()).saveAndFlush(any());
    }

    // ─────────────────── resolve() ───────────────────

    @Test
    void resolve_setsResolvedAtAndAdmin() {
        RealtyGroup group = buildGroup(42L);
        User reporter = buildUser(7L);
        RealtyGroupReport report = buildReport(1001L, group, reporter, RealtyGroupReportStatus.OPEN);
        UUID reportPid = report.getPublicId();
        when(reportRepo.findByPublicId(reportPid)).thenReturn(Optional.of(report));
        when(reportRepo.save(any(RealtyGroupReport.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        RealtyGroupReport result = service.resolve(reportPid, 9L, "valid complaint, suspended group");

        assertThat(result.getStatus()).isEqualTo(RealtyGroupReportStatus.RESOLVED);
        assertThat(result.getResolvedAt()).isEqualTo(FIXED_NOW);
        assertThat(result.getResolvedByAdmin().getId()).isEqualTo(9L);
        assertThat(result.getResolutionNotes()).isEqualTo("valid complaint, suspended group");
    }

    @Test
    void resolve_recordsAdminAction() {
        RealtyGroup group = buildGroup(42L);
        User reporter = buildUser(7L);
        RealtyGroupReport report = buildReport(1001L, group, reporter, RealtyGroupReportStatus.OPEN);
        UUID reportPid = report.getPublicId();
        when(reportRepo.findByPublicId(reportPid)).thenReturn(Optional.of(report));
        when(reportRepo.save(any(RealtyGroupReport.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        service.resolve(reportPid, 9L, "notes here");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> evidenceCap = ArgumentCaptor.forClass(Map.class);
        verify(adminActionService).record(
            eq(9L),
            eq(AdminActionType.REALTY_GROUP_REPORT_RESOLVE),
            eq(AdminActionTargetType.REPORT),
            eq(1001L),
            eq("notes here"),
            evidenceCap.capture());
        Map<String, Object> evidence = evidenceCap.getValue();
        assertThat(evidence).containsEntry("reportPublicId", reportPid.toString());
        assertThat(evidence).containsEntry("groupId", 42L);
    }

    @Test
    void resolve_whenReportNotFound_throwsReportNotFound() {
        UUID reportPid = UUID.randomUUID();
        when(reportRepo.findByPublicId(reportPid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(reportPid, 9L, "notes"))
            .isInstanceOf(ReportNotFoundException.class);

        verify(adminActionService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void resolve_whenAlreadyResolved_throwsReportNotFound() {
        RealtyGroup group = buildGroup(42L);
        User reporter = buildUser(7L);
        // Already-resolved reports are treated as stale rows — surface as 404
        // for a consistent admin-UI experience.
        RealtyGroupReport report = buildReport(1001L, group, reporter, RealtyGroupReportStatus.RESOLVED);
        UUID reportPid = report.getPublicId();
        when(reportRepo.findByPublicId(reportPid)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.resolve(reportPid, 9L, "again"))
            .isInstanceOf(ReportNotFoundException.class);

        verify(reportRepo, never()).save(any());
        verify(adminActionService, never()).record(any(), any(), any(), any(), any(), any());
    }

    // ─────────────────── dismiss() ───────────────────

    @Test
    void dismiss_setsDismissedAndIncrementsDismissedReportsCount() {
        RealtyGroup group = buildGroup(42L);
        User reporter = buildUser(7L);
        reporter.setDismissedReportsCount(2L);
        RealtyGroupReport report = buildReport(1001L, group, reporter, RealtyGroupReportStatus.OPEN);
        UUID reportPid = report.getPublicId();
        when(reportRepo.findByPublicId(reportPid)).thenReturn(Optional.of(report));
        when(reportRepo.save(any(RealtyGroupReport.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        when(userRepo.findById(7L)).thenReturn(Optional.of(reporter));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        RealtyGroupReport result = service.dismiss(reportPid, 9L, "frivolous");

        assertThat(result.getStatus()).isEqualTo(RealtyGroupReportStatus.DISMISSED);
        assertThat(result.getResolvedAt()).isEqualTo(FIXED_NOW);
        assertThat(result.getResolvedByAdmin().getId()).isEqualTo(9L);
        assertThat(result.getResolutionNotes()).isEqualTo("frivolous");

        // The reporter's frivolous-counter is bumped — drives the abuse-of-report
        // feedback loop in §12.3.
        ArgumentCaptor<User> userCap = ArgumentCaptor.forClass(User.class);
        verify(userRepo).save(userCap.capture());
        assertThat(userCap.getValue().getDismissedReportsCount()).isEqualTo(3L);
    }

    @Test
    void dismiss_recordsAdminAction() {
        RealtyGroup group = buildGroup(42L);
        User reporter = buildUser(7L);
        RealtyGroupReport report = buildReport(1001L, group, reporter, RealtyGroupReportStatus.OPEN);
        UUID reportPid = report.getPublicId();
        when(reportRepo.findByPublicId(reportPid)).thenReturn(Optional.of(report));
        when(reportRepo.save(any(RealtyGroupReport.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        when(userRepo.findById(7L)).thenReturn(Optional.of(reporter));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.dismiss(reportPid, 9L, "dismiss notes");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> evidenceCap = ArgumentCaptor.forClass(Map.class);
        verify(adminActionService).record(
            eq(9L),
            eq(AdminActionType.REALTY_GROUP_REPORT_DISMISS),
            eq(AdminActionTargetType.REPORT),
            eq(1001L),
            eq("dismiss notes"),
            evidenceCap.capture());
        Map<String, Object> evidence = evidenceCap.getValue();
        assertThat(evidence).containsEntry("reportPublicId", reportPid.toString());
        assertThat(evidence).containsEntry("groupId", 42L);
    }

    @Test
    void dismiss_whenReportNotFound_throwsReportNotFound() {
        UUID reportPid = UUID.randomUUID();
        when(reportRepo.findByPublicId(reportPid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.dismiss(reportPid, 9L, "notes"))
            .isInstanceOf(ReportNotFoundException.class);

        verify(userRepo, never()).save(any());
        verify(adminActionService, never()).record(any(), any(), any(), any(), any(), any());
    }
}
