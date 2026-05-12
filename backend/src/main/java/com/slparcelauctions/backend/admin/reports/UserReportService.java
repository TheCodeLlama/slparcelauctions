package com.slparcelauctions.backend.admin.reports;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.reports.dto.MyReportResponse;
import com.slparcelauctions.backend.admin.reports.dto.ReportRequest;
import com.slparcelauctions.backend.admin.reports.exception.AuctionNotReportableException;
import com.slparcelauctions.backend.admin.reports.exception.CannotReportOwnListingException;
import com.slparcelauctions.backend.admin.reports.exception.MustBeVerifiedToReportException;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.realty.reports.RealtyGroupReport;
import com.slparcelauctions.backend.realty.reports.RealtyGroupReportRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserReportService {

    private final ListingReportRepository repo;
    private final RealtyGroupReportRepository realtyGroupReportRepo;
    private final AuctionRepository auctionRepo;
    private final UserRepository userRepo;

    @Transactional
    public MyReportResponse upsertReport(Long auctionId, Long reporterId, ReportRequest req) {
        Auction auction = auctionRepo.findById(auctionId)
            .orElseThrow(() -> new AuctionNotFoundException(auctionId));
        User reporter = userRepo.findById(reporterId)
            .orElseThrow(() -> new IllegalStateException("Reporter not found: " + reporterId));

        if (!Boolean.TRUE.equals(reporter.getVerified())) {
            throw new MustBeVerifiedToReportException();
        }
        if (auction.getSeller().getId().equals(reporterId)) {
            throw new CannotReportOwnListingException();
        }
        if (auction.getStatus() != AuctionStatus.ACTIVE) {
            throw new AuctionNotReportableException(auction.getStatus());
        }

        ListingReport report = repo.findByAuctionIdAndReporterId(auctionId, reporterId)
            .orElseGet(() -> ListingReport.builder()
                .auction(auction).reporter(reporter)
                .build());
        report.setSubject(req.subject());
        report.setReason(req.reason());
        report.setDetails(req.details());
        report.setStatus(ListingReportStatus.OPEN);
        report.setReviewedBy(null);
        report.setReviewedAt(null);
        report.setAdminNotes(null);
        ListingReport saved = repo.save(report);
        return toMyReport(saved);
    }

    @Transactional(readOnly = true)
    public Optional<MyReportResponse> findMyReport(Long auctionId, Long reporterId) {
        return repo.findByAuctionIdAndReporterId(auctionId, reporterId)
            .map(this::toMyReport);
    }

    /**
     * Merged reporter-visibility list: every listing report and realty-group report
     * the caller has filed, newest first. The DTO's {@code entityType} field
     * discriminates the two so the frontend can render either kind in one timeline.
     *
     * <p>Strategy: two cheap per-reporter reads + Java merge. The shared 5/day quota
     * caps the per-reporter row count low enough that union-via-SQL adds no value
     * over a Java sort.
     *
     * <p>Sub-project F spec §12.4.
     */
    @Transactional(readOnly = true)
    public List<MyReportResponse> findMyReports(Long reporterId) {
        List<ListingReport> listingReports = repo.findByReporterIdOrderByCreatedAtDesc(reporterId);
        List<RealtyGroupReport> groupReports =
            realtyGroupReportRepo.findByReporterIdOrderByCreatedAtDesc(reporterId);
        List<MyReportResponse> merged = new ArrayList<>(listingReports.size() + groupReports.size());
        for (ListingReport r : listingReports) {
            merged.add(toMyReport(r));
        }
        for (RealtyGroupReport r : groupReports) {
            merged.add(toMyReport(r));
        }
        merged.sort(Comparator.comparing(MyReportResponse::createdAt).reversed());
        return merged;
    }

    private MyReportResponse toMyReport(ListingReport r) {
        return new MyReportResponse(
            r.getPublicId(),
            "LISTING",
            r.getAuction().getPublicId(),
            r.getSubject(),
            r.getReason().name(),
            r.getDetails(),
            r.getStatus().name(),
            r.getAdminNotes(),
            r.getCreatedAt(),
            r.getUpdatedAt());
    }

    private MyReportResponse toMyReport(RealtyGroupReport r) {
        return new MyReportResponse(
            r.getPublicId(),
            "REALTY_GROUP",
            r.getRealtyGroup().getPublicId(),
            null,
            r.getReason().name(),
            r.getDetails(),
            r.getStatus().name(),
            r.getResolutionNotes(),
            r.getCreatedAt(),
            r.getUpdatedAt());
    }
}
