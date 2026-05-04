package com.slparcelauctions.backend.auction;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuctionPhotoRepository extends JpaRepository<AuctionPhoto, Long> {

    Optional<AuctionPhoto> findByPublicId(UUID publicId);

    List<AuctionPhoto> findByAuctionIdOrderBySortOrderAsc(Long auctionId);

    long countByAuctionId(Long auctionId);

    java.util.Optional<AuctionPhoto> findFirstByAuctionIdAndSource(Long auctionId, PhotoSource source);
}
