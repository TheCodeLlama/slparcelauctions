package com.slparcelauctions.backend.review;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link Review}. The {@code findByIdForUpdate}
 * method takes a pessimistic write lock for the Task 2 reveal path; the
 * aggregate projections materialise into {@link Aggregate} records that
 * the aggregate-recompute routine consumes. Task 1 uses only
 * {@link #findByAuctionIdAndReviewerId}; the other methods are defined
 * here so Task 2 can ship reveal + aggregates without re-touching this
 * file (see plan §Task 2).
 */
public interface ReviewRepository extends JpaRepository<Review, Long> {

    Optional<Review> findByAuctionIdAndReviewerId(Long auctionId, Long reviewerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Review r where r.id = :id")
    Optional<Review> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select new com.slparcelauctions.backend.review.Aggregate(
                avg(r.rating), count(r))
            from Review r
            where r.reviewee.id = :revieweeId
              and r.reviewedRole = com.slparcelauctions.backend.review.ReviewedRole.SELLER
              and r.visible = true
            """)
    Aggregate computeSellerAggregate(@Param("revieweeId") Long revieweeId);

    @Query("""
            select new com.slparcelauctions.backend.review.Aggregate(
                avg(r.rating), count(r))
            from Review r
            where r.reviewee.id = :revieweeId
              and r.reviewedRole = com.slparcelauctions.backend.review.ReviewedRole.BUYER
              and r.visible = true
            """)
    Aggregate computeBuyerAggregate(@Param("revieweeId") Long revieweeId);

    /**
     * Pending reviews whose auction's escrow completed at or before the
     * supplied threshold — i.e., day-14 reveal candidates for the
     * scheduler in Task 2. Returns {@code id}-ordered pages; the caller
     * is expected to lock each row individually inside its own
     * transaction via {@link #findByIdForUpdate(Long)}.
     */
    @Query("""
            select r from Review r
            where r.visible = false
              and r.auction.id in (
                select e.auction.id from com.slparcelauctions.backend.escrow.Escrow e
                where e.state = com.slparcelauctions.backend.escrow.EscrowState.COMPLETED
                  and e.completedAt < :threshold
              )
            order by r.id
            """)
    List<Review> findRevealable(@Param("threshold") OffsetDateTime threshold, Pageable page);
}
