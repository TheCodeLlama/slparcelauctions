package com.slparcelauctions.backend.admin.reports;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.slparcelauctions.backend.admin.reports.dto.AdminReportListingRowDto;

public interface ListingReportRepository extends JpaRepository<ListingReport, Long> {

    Optional<ListingReport> findByPublicId(UUID publicId);

    Optional<ListingReport> findByAuctionIdAndReporterId(Long auctionId, Long reporterId);

    List<ListingReport> findByAuctionIdOrderByCreatedAtDesc(Long auctionId);

    long countByStatus(ListingReportStatus status);

    @Query("""
        SELECT new com.slparcelauctions.backend.admin.reports.dto.AdminReportListingRowDto(
            r.auction.id, r.auction.title, r.auction.status,
            r.auction.parcelSnapshot.regionName, r.auction.seller.id, r.auction.seller.displayName,
            COUNT(r), MAX(r.updatedAt))
        FROM ListingReport r
        WHERE r.status = :status
        GROUP BY r.auction.id, r.auction.title, r.auction.status,
                 r.auction.parcelSnapshot.regionName, r.auction.seller.id, r.auction.seller.displayName
        ORDER BY COUNT(r) DESC, MAX(r.updatedAt) DESC
    """)
    Page<AdminReportListingRowDto> findListingsGroupedByStatus(
        @Param("status") ListingReportStatus status, Pageable pageable);

    @Query("""
        SELECT new com.slparcelauctions.backend.admin.reports.dto.AdminReportListingRowDto(
            r.auction.id, r.auction.title, r.auction.status,
            r.auction.parcelSnapshot.regionName, r.auction.seller.id, r.auction.seller.displayName,
            COUNT(r), MAX(r.updatedAt))
        FROM ListingReport r
        GROUP BY r.auction.id, r.auction.title, r.auction.status,
                 r.auction.parcelSnapshot.regionName, r.auction.seller.id, r.auction.seller.displayName
        ORDER BY COUNT(r) DESC, MAX(r.updatedAt) DESC
    """)
    Page<AdminReportListingRowDto> findAllListingsGrouped(Pageable pageable);

    @Query("SELECT count(r) FROM ListingReport r WHERE r.auction.id = :auctionId AND r.status = 'OPEN'")
    long countOpenByAuctionId(@Param("auctionId") Long auctionId);

    @Query("""
        SELECT r FROM ListingReport r
        WHERE r.reporter.id = :userId OR r.auction.seller.id = :userId
        ORDER BY r.updatedAt DESC
        """)
    Page<ListingReport> findByUserAsReporterOrSeller(@Param("userId") Long userId, Pageable pageable);
}
