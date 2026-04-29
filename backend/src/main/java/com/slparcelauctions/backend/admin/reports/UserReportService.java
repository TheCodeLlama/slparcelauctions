package com.slparcelauctions.backend.admin.reports;

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
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserReportService {

    private final ListingReportRepository repo;
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

    private MyReportResponse toMyReport(ListingReport r) {
        return new MyReportResponse(r.getId(), r.getSubject(), r.getReason(), r.getDetails(),
            r.getStatus(), r.getCreatedAt(), r.getUpdatedAt());
    }
}
