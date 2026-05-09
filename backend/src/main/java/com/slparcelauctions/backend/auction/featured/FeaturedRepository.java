package com.slparcelauctions.backend.auction.featured;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.slparcelauctions.backend.auction.Auction;

/**
 * Native-SQL queries backing the three homepage rails.
 *
 * <p>{@link #featured()} blends admin-curated rows (ordered by
 * {@code ends_at ASC}, capped at 4) with auto-fill rows (highest
 * {@code current_bid} DESC) so paying / curated slots always render
 * before organic fill. {@link #endingSoon()} returns the soonest-closing
 * ACTIVE auctions. {@link #trending()} computes a 24h weighted score
 * (bids * 2 + saves) per auction.
 */
public interface FeaturedRepository extends Repository<Auction, Long> {

    @Query(value = """
            WITH curated AS (
              SELECT a.*, 0 AS bucket, a.ends_at AS sort_key
              FROM auctions a
              WHERE a.is_featured = TRUE
                AND (a.featured_until IS NULL OR a.featured_until > NOW())
                AND a.status = 'ACTIVE'
                AND a.ends_at > NOW()
              ORDER BY a.ends_at ASC, a.id ASC
              LIMIT 4
            ),
            fill AS (
              SELECT a.*, 1 AS bucket, NULL::timestamptz AS sort_key
              FROM auctions a
              WHERE a.status = 'ACTIVE'
                AND a.ends_at > NOW()
                AND a.id NOT IN (SELECT id FROM curated)
              ORDER BY a.current_bid DESC, a.id DESC
              LIMIT 4
            )
            SELECT * FROM (
              SELECT * FROM curated
              UNION ALL
              SELECT * FROM (
                SELECT * FROM fill
                LIMIT GREATEST(0, 4 - (SELECT COUNT(*) FROM curated))
              ) f
            ) blended
            ORDER BY bucket ASC, sort_key ASC NULLS LAST, current_bid DESC, id DESC
            LIMIT 4
            """, nativeQuery = true)
    List<Auction> featured();

    @Query(value = """
            SELECT a.* FROM auctions a
            WHERE a.status = 'ACTIVE' AND a.ends_at > NOW()
            ORDER BY a.ends_at ASC, a.id ASC
            LIMIT 6
            """, nativeQuery = true)
    List<Auction> endingSoon();

    @Query(value = """
            SELECT a.* FROM auctions a
            WHERE a.status = 'ACTIVE' AND a.ends_at > NOW()
            ORDER BY (
              (SELECT COUNT(*) FROM bids b
                WHERE b.auction_id = a.id
                  AND b.created_at > NOW() - INTERVAL '24 hours') * 2
              +
              (SELECT COUNT(*) FROM saved_auctions s
                WHERE s.auction_id = a.id
                  AND s.saved_at > NOW() - INTERVAL '24 hours')
            ) DESC, a.ends_at ASC, a.id DESC
            LIMIT 6
            """, nativeQuery = true)
    List<Auction> trending();
}
