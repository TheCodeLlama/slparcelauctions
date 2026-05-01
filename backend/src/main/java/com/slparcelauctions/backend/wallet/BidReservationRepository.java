package com.slparcelauctions.backend.wallet;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link BidReservation}.
 */
public interface BidReservationRepository extends JpaRepository<BidReservation, Long> {

    /**
     * The single active reservation for an auction (if any). Used by the
     * bid endpoint to identify the prior high bidder before swapping in
     * a new reservation.
     */
    @Query("""
        SELECT br FROM BidReservation br
         WHERE br.auctionId = :auctionId
           AND br.releasedAt IS NULL
        """)
    Optional<BidReservation> findActiveForAuction(@Param("auctionId") Long auctionId);

    /**
     * All active reservations for an auction (multiple bidders, used by
     * cancellation/freeze paths to release every reservation cleanly).
     */
    @Query("""
        SELECT br FROM BidReservation br
         WHERE br.auctionId = :auctionId
           AND br.releasedAt IS NULL
        """)
    List<BidReservation> findAllActiveForAuction(@Param("auctionId") Long auctionId);

    /**
     * All active reservations owned by a user (used by user-ban path,
     * dormancy filter, and admin tooling).
     */
    @Query("""
        SELECT br FROM BidReservation br
         WHERE br.userId = :userId
           AND br.releasedAt IS NULL
        """)
    List<BidReservation> findActiveByUser(@Param("userId") Long userId);

    /**
     * The user's active reservation on a specific auction, if any. Used by
     * the BIN handler to detect "BIN-clicker had a prior reservation on
     * this auction at a lower amount."
     */
    @Query("""
        SELECT br FROM BidReservation br
         WHERE br.userId = :userId
           AND br.auctionId = :auctionId
           AND br.releasedAt IS NULL
        """)
    Optional<BidReservation> findActiveByUserAndAuction(
            @Param("userId") Long userId,
            @Param("auctionId") Long auctionId);

    /**
     * Sum of all active reservation amounts for a user — used by the
     * reconciliation denorm-drift precheck.
     */
    @Query("""
        SELECT COALESCE(SUM(br.amount), 0) FROM BidReservation br
         WHERE br.userId = :userId
           AND br.releasedAt IS NULL
        """)
    long sumActiveByUser(@Param("userId") Long userId);

    /**
     * Total of all active reservations across all users — used as the
     * authoritative source for the wallet_reserved_total reconciliation
     * sum.
     */
    @Query("""
        SELECT COALESCE(SUM(br.amount), 0) FROM BidReservation br
         WHERE br.releasedAt IS NULL
        """)
    long sumAllActive();
}
