package com.slparcelauctions.backend.realty.reports;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link RealtyGroupReport}.
 *
 * <p>Sub-project F spec §8.
 */
public interface RealtyGroupReportRepository extends JpaRepository<RealtyGroupReport, Long> {

    Optional<RealtyGroupReport> findByPublicId(UUID publicId);

    /**
     * Used by the report-create gate to enforce "at most one OPEN report per
     * (group, reporter)" before the partial-unique-index would catch it at insert
     * time. Mirrors the partial-unique index {@code uq_rg_reports_one_open_per_reporter}.
     */
    @Query("""
        SELECT COUNT(r) > 0 FROM RealtyGroupReport r
         WHERE r.realtyGroup.id = :groupId
           AND r.reporter.id = :reporterId
           AND r.status = com.slparcelauctions.backend.realty.reports.RealtyGroupReportStatus.OPEN
    """)
    boolean existsOpenByGroupAndReporter(
            @Param("groupId") Long groupId,
            @Param("reporterId") Long reporterId);

    /** Paginated by status — admin moderation queue uses this with {@code OPEN}. */
    Page<RealtyGroupReport> findByStatus(RealtyGroupReportStatus status, Pageable pageable);

    /** Full report history for a group, newest first. Drives the admin moderation page. */
    @Query("""
        SELECT r FROM RealtyGroupReport r
         WHERE r.realtyGroup.id = :groupId
         ORDER BY r.createdAt DESC
    """)
    List<RealtyGroupReport> findByGroupId(@Param("groupId") Long groupId);
}
