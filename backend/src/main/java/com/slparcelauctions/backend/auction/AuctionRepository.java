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
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuctionRepository extends JpaRepository<Auction, Long> {

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
     * Returns the IDs of ACTIVE auctions whose {@code lastOwnershipCheckAt} is
     * either null (never checked — happens on fresh activation when the
     * jitter-seeded timestamp lands before the cutoff) or at/before the cutoff.
     * Sorted oldest-first so the longest-stale listings are dispatched first.
     * Nulls sort first so fresh ACTIVE transitions that missed the jitter
     * window still participate in the next sweep. See
     * {@code OwnershipMonitorScheduler} for the cutoff derivation.
     */
    @Query("""
            SELECT a.id FROM Auction a
            WHERE a.status = com.slparcelauctions.backend.auction.AuctionStatus.ACTIVE
              AND (a.lastOwnershipCheckAt IS NULL OR a.lastOwnershipCheckAt <= :cutoff)
            ORDER BY a.lastOwnershipCheckAt ASC NULLS FIRST
            """)
    List<Long> findDueForOwnershipCheck(@Param("cutoff") OffsetDateTime cutoff);

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
     * Paginated page of ACTIVE auctions owned by the given seller, ordered by
     * {@code endsAt} ascending so listings closest to ending surface first on
     * the public profile. Eagerly fetches {@code parcel} + {@code tags} so the
     * downstream mapper can run outside the transaction boundary (see
     * class-level note on {@link #findBySellerIdOrderByCreatedAtDesc}).
     *
     * <p>SUSPENDED and pre-ACTIVE statuses are deliberately excluded — the
     * public {@code GET /users/{id}/auctions?status=ACTIVE} endpoint must not
     * leak draft prep work or suspended listings regardless of requester
     * identity (spec §14).
     */
    @EntityGraph(attributePaths = {"parcel", "tags"})
    @Query("SELECT a FROM Auction a WHERE a.seller.id = :sellerId AND a.status = com.slparcelauctions.backend.auction.AuctionStatus.ACTIVE ORDER BY a.endsAt ASC")
    Page<Auction> findActiveBySellerId(@Param("sellerId") Long sellerId, Pageable pageable);
}
