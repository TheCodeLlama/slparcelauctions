package com.slparcelauctions.backend.auction;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Read/write surface for the {@code bids} table. The auction-room UI renders
 * newest-first; internal reconciliation (e.g. proxy engine replay) walks
 * oldest-first — hence both directional finders. The My-Bids query lives in a
 * later task.
 */
public interface BidRepository extends JpaRepository<Bid, Long> {

    Page<Bid> findByAuctionIdOrderByCreatedAtDesc(Long auctionId, Pageable pageable);

    List<Bid> findByAuctionIdOrderByCreatedAtAsc(Long auctionId);
}
