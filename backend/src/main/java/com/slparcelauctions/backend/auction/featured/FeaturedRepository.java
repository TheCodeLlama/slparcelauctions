package com.slparcelauctions.backend.auction.featured;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.slparcelauctions.backend.auction.Auction;

/**
 * Native-SQL queries backing the three featured rows. Each query caps at
 * six rows and filters to ACTIVE auctions only.
 *
 * <p>Note: the {@code Auction} entity uses {@code starts_at} for the
 * activation timestamp — there is no {@code activated_at} column. The
 * {@link #justListed()} query orders on {@code starts_at DESC} accordingly.
 *
 * <p>Defined in Epic 07 sub-spec 1 §5.2.
 */
public interface FeaturedRepository extends Repository<Auction, Long> {

    @Query(value = """
            SELECT a.* FROM auctions a
            WHERE a.status = 'ACTIVE' AND a.ends_at > NOW()
            ORDER BY a.ends_at ASC, a.id ASC
            LIMIT 6
            """, nativeQuery = true)
    List<Auction> endingSoon();

    @Query(value = """
            SELECT a.* FROM auctions a
            WHERE a.status = 'ACTIVE'
            ORDER BY a.starts_at DESC, a.id DESC
            LIMIT 6
            """, nativeQuery = true)
    List<Auction> justListed();

    @Query(value = """
            SELECT a.* FROM auctions a
            WHERE a.status = 'ACTIVE'
            ORDER BY (
              SELECT COUNT(*) FROM bids b
              WHERE b.auction_id = a.id
                AND b.created_at > NOW() - INTERVAL '6 hours'
            ) DESC, a.ends_at ASC, a.id DESC
            LIMIT 6
            """, nativeQuery = true)
    List<Auction> mostActive();
}
