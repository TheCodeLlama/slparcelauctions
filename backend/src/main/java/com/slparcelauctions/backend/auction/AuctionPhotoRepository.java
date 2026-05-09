package com.slparcelauctions.backend.auction;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuctionPhotoRepository extends JpaRepository<AuctionPhoto, Long> {

    Optional<AuctionPhoto> findByPublicId(UUID publicId);

    List<AuctionPhoto> findByAuctionIdOrderBySortOrderAsc(Long auctionId);

    long countByAuctionId(Long auctionId);

    Optional<AuctionPhoto> findFirstByAuctionIdAndSource(Long auctionId, PhotoSource source);

    boolean existsByAuctionIdAndSource(Long auctionId, PhotoSource source);

    /**
     * Returns the highest {@code sortOrder} on this auction's photos, or
     * {@code -1} if the auction has no photos. Callers add 1 to land on the
     * next free slot. Used by auto-insert services so default cover and SL
     * snapshot don't fight over a hardcoded 0/1 — first one to apply claims
     * the lower index, the next claims +1.
     */
    @Query("select coalesce(max(p.sortOrder), -1) from AuctionPhoto p where p.auction.id = :auctionId")
    int findMaxSortOrderByAuctionId(@Param("auctionId") Long auctionId);
}
