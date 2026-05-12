package com.slparcelauctions.backend.realty.reports;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.reports.exception.AlreadyReportedException;
import com.slparcelauctions.backend.realty.reports.exception.CannotReportOwnGroupException;
import com.slparcelauctions.backend.realty.reports.exception.ReportNotFoundException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Submit / resolve / dismiss user-filed reports against a realty group. Single source
 * of truth for the {@code realty_group_reports} table.
 *
 * <p>Behaviour per spec section 8, 12.1, 12.3:
 * <ul>
 *   <li>{@link #submit} enforces three pre-conditions before insert: target group must
 *       exist, reporter must not be a member of the group, and the reporter must be
 *       under the shared daily quota. A unique-violation on
 *       {@code uq_rg_reports_one_open_per_reporter} (when two requests race past the
 *       pre-check) is translated to {@link AlreadyReportedException}. After insert,
 *       {@link #tryFireThresholdNotification} fires the admin fan-out iff the new
 *       open-report count crossed {@code slpa.reports.group-alert-threshold} for the
 *       first time in the current cycle (sub-project G section 12.3, one-shot per cycle).</li>
 *   <li>{@link #resolve} flips an OPEN report to RESOLVED, stamps resolver/resolution
 *       fields, records a {@code REALTY_GROUP_REPORT_RESOLVE} admin action, and clears
 *       the threshold-notified flag when the open count returns to zero (re-arms the
 *       next cycle).</li>
 *   <li>{@link #dismiss} flips an OPEN report to DISMISSED, stamps resolver fields,
 *       increments {@link User#getDismissedReportsCount()} for the reporter (used by
 *       the frivolous-reporter counter), records a
 *       {@code REALTY_GROUP_REPORT_DISMISS} admin action, and clears the threshold-
 *       notified flag when the open count returns to zero.</li>
 * </ul>
 *
 * <p>Sub-project F spec section 8, 12.1, 12.3; sub-project G spec section 12.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RealtyGroupReportService {

    private final RealtyGroupReportRepository reportRepo;
    private final RealtyGroupRepository groupRepo;
    private final RealtyGroupMemberRepository memberRepo;
    private final UserRepository userRepo;
    private final RealtyGroupReportRateLimiter rateLimiter;
    private final AdminActionService adminActionService;
    private final NotificationPublisher notificationPublisher;
    private final ReportsProperties reportsProps;
    private final Clock clock;

    /**
     * Look up a single report by its public id. Used by the admin detail endpoint.
     * Read-only -- does not change report state.
     *
     * @throws ReportNotFoundException if {@code reportPublicId} resolves to nothing
     */
    @Transactional(readOnly = true)
    public RealtyGroupReport find(UUID reportPublicId) {
        return reportRepo.findByPublicId(reportPublicId)
            .orElseThrow(() -> new ReportNotFoundException(reportPublicId));
    }

    /**
     * Paginated query of reports filtered by status. When {@code status} is null the
     * full table is returned, ordered by {@code Pageable}'s sort. Used by the admin
     * moderation queue.
     */
    @Transactional(readOnly = true)
    public Page<RealtyGroupReport> findAll(
            RealtyGroupReportStatus status,
            Pageable pageable) {
        if (status == null) {
            return reportRepo.findAll(pageable);
        }
        return reportRepo.findByStatus(status, pageable);
    }

    /**
     * Submit a new report against a realty group. The reporter must be authenticated
     * (resolved upstream) and the group must exist. Members of the group cannot
     * report it. Each reporter is held to a shared daily quota with listing reports.
     *
     * @throws RealtyGroupNotFoundException   if {@code groupPublicId} resolves to nothing
     * @throws CannotReportOwnGroupException  if the reporter is a member of the group
     * @throws AlreadyReportedException       if the reporter already has an OPEN report
     *                                        against this group (race with another request)
     */
    @Transactional
    public RealtyGroupReport submit(
            UUID groupPublicId,
            Long reporterId,
            RealtyGroupReportReason reason,
            String details) {

        RealtyGroup group = groupRepo.findByPublicId(groupPublicId)
            .orElseThrow(() -> new RealtyGroupNotFoundException(groupPublicId));

        // Reject member-of-group reports up-front. Members have richer recourse paths
        // (fraud flags, leadership transfer, dispute the listing) and shouldn't be
        // funnelling internal disputes through the public report queue.
        if (memberRepo.findByGroupIdAndUserId(group.getId(), reporterId).isPresent()) {
            throw new CannotReportOwnGroupException(groupPublicId);
        }

        // Rate limit BEFORE we issue any DB write -- over-limit requests should
        // short-circuit before touching the transaction.
        rateLimiter.checkAndIncrement(reporterId);

        User reporter = userRepo.getReferenceById(reporterId);
        RealtyGroupReport row = RealtyGroupReport.builder()
            .realtyGroup(group)
            .reporter(reporter)
            .reason(reason)
            .details(details)
            .status(RealtyGroupReportStatus.OPEN)
            .build();

        try {
            RealtyGroupReport saved = reportRepo.saveAndFlush(row);
            // Sub-project G section 12.3 -- fire the threshold fan-out iff this submission
            // pushed the open-report count across the configured threshold for the
            // first time in the current cycle. Idempotent inside the submit tx; no-op
            // when the flag is already set or the count is below threshold.
            tryFireThresholdNotification(group);
            log.info("Realty group report submitted: groupPublicId={} reporterId={} reason={} reportPublicId={}",
                groupPublicId, reporterId, reason, saved.getPublicId());
            return saved;
        } catch (DataIntegrityViolationException e) {
            // Partial-unique index uq_rg_reports_one_open_per_reporter -- racy second
            // submission past the pre-check trips this. Translate to a domain
            // exception so callers see a clean 409.
            throw new AlreadyReportedException(groupPublicId);
        }
    }

    /**
     * Sub-project G section 12.3 -- one-shot fan-out when the group's open-report count
     * crosses the configured threshold. The flag stays set until {@link #resolve}
     * or {@link #dismiss} returns {@code openReportCount} to zero, at which point
     * the flag clears and the next cycle can fire again.
     */
    private void tryFireThresholdNotification(RealtyGroup group) {
        if (group.isReportsThresholdNotified()) {
            return;
        }
        long openReports = reportRepo
            .countByRealtyGroupIdAndStatus(group.getId(), RealtyGroupReportStatus.OPEN);
        int threshold = reportsProps.getGroupAlertThreshold();
        if (openReports < threshold) {
            return;
        }
        group.setReportsThresholdNotified(true);
        groupRepo.save(group);
        notificationPublisher.groupReportThresholdReached(group, threshold);
    }

    /**
     * Sub-project G section 12.3 -- clear the threshold-notified flag when the last
     * open report on the group is triaged. Shared by {@link #resolve} and
     * {@link #dismiss}; idempotent -- only writes when the flag is set and
     * the open count has returned to zero.
     */
    private void maybeResetThresholdFlag(RealtyGroup group) {
        if (group == null || !group.isReportsThresholdNotified()) {
            return;
        }
        long openReports = reportRepo
            .countByRealtyGroupIdAndStatus(group.getId(), RealtyGroupReportStatus.OPEN);
        if (openReports == 0L) {
            group.setReportsThresholdNotified(false);
            groupRepo.save(group);
        }
    }

    /**
     * Mark an OPEN report as RESOLVED. Resolution stamps resolver / resolved-at /
     * notes and writes a {@code REALTY_GROUP_REPORT_RESOLVE} admin action.
     *
     * @throws ReportNotFoundException if {@code reportPublicId} resolves to nothing or
     *                                 the report is not in {@code OPEN} status
     */
    @Transactional
    public RealtyGroupReport resolve(UUID reportPublicId, Long adminId, String notes) {
        RealtyGroupReport report = reportRepo.findByPublicId(reportPublicId)
            .orElseThrow(() -> new ReportNotFoundException(reportPublicId));

        // Only OPEN reports are actionable. Already-resolved/dismissed reports are
        // surfaced as "not found" so the admin UI shows a consistent stale-row state
        // regardless of whether the row vanished or was simply triaged moments ago.
        if (report.getStatus() != RealtyGroupReportStatus.OPEN) {
            throw new ReportNotFoundException(reportPublicId);
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        User admin = userRepo.getReferenceById(adminId);
        report.setStatus(RealtyGroupReportStatus.RESOLVED);
        report.setResolvedByAdmin(admin);
        report.setResolvedAt(now);
        report.setResolutionNotes(notes);
        RealtyGroupReport saved = reportRepo.save(report);

        // Sub-project G section 12.3 -- re-arm the threshold notification when the
        // last open report on the group is closed.
        maybeResetThresholdFlag(report.getRealtyGroup());

        Long groupId = report.getRealtyGroup() == null ? null : report.getRealtyGroup().getId();
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("reportPublicId", reportPublicId.toString());
        evidence.put("groupId", groupId);
        adminActionService.record(
            adminId,
            AdminActionType.REALTY_GROUP_REPORT_RESOLVE,
            AdminActionTargetType.REPORT,
            saved.getId(),
            notes,
            evidence);

        log.info("Realty group report resolved: reportPublicId={} adminId={} groupId={}",
            reportPublicId, adminId, groupId);
        return saved;
    }

    /**
     * Mark an OPEN report as DISMISSED. Dismissal stamps resolver fields, increments
     * the reporter's {@code dismissedReportsCount} (drives the frivolous-reporter
     * counter), and writes a {@code REALTY_GROUP_REPORT_DISMISS} admin action.
     *
     * @throws ReportNotFoundException if {@code reportPublicId} resolves to nothing or
     *                                 the report is not in {@code OPEN} status
     */
    @Transactional
    public RealtyGroupReport dismiss(UUID reportPublicId, Long adminId, String notes) {
        RealtyGroupReport report = reportRepo.findByPublicId(reportPublicId)
            .orElseThrow(() -> new ReportNotFoundException(reportPublicId));

        if (report.getStatus() != RealtyGroupReportStatus.OPEN) {
            throw new ReportNotFoundException(reportPublicId);
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        User admin = userRepo.getReferenceById(adminId);
        report.setStatus(RealtyGroupReportStatus.DISMISSED);
        report.setResolvedByAdmin(admin);
        report.setResolvedAt(now);
        report.setResolutionNotes(notes);
        RealtyGroupReport saved = reportRepo.save(report);

        // Sub-project G section 12.3 -- re-arm the threshold notification when the
        // last open report on the group is closed.
        maybeResetThresholdFlag(report.getRealtyGroup());

        // Bump the reporter's dismissed-counter. We re-fetch through findById so the
        // update is on a managed entity (vs. the lazy reporter proxy on the report row)
        // and so the row is locked under the current tx.
        Long reporterId = report.getReporter() == null ? null : report.getReporter().getId();
        if (reporterId != null) {
            userRepo.findById(reporterId).ifPresent(u -> {
                Long current = u.getDismissedReportsCount() == null
                    ? 0L : u.getDismissedReportsCount();
                u.setDismissedReportsCount(current + 1);
                userRepo.save(u);
            });
        }

        Long groupId = report.getRealtyGroup() == null ? null : report.getRealtyGroup().getId();
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("reportPublicId", reportPublicId.toString());
        evidence.put("groupId", groupId);
        adminActionService.record(
            adminId,
            AdminActionType.REALTY_GROUP_REPORT_DISMISS,
            AdminActionTargetType.REPORT,
            saved.getId(),
            notes,
            evidence);

        log.info("Realty group report dismissed: reportPublicId={} adminId={} reporterId={} groupId={}",
            reportPublicId, adminId, reporterId, groupId);
        return saved;
    }
}
