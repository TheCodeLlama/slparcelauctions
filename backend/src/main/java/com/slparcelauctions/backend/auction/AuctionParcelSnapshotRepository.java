package com.slparcelauctions.backend.auction;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuctionParcelSnapshotRepository
        extends JpaRepository<AuctionParcelSnapshot, Long> {
}
