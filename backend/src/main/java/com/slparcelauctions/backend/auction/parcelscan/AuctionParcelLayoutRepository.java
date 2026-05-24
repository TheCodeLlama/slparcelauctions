package com.slparcelauctions.backend.auction.parcelscan;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuctionParcelLayoutRepository extends JpaRepository<AuctionParcelLayout, Long> {

    boolean existsByAuctionId(Long auctionId);

    Optional<AuctionParcelLayout> findByAuctionId(Long auctionId);

    void deleteByAuctionId(Long auctionId);
}
