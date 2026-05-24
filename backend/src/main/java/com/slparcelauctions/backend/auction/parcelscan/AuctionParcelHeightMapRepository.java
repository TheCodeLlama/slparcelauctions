package com.slparcelauctions.backend.auction.parcelscan;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuctionParcelHeightMapRepository extends JpaRepository<AuctionParcelHeightMap, Long> {

    boolean existsByAuctionId(Long auctionId);

    Optional<AuctionParcelHeightMap> findByAuctionId(Long auctionId);

    void deleteByAuctionId(Long auctionId);
}
