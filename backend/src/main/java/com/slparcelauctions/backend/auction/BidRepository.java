package com.slparcelauctions.backend.auction;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read/write surface for the {@code bids} table. The auction-room UI renders
 * newest-first; internal reconciliation (e.g. proxy engine replay) walks
 * oldest-first — hence both directional finders. The My-Bids projection
 * queries live here too: one paginated projection over
 * {@code (auction_id, max(amount), max(created_at))} grouped by auction, plus
 * a convenience single-user-single-auction lookup.
 */
public interface BidRepository extends JpaRepository<Bid, Long> {

    Page<Bid> findByAuctionIdOrderByCreatedAtDesc(Long auctionId, Pageable pageable);

    List<Bid> findByAuctionIdOrderByCreatedAtAsc(Long auctionId);

    /**
     * Paginated list of distinct auction IDs the given user has bid on,
     * filtered by the parent auction's status. Spec §10 ordering: ACTIVE
     * auctions first (ending soonest at the top), then every other status
     * ordered by {@code endedAt DESC}.
     *
     * <p>The service calls {@link #findMyBidAuctionIdsUnfiltered} when the
     * caller requests {@code status=all} (or omits the parameter) — splitting
     * the two shapes keeps each JPQL query literal and avoids the
     * HQL-on-Collection-IS-NULL footgun.
     *
     * <p>Returned IDs are loaded via
     * {@link org.springframework.data.jpa.repository.JpaRepository#findById}
     * and zipped with per-user bid aggregates from
     * {@link #findMyBidAggregatesForAuctions} in {@code MyBidsService}.
     */
    @Query(value = """
            SELECT b.auction.id
            FROM Bid b
            JOIN b.auction a
            WHERE b.bidder.id = :userId
              AND a.status IN :statuses
            GROUP BY b.auction.id,
                     CASE WHEN a.status = com.slparcelauctions.backend.auction.AuctionStatus.ACTIVE THEN 0 ELSE 1 END,
                     a.endsAt,
                     a.endedAt
            ORDER BY CASE WHEN a.status = com.slparcelauctions.backend.auction.AuctionStatus.ACTIVE THEN 0 ELSE 1 END ASC,
                     a.endsAt DESC,
                     a.endedAt DESC,
                     b.auction.id ASC
            """,
            countQuery = """
            SELECT COUNT(DISTINCT b.auction.id)
            FROM Bid b
            JOIN b.auction a
            WHERE b.bidder.id = :userId
              AND a.status IN :statuses
            """)
    Page<Long> findMyBidAuctionIds(
            @Param("userId") Long userId,
            @Param("statuses") Collection<AuctionStatus> statuses,
            Pageable pageable);

    /**
     * Unfiltered variant of {@link #findMyBidAuctionIds} — used when the
     * caller requests {@code status=all} (or omits the parameter). Kept as
     * a separate query literal rather than a nullable parameter to sidestep
     * HQL's uncertain handling of {@code :collection IS NULL}.
     */
    @Query(value = """
            SELECT b.auction.id
            FROM Bid b
            JOIN b.auction a
            WHERE b.bidder.id = :userId
            GROUP BY b.auction.id,
                     CASE WHEN a.status = com.slparcelauctions.backend.auction.AuctionStatus.ACTIVE THEN 0 ELSE 1 END,
                     a.endsAt,
                     a.endedAt
            ORDER BY CASE WHEN a.status = com.slparcelauctions.backend.auction.AuctionStatus.ACTIVE THEN 0 ELSE 1 END ASC,
                     a.endsAt DESC,
                     a.endedAt DESC,
                     b.auction.id ASC
            """,
            countQuery = """
            SELECT COUNT(DISTINCT b.auction.id)
            FROM Bid b
            WHERE b.bidder.id = :userId
            """)
    Page<Long> findMyBidAuctionIdsUnfiltered(
            @Param("userId") Long userId,
            Pageable pageable);

    /**
     * For a given caller and a concrete set of auction IDs, returns
     * {@code (auctionId, maxAmount, maxCreatedAt)} triples — the caller's
     * own bidding high-water-mark per auction. Paired with
     * {@link #findMyBidAuctionIds} to populate the My Bids dashboard without
     * fetching every raw bid row.
     *
     * <p>Columns in the {@code Object[]}:
     * <ol start="0">
     *   <li>{@code auctionId} — {@link Long}</li>
     *   <li>{@code maxAmount} — {@link Long}</li>
     *   <li>{@code maxCreatedAt} — {@link java.time.OffsetDateTime}</li>
     * </ol>
     */
    @Query("""
            SELECT b.auction.id,
                   MAX(b.amount),
                   MAX(b.createdAt)
            FROM Bid b
            WHERE b.bidder.id = :userId
              AND b.auction.id IN :auctionIds
            GROUP BY b.auction.id
            """)
    List<Object[]> findMyBidAggregatesForAuctions(
            @Param("userId") Long userId,
            @Param("auctionIds") Collection<Long> auctionIds);

    /**
     * Bulk-deletes every bid for the given auction. Test-only helper used by
     * integration-test cleanup to avoid raw JDBC {@code DELETE} statements
     * that would silently stop covering new FK-child tables as Epic 05 lands.
     */
    @Modifying
    @Transactional
    int deleteAllByAuctionId(Long auctionId);
}
