package com.slparcelauctions.backend.auction;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuctionPhotoRepository extends JpaRepository<AuctionPhoto, Long> {

    List<AuctionPhoto> findByAuctionIdOrderBySortOrderAsc(Long auctionId);

    long countByAuctionId(Long auctionId);
}
