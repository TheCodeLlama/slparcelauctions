package com.slparcelauctions.backend.auction;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read/write surface for the {@code bids} table. The auction-room UI renders
 * newest-first; internal reconciliation (e.g. proxy engine replay) walks
 * oldest-first — hence both directional finders. The My-Bids query lives in a
 * later task.
 */
public interface BidRepository extends JpaRepository<Bid, Long> {

    Page<Bid> findByAuctionIdOrderByCreatedAtDesc(Long auctionId, Pageable pageable);

    List<Bid> findByAuctionIdOrderByCreatedAtAsc(Long auctionId);

    /**
     * Bulk-deletes every bid for the given auction. Test-only helper used by
     * integration-test cleanup to avoid raw JDBC {@code DELETE} statements
     * that would silently stop covering new FK-child tables as Epic 05 lands.
     */
    @Modifying
    @Transactional
    int deleteAllByAuctionId(Long auctionId);
}
