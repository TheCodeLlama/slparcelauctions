package com.slparcelauctions.backend.auction.search;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * Window-function batch loader for the primary photo URL of each
 * auction in a search page. The {@code ROW_NUMBER() OVER PARTITION BY
 * auction_id} keeps the work to a single round-trip; without it we'd
 * either issue one query per auction (N+1) or pull every photo for the
 * page and pick a winner in Java.
 *
 * <p>The URL shape mirrors the live route on
 * {@link com.slparcelauctions.backend.auction.AuctionPhotoController}
 * ({@code GET /api/v1/auctions/&#123;auctionId&#125;/photos/&#123;photoId&#125;/bytes}).
 * If that route ever moves the search-result thumbnails will rot
 * silently — that's why the path is built here from the column data
 * rather than being a static constant: there's only one place to
 * update and it's the same place a JPA query would have to change.
 */
@Repository
@RequiredArgsConstructor
public class AuctionPhotoBatchRepositoryImpl implements AuctionPhotoBatchRepository {

    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public Map<Long, String> findPrimaryPhotoUrls(Collection<Long> auctionIds) {
        if (auctionIds == null || auctionIds.isEmpty()) {
            return Map.of();
        }

        // Window function picks the first photo per auction. Indexed via the
        // primary key on (id) — if (auction_id, sort_order) shows up in
        // slow-query logs, add a covering index then.
        String sql = """
                SELECT auction_id, url
                FROM (
                  SELECT auction_id,
                         '/api/v1/auctions/' || auction_id || '/photos/' || id || '/bytes' AS url,
                         ROW_NUMBER() OVER (
                           PARTITION BY auction_id
                           ORDER BY sort_order ASC, id ASC
                         ) AS rn
                  FROM auction_photos
                  WHERE auction_id IN (:ids)
                ) p
                WHERE rn = 1
                """;

        MapSqlParameterSource params = new MapSqlParameterSource("ids", auctionIds);
        Map<Long, String> result = new HashMap<>();
        jdbc.query(sql, params, (rs, i) -> {
            result.put(rs.getLong("auction_id"), rs.getString("url"));
            return null;
        });
        return result;
    }
}
