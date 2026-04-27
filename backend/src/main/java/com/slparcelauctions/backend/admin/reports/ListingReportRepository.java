package com.slparcelauctions.backend.admin.reports;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ListingReportRepository extends JpaRepository<ListingReport, Long> {

    Optional<ListingReport> findByAuctionIdAndReporterId(Long auctionId, Long reporterId);

    List<ListingReport> findByAuctionIdOrderByCreatedAtDesc(Long auctionId);

    long countByStatus(ListingReportStatus status);
}
