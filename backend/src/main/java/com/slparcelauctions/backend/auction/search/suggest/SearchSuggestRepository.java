package com.slparcelauctions.backend.auction.search.suggest;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * Native-SQL queries powering {@link SearchSuggestController}. Uses
 * {@code JdbcTemplate} directly rather than Spring Data Specifications
 * because the suggest path benefits from explicit {@code similarity()}
 * ordering — Criteria API doesn't express it cleanly. All three queries
 * hit the {@code pg_trgm} GIN indexes added in V22.
 */
@Repository
@RequiredArgsConstructor
public class SearchSuggestRepository {

    private final JdbcTemplate jdbc;

    /**
     * Top-N ACTIVE listings whose title, parcel name, or region name
     * substring-matches {@code raw}. Ranked by trigram similarity; ties
     * fall back to insertion order.
     */
    public List<SuggestListingDto> findListings(String raw, int limit) {
        String pattern = "%" + raw.toLowerCase() + "%";
        String sql = """
                SELECT a.public_id, a.title, ps.region_name,
                       ps.parcel_name, ph.public_id AS photo_public_id,
                       a.current_bid
                FROM auctions a
                JOIN auction_parcel_snapshots ps ON ps.auction_id = a.id
                LEFT JOIN auction_photos ph
                       ON ph.auction_id = a.id AND ph.sort_order = 0
                WHERE a.status = 'ACTIVE'
                  AND (LOWER(a.title) LIKE ?
                       OR LOWER(ps.parcel_name) LIKE ?
                       OR LOWER(ps.region_name) LIKE ?)
                ORDER BY GREATEST(
                    similarity(a.title, ?),
                    similarity(COALESCE(ps.parcel_name, ''), ?),
                    similarity(ps.region_name, ?)
                ) DESC
                LIMIT ?
                """;
        return jdbc.query(sql,
                (rs, i) -> new SuggestListingDto(
                        UUID.fromString(rs.getString("public_id")),
                        rs.getString("title"),
                        rs.getString("region_name"),
                        rs.getString("parcel_name"),
                        photoUrl(rs.getString("photo_public_id")),
                        rs.getLong("current_bid")),
                pattern, pattern, pattern, raw, raw, raw, limit);
    }

    /**
     * Top-N region names with at least one ACTIVE auction whose name
     * substring-matches {@code raw}. The {@code activeAuctionCount}
     * surfaces in the popover as "{n} active".
     */
    public List<SuggestRegionDto> findRegions(String raw, int limit) {
        String pattern = "%" + raw.toLowerCase() + "%";
        String sql = """
                SELECT r.name AS name, COUNT(a.id) AS active_count
                FROM regions r
                JOIN auction_parcel_snapshots ps ON ps.region_id = r.id
                JOIN auctions a ON a.id = ps.auction_id AND a.status = 'ACTIVE'
                WHERE LOWER(r.name) LIKE ?
                GROUP BY r.name
                ORDER BY similarity(r.name, ?) DESC
                LIMIT ?
                """;
        return jdbc.query(sql,
                (rs, i) -> new SuggestRegionDto(
                        rs.getString("name"),
                        rs.getInt("active_count")),
                pattern, raw, limit);
    }

    /**
     * Total count of ACTIVE auctions matching the same predicate the
     * listings query uses. Powers the popover's "See all N results"
     * footer; the footer is hidden when this equals the number of rows
     * already returned by {@link #findListings}.
     */
    public int countListings(String raw) {
        String pattern = "%" + raw.toLowerCase() + "%";
        String sql = """
                SELECT COUNT(*)
                FROM auctions a
                JOIN auction_parcel_snapshots ps ON ps.auction_id = a.id
                WHERE a.status = 'ACTIVE'
                  AND (LOWER(a.title) LIKE ?
                       OR LOWER(ps.parcel_name) LIKE ?
                       OR LOWER(ps.region_name) LIKE ?)
                """;
        Integer count = jdbc.queryForObject(sql, Integer.class,
                pattern, pattern, pattern);
        return count == null ? 0 : count;
    }

    private static String photoUrl(String publicId) {
        return publicId == null ? null : "/api/v1/photos/" + publicId;
    }
}
