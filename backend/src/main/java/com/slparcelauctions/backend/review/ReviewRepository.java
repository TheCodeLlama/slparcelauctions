package com.slparcelauctions.backend.review;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
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

    /**
     * Visible reviews for an auction (both sides — seller + buyer). Used
     * by {@code GET /api/v1/auctions/{id}/reviews} to populate the
     * {@code reviews} array. {@code revealedAt DESC} ordering matches the
     * public profile listing contract so the most recent reveal appears
     * first.
     */
    @Query("""
            select r from Review r
            where r.auction.id = :auctionId
              and r.visible = true
            order by r.revealedAt desc
            """)
    List<Review> findByAuctionIdAndVisibleTrue(@Param("auctionId") Long auctionId);

    /**
     * Paginated visible reviews for a user in a specific role — backs
     * the public profile page's reviews tab. Derived-query method name
     * resolves to {@code reviewee_id + reviewed_role + visible} which
     * exactly hits the composite index
     * {@code idx_reviews_reviewee_visible}.
     */
    Page<Review> findByRevieweeIdAndReviewedRoleAndVisibleTrue(
            Long revieweeId, ReviewedRole reviewedRole, Pageable page);

    /**
     * Visible reviews whose computed {@code responseDeadline}
     * ({@code revealedAt + 14 days}) falls inside {@code [rangeStart, rangeEnd]},
     * where a response has not yet been submitted and the once-per-review
     * reminder flag is clear. Used by
     * {@link com.slparcelauctions.backend.admin.infrastructure.reminders.ReviewResponseWindowClosingScheduler}
     * to fire a once-per-review response-window-closing reminder.
     *
     * <p>{@code rangeStart} and {@code rangeEnd} are the caller-supplied
     * deadline bounds (e.g., {@code now+24h} and {@code now+48h}). JPQL
     * adds 14 days to {@code revealedAt} to derive the deadline, so the
     * query is equivalent to:
     * {@code revealedAt BETWEEN rangeStart-14d AND rangeEnd-14d}.
     */
    @Query("""
            SELECT r FROM Review r
            WHERE r.visible = true
              AND r.revealedAt IS NOT NULL
              AND r.responseClosingReminderSentAt IS NULL
              AND NOT EXISTS (
                  SELECT 1 FROM ReviewResponse rr WHERE rr.review.id = r.id
              )
              AND r.revealedAt BETWEEN :revealedAtStart AND :revealedAtEnd
            """)
    List<Review> findReviewsApproachingResponseClose(
            @Param("revealedAtStart") OffsetDateTime revealedAtStart,
            @Param("revealedAtEnd") OffsetDateTime revealedAtEnd);
}
