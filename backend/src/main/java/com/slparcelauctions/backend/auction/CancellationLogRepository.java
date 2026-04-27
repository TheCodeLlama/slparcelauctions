package com.slparcelauctions.backend.auction;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CancellationLogRepository extends JpaRepository<CancellationLog, Long> {

    /**
     * Count of prior cancelled-with-bids log rows for a seller. Drives the
     * ladder index in {@code CancellationService.cancel} — the call MUST run
     * BEFORE the new log row is inserted (off-by-one trap). Result indices:
     * 0 → WARNING, 1 → PENALTY, 2 → PENALTY_AND_30D, 3+ → PERMANENT_BAN.
     * The seller-row pessimistic lock acquired earlier in the same
     * transaction prevents two concurrent cancellations from racing on this
     * count.
     */
    @Query("SELECT count(c) FROM CancellationLog c "
            + "WHERE c.seller.id = :sellerId "
            + "AND c.hadBids = true "
            + "AND c.cancelledByAdminId IS NULL")
    long countPriorOffensesWithBids(@Param("sellerId") Long sellerId);

    /**
     * Paginated cancellation history for one seller, hydrated with the
     * referenced auction (its parcel and photos) so the DTO mapper can pick a
     * primary photo URL without an N+1 lazy fetch storm. The {@code auction.photos}
     * collection is the only to-many in the graph, so Hibernate batches the
     * fetch via a single LEFT JOIN without Cartesian explosion. Sorting is
     * supplied by the caller via {@link Pageable} — the controller pins
     * {@code Sort.by("cancelledAt").descending()} per spec §7.4.
     */
    @EntityGraph(attributePaths = {"auction", "auction.parcel", "auction.photos"})
    Page<CancellationLog> findBySellerId(Long sellerId, Pageable pageable);

    /**
     * Most-recent cancellation log rows for a given auction, newest first.
     * Used by the post-cancel ownership watcher (Epic 08 sub-spec 2 §6.3) to
     * derive the {@code hoursSinceCancellation} evidence field — computed
     * from the latest row's {@code cancelledAt} rather than
     * {@code postCancelWatchUntil - 48h} so the math is robust even if the
     * watch-window length is reconfigured. Callers should pass
     * {@code PageRequest.of(0, 1)} and read the first element; the return
     * type is {@code List} (not {@code Optional}) because Spring Data
     * rejects {@code Optional} on JPQL queries that take a {@link Pageable}.
     */
    @Query("SELECT c FROM CancellationLog c WHERE c.auction.id = :auctionId "
            + "ORDER BY c.cancelledAt DESC")
    List<CancellationLog> findLatestByAuctionId(
            @Param("auctionId") Long auctionId, Pageable pageable);
}
