package com.slparcelauctions.backend.auction;

import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    /**
     * Sub-project G section 6.1 -- batch resolver for "primary photo" used by
     * {@link com.slparcelauctions.backend.auction.AuctionDtoMapper.MapperBatchContext}.
     *
     * <p>Returns one {@link AuctionPhoto} per supplied auction id: the row with
     * the lowest {@code sortOrder} on each auction (matches the per-row
     * resolution that {@code photoList} used to do via
     * {@code findByAuctionIdOrderBySortOrderAsc(...).get(0)}). Auctions with no
     * photos return no row.
     *
     * <p>One query per call regardless of input cardinality. Empty input is
     * accepted and returns an empty list (no SQL emitted; Spring Data short-
     * circuits the empty {@code IN ()} case).
     */
    @Query("""
        SELECT p FROM AuctionPhoto p
         WHERE p.auction.id IN :auctionIds
           AND p.sortOrder = (
               SELECT MIN(p2.sortOrder) FROM AuctionPhoto p2
                WHERE p2.auction.id = p.auction.id)
        """)
    List<AuctionPhoto> findPrimaryForAuctions(@Param("auctionIds") Set<Long> auctionIds);
}
