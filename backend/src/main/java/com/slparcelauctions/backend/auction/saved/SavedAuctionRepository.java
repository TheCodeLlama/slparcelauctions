package com.slparcelauctions.backend.auction.saved;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link SavedAuction}. JPQL-driven so we hit
 * {@code user_id} / {@code auction_id} columns directly rather than
 * forcing Hibernate to load the related entities for derived queries.
 */
public interface SavedAuctionRepository extends JpaRepository<SavedAuction, Long> {

    @Query("SELECT s FROM SavedAuction s WHERE s.user.id = :userId AND s.auction.id = :auctionId")
    Optional<SavedAuction> findByUserIdAndAuctionId(
            @Param("userId") Long userId, @Param("auctionId") Long auctionId);

    @Query("SELECT s.auction.id FROM SavedAuction s WHERE s.user.id = :userId ORDER BY s.savedAt DESC")
    List<Long> findAuctionIdsByUserId(@Param("userId") Long userId);

    @Query("SELECT COUNT(s) FROM SavedAuction s WHERE s.user.id = :userId")
    long countByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("DELETE FROM SavedAuction s WHERE s.user.id = :userId AND s.auction.id = :auctionId")
    int deleteByUserIdAndAuctionId(
            @Param("userId") Long userId, @Param("auctionId") Long auctionId);
}
