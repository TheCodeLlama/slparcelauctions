package com.slparcelauctions.backend.auction;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.slparcelauctions.backend.user.User;

public interface AuctionRepository extends JpaRepository<Auction, Long>, JpaSpecificationExecutor<Auction> {

    /**
     * Eagerly fetches {@code parcel} + {@code tags} so {@code AuctionDtoMapper}
     * calls downstream of a {@code @Transactional} boundary do not trip
     * {@link org.hibernate.LazyInitializationException} under
     * {@code spring.jpa.open-in-view=false}.
     */
    @EntityGraph(attributePaths = {"parcel", "tags"})
    List<Auction> findBySellerIdOrderByCreatedAtDesc(Long sellerId);

    /**
     * Parcel-locking check. Used by AuctionVerificationService before every
     * VERIFICATION_PENDING → ACTIVE transition.
     */
    boolean existsByParcelIdAndStatusInAndIdNot(
            Long parcelId, Collection<AuctionStatus> statuses, Long excludeAuctionId);

    /**
     * Identifies the auction currently holding the parcel-lock on a parcel so
     * {@code ParcelAlreadyListedException} can surface its ID in the 409
     * response. Paired with {@link #existsByParcelIdAndStatusInAndIdNot}.
     */
    Optional<Auction> findFirstByParcelIdAndStatusIn(
            Long parcelId, Collection<AuctionStatus> statuses);

    /** Used by ParcelCodeExpiryJob to find stuck Method B auctions. */
    List<Auction> findByStatusAndVerificationMethod(
            AuctionStatus status, VerificationMethod verificationMethod);

    /**
     * Eagerly fetches {@code parcel} + {@code tags} — see class-level note on
     * {@link #findBySellerIdOrderByCreatedAtDesc}.
     */
    @EntityGraph(attributePaths = {"parcel", "tags"})
    Optional<Auction> findByIdAndSellerId(Long id, Long sellerId);

    /**
     * Eagerly fetches {@code parcel} + {@code tags} — see class-level note on
     * {@link #findBySellerIdOrderByCreatedAtDesc}. Overrides the inherited
     * {@link JpaRepository#findById} so every load path — including seller,
     * public, and service-layer lookups — returns a fully-initialized aggregate.
     */
    @EntityGraph(attributePaths = {"parcel", "tags"})
    @Override
    Optional<Auction> findById(Long id);

    /**
     * Single-row hydration for {@code GET /api/v1/auctions/{id}}. Fetches
     * {@code parcel}, {@code seller}, {@code photos}, and {@code tags} in one
     * LEFT JOIN so the listing-detail mapper builds the seller card + photo
     * carousel off one query instead of three lazy proxies. The HHH90003004
     * in-memory pagination warning that bites
     * {@link #findActiveBySellerIdIds}/{@link #findAllByIdInWithParcelAndTags}
     * does not apply here: this is a single-row lookup with no
     * {@code Pageable}, so the multiple to-many fetches stay safe.
     */
    @EntityGraph(attributePaths = {"parcel", "seller", "photos", "tags"})
    @Query("SELECT a FROM Auction a WHERE a.id = :id")
    Optional<Auction> findByIdForDetail(@Param("id") Long id);

    /**
     * Returns the IDs of auctions due for an ownership check. Two paths share
     * the {@code lastOwnershipCheckAt < cutoff} cadence gate so the polling
     * frequency is uniform across both:
     * <ul>
     *   <li><b>ACTIVE auctions</b> — the live ownership-monitor flow. A null
     *       {@code lastOwnershipCheckAt} qualifies (fresh ACTIVE transitions
     *       that missed the jitter window still get picked up).</li>
     *   <li><b>CANCELLED auctions inside their post-cancel watch window</b> —
     *       Epic 08 sub-spec 2 §6. {@code postCancelWatchUntil} is set on
     *       cancel-with-bids and points {@code now + watch-hours} into the
     *       future; rows whose window has expired are excluded by the
     *       {@code postCancelWatchUntil > :now} predicate. The watch window
     *       gets cleared once a {@code CANCEL_AND_SELL} flag is raised so a
     *       single auction can flag at most once during the window.</li>
     * </ul>
     *
     * <p>Sort is oldest-first within each branch so the longest-stale
     * listings are dispatched first; nulls sort first.
     */
    @Query("""
            SELECT a.id FROM Auction a
            WHERE (a.status = com.slparcelauctions.backend.auction.AuctionStatus.ACTIVE
                    AND (a.lastOwnershipCheckAt IS NULL OR a.lastOwnershipCheckAt <= :cutoff))
               OR (a.status = com.slparcelauctions.backend.auction.AuctionStatus.CANCELLED
                    AND a.postCancelWatchUntil IS NOT NULL
                    AND a.postCancelWatchUntil > :now
                    AND (a.lastOwnershipCheckAt IS NULL OR a.lastOwnershipCheckAt <= :cutoff))
            ORDER BY a.lastOwnershipCheckAt ASC NULLS FIRST
            """)
    List<Long> findDueForOwnershipCheck(
            @Param("cutoff") OffsetDateTime cutoff,
            @Param("now") OffsetDateTime now);

