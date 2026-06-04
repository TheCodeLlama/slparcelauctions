package com.slparcelauctions.backend.promotion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FeaturedBoardSlotRepository extends JpaRepository<FeaturedBoardSlot, Long> {

    Optional<FeaturedBoardSlot> findByPublicId(UUID publicId);

    /**
     * The live queue for one board, in display (cycle) order. Caller decides
     * static-vs-cycle based on the list size.
     */
    @Query("""
        SELECT s FROM FeaturedBoardSlot s
        WHERE s.boardIndex = :boardIndex AND s.releasedAt IS NULL
        ORDER BY s.position ASC, s.id ASC
        """)
    List<FeaturedBoardSlot> liveQueue(@Param("boardIndex") int boardIndex);

    /**
     * Active rows across all boards -- used by {@code FeaturedBoardAssignmentService}
     * to compute per-board counts and by the admin curator. Ordering is
     * (boardIndex, position) so callers can group cheaply.
     */
    @Query("""
        SELECT s FROM FeaturedBoardSlot s
        WHERE s.releasedAt IS NULL
        ORDER BY s.boardIndex ASC, s.position ASC, s.id ASC
        """)
    List<FeaturedBoardSlot> allActive();

    /**
     * Active row for a given auction (a listing is on at most one board at
     * a time). Used by the release-on-auction-end path.
     */
    @Query("""
        SELECT s FROM FeaturedBoardSlot s
        WHERE s.auction.id = :auctionId AND s.releasedAt IS NULL
        """)
    Optional<FeaturedBoardSlot> findActiveByAuctionId(@Param("auctionId") long auctionId);
}
