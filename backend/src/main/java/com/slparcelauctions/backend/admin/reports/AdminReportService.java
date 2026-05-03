package com.slparcelauctions.backend.admin.reports;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.admin.reports.dto.AdminReportDetailDto;
import com.slparcelauctions.backend.admin.reports.dto.AdminReportListingRowDto;
import com.slparcelauctions.backend.admin.reports.exception.ReportNotFoundException;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.CancellationService;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auction.monitoring.SuspensionService;
import com.slparcelauctions.backend.common.PagedResponse;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AdminReportService {

    private final ListingReportRepository reportRepo;
    private final AuctionRepository auctionRepo;
    private final UserRepository userRepo;
    private final AdminActionService adminActionService;
    private final NotificationPublisher notificationPublisher;
    private final SuspensionService suspensionService;
    private final CancellationService cancellationService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PagedResponse<AdminReportListingRowDto> listGrouped(ListingReportStatus status, Pageable pageable) {
        Page<AdminReportListingRowDto> page = reportRepo.findListingsGroupedByStatus(status, pageable);
        return PagedResponse.from(page);
    }

    @Transactional(readOnly = true)
    public PagedResponse<AdminReportListingRowDto> listAllGrouped(Pageable pageable) {
        Page<AdminReportListingRowDto> page = reportRepo.findAllListingsGrouped(pageable);
        return PagedResponse.from(page);
    }

    @Transactional(readOnly = true)
    public List<AdminReportDetailDto> findByListing(Long auctionId) {
        List<ListingReport> reports = reportRepo.findByAuctionIdOrderByCreatedAtDesc(auctionId);
        return reports.stream().map(this::toDetailDto).toList();
    }

    @Transactional(readOnly = true)
    public AdminReportDetailDto findOne(Long reportId) {
        ListingReport report = reportRepo.findById(reportId)
            .orElseThrow(() -> new ReportNotFoundException(reportId));
        return toDetailDto(report);
    }

    @Transactional
    public AdminReportDetailDto dismiss(Long reportId, Long adminUserId, String notes) {
        ListingReport report = reportRepo.findById(reportId)
            .orElseThrow(() -> new ReportNotFoundException(reportId));

        User reporter = report.getReporter();
        reporter.setDismissedReportsCount(reporter.getDismissedReportsCount() + 1);
        userRepo.save(reporter);

        User admin = userRepo.findById(adminUserId)
            .orElseThrow(() -> new IllegalStateException("Admin user not found: " + adminUserId));

        report.setStatus(ListingReportStatus.DISMISSED);
        report.setAdminNotes(notes);
        report.setReviewedBy(admin);
        report.setReviewedAt(OffsetDateTime.now(clock));
        reportRepo.save(report);

        adminActionService.record(
            adminUserId,
            AdminActionType.DISMISS_REPORT,
            AdminActionTargetType.REPORT,
            reportId,
            notes,
            Map.of("auctionId", report.getAuction().getId())
        );

        return toDetailDto(report);
    }

    @Transactional
    public void warnSeller(Long auctionId, Long adminUserId, String notes) {
        Auction auction = auctionRepo.findById(auctionId)
            .orElseThrow(() -> new AuctionNotFoundException(auctionId));

        List<ListingReport> reports = reportRepo.findByAuctionIdOrderByCreatedAtDesc(auctionId);

        User admin = userRepo.findById(adminUserId)
            .orElseThrow(() -> new IllegalStateException("Admin user not found: " + adminUserId));

        OffsetDateTime now = OffsetDateTime.now(clock);
        List<ListingReport> openReports = reports.stream()
            .filter(r -> r.getStatus() == ListingReportStatus.OPEN)
            .toList();

        for (ListingReport r : openReports) {
            r.setStatus(ListingReportStatus.REVIEWED);
            r.setAdminNotes(notes);
            r.setReviewedBy(admin);
            r.setReviewedAt(now);
            reportRepo.save(r);
        }

        notificationPublisher.listingWarned(
            auction.getSeller().getId(),
            auctionId,
            auction.getParcelSnapshot() != null ? auction.getParcelSnapshot().getRegionName() : null,
            notes
        );

        adminActionService.record(
            adminUserId,
            AdminActionType.WARN_SELLER_FROM_REPORT,
            AdminActionTargetType.LISTING,
            auctionId,
            notes,
            Map.of("openReportsMarkedReviewed", openReports.size())
        );
    }

    @Transactional
    public void suspend(Long auctionId, Long adminUserId, String notes) {
        Auction auction = auctionRepo.findById(auctionId)
            .orElseThrow(() -> new AuctionNotFoundException(auctionId));

        List<ListingReport> reports = reportRepo.findByAuctionIdOrderByCreatedAtDesc(auctionId);

        User admin = userRepo.findById(adminUserId)
            .orElseThrow(() -> new IllegalStateException("Admin user not found: " + adminUserId));

        suspensionService.suspendByAdmin(auction, adminUserId, notes);

        OffsetDateTime now = OffsetDateTime.now(clock);
        List<ListingReport> openReports = reports.stream()
            .filter(r -> r.getStatus() == ListingReportStatus.OPEN)
            .toList();

        for (ListingReport r : openReports) {
            r.setStatus(ListingReportStatus.ACTION_TAKEN);
            r.setAdminNotes(notes);
            r.setReviewedBy(admin);
            r.setReviewedAt(now);
            reportRepo.save(r);
        }

        adminActionService.record(
            adminUserId,
            AdminActionType.SUSPEND_LISTING_FROM_REPORT,
            AdminActionTargetType.LISTING,
            auctionId,
            notes,
            Map.of("openReportsMarkedActionTaken", openReports.size())
        );
    }

    @Transactional
    public void cancel(Long auctionId, Long adminUserId, String notes) {
        List<ListingReport> reports = reportRepo.findByAuctionIdOrderByCreatedAtDesc(auctionId);

        User admin = userRepo.findById(adminUserId)
            .orElseThrow(() -> new IllegalStateException("Admin user not found: " + adminUserId));

        cancellationService.cancelByAdmin(auctionId, adminUserId, notes);

        OffsetDateTime now = OffsetDateTime.now(clock);
        List<ListingReport> openReports = reports.stream()
            .filter(r -> r.getStatus() == ListingReportStatus.OPEN)
            .toList();

        for (ListingReport r : openReports) {
            r.setStatus(ListingReportStatus.ACTION_TAKEN);
            r.setAdminNotes(notes);
            r.setReviewedBy(admin);
            r.setReviewedAt(now);
            reportRepo.save(r);
        }

        adminActionService.record(
            adminUserId,
            AdminActionType.CANCEL_LISTING_FROM_REPORT,
            AdminActionTargetType.LISTING,
            auctionId,
            notes,
            Map.of("openReportsMarkedActionTaken", openReports.size())
        );
    }

    private AdminReportDetailDto toDetailDto(ListingReport r) {
        User reporter = r.getReporter();
        User reviewedBy = r.getReviewedBy();
        return new AdminReportDetailDto(
            r.getId(),
            r.getReason(),
            r.getSubject(),
            r.getDetails(),
            r.getStatus(),
            r.getAdminNotes(),
            r.getCreatedAt(),
            r.getUpdatedAt(),
            r.getReviewedAt(),
            reporter != null ? reporter.getId() : null,
            reporter != null ? reporter.getDisplayName() : null,
            reporter != null && reporter.getDismissedReportsCount() != null
                ? reporter.getDismissedReportsCount() : 0L,
            reviewedBy != null ? reviewedBy.getDisplayName() : null
        );
    }
}