    /**
     * Acquires a {@code PESSIMISTIC_WRITE} (i.e. {@code SELECT ... FOR UPDATE})
     * row lock on the auction inside the caller's transaction. Used by the
     * bid-placement, buy-now, cancellation, and ownership-check paths so any
     * two writers racing on the same auction serialise at the DB rather than
     * stepping on each other's reads of {@code currentBid}/{@code endsAt}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Auction a WHERE a.id = :id")
    Optional<Auction> findByIdForUpdate(@Param("id") Long id);

    /**
     * IDs of ACTIVE auctions whose {@code ends_at} is at or before {@code now}
     * — i.e. ripe for the auction-end scheduler to close out. Returned as bare
     * IDs so the scheduler can re-load each one under a pessimistic lock
     * before deciding the outcome.
     */
    @Query("SELECT a.id FROM Auction a WHERE a.status = com.slparcelauctions.backend.auction.AuctionStatus.ACTIVE AND a.endsAt <= :now")
    List<Long> findActiveIdsDueForEnd(@Param("now") OffsetDateTime now);

    /**
     * IDs-only page of ACTIVE auctions owned by the given seller, ordered by
     * {@code endsAt} ascending so listings closest to ending surface first on
     * the public profile. Paired with {@link #findAllByIdInWithParcelAndTags}
     * to sidestep Hibernate's {@code HHH90003004} in-memory pagination warning:
     * combining {@code @EntityGraph} over a to-many collection ({@code tags})
     * with {@code Pageable} causes Hibernate to drop SQL {@code LIMIT}/
     * {@code OFFSET} and paginate in the JVM, which would fetch every ACTIVE
     * listing for the seller on every page request. This query selects bare
     * IDs so the DB paginates cleanly, and a follow-up call hydrates the page
     * of entities with parcel + tags eagerly fetched.
     *
     * <p>SUSPENDED and pre-ACTIVE statuses are deliberately excluded — the
     * public {@code GET /users/{id}/auctions?status=ACTIVE} endpoint must not
     * leak draft prep work or suspended listings regardless of requester
     * identity (spec §14).
     */
    @Query("SELECT a.id FROM Auction a WHERE a.seller.id = :sellerId AND a.status = com.slparcelauctions.backend.auction.AuctionStatus.ACTIVE ORDER BY a.endsAt ASC")
    Page<Long> findActiveBySellerIdIds(@Param("sellerId") Long sellerId, Pageable pageable);

    /**
     * Hydrates the {@link Auction} entities for a page of IDs produced by
     * {@link #findActiveBySellerIdIds}, eagerly fetching {@code parcel} +
     * {@code tags} so the downstream {@link AuctionDtoMapper} can run outside
     * the transaction boundary without tripping
     * {@link org.hibernate.LazyInitializationException} under
     * {@code spring.jpa.open-in-view=false}. Results are ordered by
     * {@code endsAt} ascending to match the ID page's ordering — the service
     * layer re-sequences them against the incoming ID list to preserve exact
     * page order (same pattern as {@code MyBidsService}).
     */
    @EntityGraph(attributePaths = {"parcel", "tags"})
    @Query("SELECT a FROM Auction a WHERE a.id IN :ids ORDER BY a.endsAt ASC")
    List<Auction> findAllByIdInWithParcelAndTags(@Param("ids") Collection<Long> ids);

    /**
     * Bulk-loads auction aggregates with {@code parcel} + {@code seller} eagerly
     * fetched. Used by {@link com.slparcelauctions.backend.auction.mybids.MyBidsService}
     * to avoid the per-id loop + lazy-seller N+1 (~43 queries on a page of 20)
     * that the previous {@code findById}-in-a-loop pattern produced. Tags
     * batch-load via {@code @BatchSize} on {@code Auction.tags} so a separate
     * fetch over them is unnecessary.
     *
     * <p>The DB does not guarantee ordering for {@code IN}-clause results;
     * callers that need a specific page order must zip the returned list back
     * against the original ID list themselves (the same pattern as
     * {@link #findAllByIdInWithParcelAndTags}).
     */
    @EntityGraph(attributePaths = {"parcel", "seller"})
    @Query("SELECT a FROM Auction a WHERE a.id IN :ids")
    List<Auction> findAllByIdWithParcelAndSeller(@Param("ids") Collection<Long> ids);

    long countByStatus(AuctionStatus status);

    Page<Auction> findBySellerIdOrderByCreatedAtDesc(Long sellerId, Pageable pageable);

    /**
     * Returns the IDs of auctions whose seller is the given user and whose
     * status is one of the supplied blocking statuses. Used by
     * {@link com.slparcelauctions.backend.user.deletion.UserDeletionService}
     * to enforce the ACTIVE_AUCTIONS precondition before account deletion.
     */
    @Query("SELECT a.id FROM Auction a WHERE a.seller = :seller AND a.status IN :statuses")
    List<Long> findIdsBySellerAndStatusIn(
            @Param("seller") User seller,
            @Param("statuses") Collection<AuctionStatus> statuses);

    /**
     * Returns the IDs of ACTIVE auctions where the given user is the current
     * high bidder (i.e. {@code currentBidderId} matches). Used by
     * {@link com.slparcelauctions.backend.user.deletion.UserDeletionService}
     * to enforce the ACTIVE_HIGH_BIDS precondition before account deletion.
     */
    @Query("SELECT a.id FROM Auction a WHERE a.currentBidderId = :userId AND a.status = com.slparcelauctions.backend.auction.AuctionStatus.ACTIVE")
    List<Long> findIdsByCurrentBidderIdAndActive(@Param("userId") Long userId);
}
