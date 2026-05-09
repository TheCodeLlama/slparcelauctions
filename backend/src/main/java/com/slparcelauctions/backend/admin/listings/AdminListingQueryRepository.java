package com.slparcelauctions.backend.admin.listings;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import com.slparcelauctions.backend.admin.listings.dto.AdminListingFilterParams;
import com.slparcelauctions.backend.admin.listings.dto.AdminListingRowDto;
import com.slparcelauctions.backend.admin.listings.exception.AdminListingStateException;
import com.slparcelauctions.backend.auction.AuctionStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

/**
 * Native-SQL reader for the admin listings table. Owns the dynamic WHERE
 * construction (search / statuses / hasReserve are each optional) and the
 * sort-column whitelist. Issues two queries: one for the page rows + one for
 * the total count (so {@link Page#getTotalElements()} is honest).
 *
 * <p>The {@code save_count} column is computed via a {@code LEFT JOIN}
 * subquery against {@code saved_auctions}; the {@code ix_saved_auctions_auction_id}
 * index added in V16 keeps that aggregation cheap.
 */
@Repository
public class AdminListingQueryRepository {

    @PersistenceContext
    private EntityManager em;

    /**
     * DTO property name → SQL column expression (for ORDER BY). Property
     * names match {@link AdminListingRowDto} field names so the frontend can
     * pass DTO-property names in the {@code ?sort=...} param.
     */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
        "title",        "a.title",
        "seller",       "u.username",
        "createdAt",    "a.created_at",
        "startingBid",  "a.starting_bid",
        "currentBid",   "a.current_bid",
        "bidCount",     "a.bid_count",
        "saveCount",    "save_count",
        "endsAt",       "a.ends_at",
        "region",       "ps.region_name"
    );

    public Page<AdminListingRowDto> search(AdminListingFilterParams params, Pageable pageable) {
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        if (params.search() != null) {
            where.append(" AND (LOWER(a.title) LIKE :search OR LOWER(u.username) LIKE :search) ");
        }
        if (params.statuses() != null && !params.statuses().isEmpty()) {
            where.append(" AND a.status IN (:statuses) ");
        }
        if (params.hasReserve() != null) {
            where.append(params.hasReserve()
                    ? " AND a.reserve_price IS NOT NULL "
                    : " AND a.reserve_price IS NULL ");
        }
        if (params.featured() != null && params.featured()) {
            where.append(" AND a.is_featured = TRUE ");
            where.append(" AND (a.featured_until IS NULL OR a.featured_until > NOW()) ");
        }

        String selectSql = """
            SELECT
                a.public_id        AS public_id,
                a.title            AS title,
                u.public_id        AS seller_public_id,
                u.username         AS seller_username,
                a.status           AS status,
                (a.reserve_price IS NOT NULL) AS has_reserve,
                a.created_at       AS created_at,
                a.starting_bid     AS starting_bid,
                a.current_bid      AS current_bid,
                a.bid_count        AS bid_count,
                COALESCE(s.save_count, 0) AS save_count,
                a.ends_at          AS ends_at,
                ps.region_name     AS region_name,
                a.is_featured      AS is_featured,
                a.featured_until   AS featured_until
            FROM auctions a
            JOIN users u ON u.id = a.seller_id
            LEFT JOIN auction_parcel_snapshots ps ON ps.auction_id = a.id
            LEFT JOIN (
                SELECT auction_id, COUNT(*) AS save_count
                  FROM saved_auctions
                 GROUP BY auction_id
            ) s ON s.auction_id = a.id
            """ + where + buildOrderBy(pageable.getSort()) +
            " LIMIT :limit OFFSET :offset ";

        Query q = em.createNativeQuery(selectSql);
        bindParams(q, params);
        q.setParameter("limit", pageable.getPageSize());
        q.setParameter("offset", pageable.getOffset());

        @SuppressWarnings("unchecked")
        List<Object[]> raw = q.getResultList();
        List<AdminListingRowDto> rows = new ArrayList<>(raw.size());
        for (Object[] r : raw) {
            rows.add(mapRow(r));
        }

        long total = countMatching(params, where.toString());
        return new PageImpl<>(rows, pageable, total);
    }

    private long countMatching(AdminListingFilterParams params, String whereClause) {
        String countSql = """
            SELECT COUNT(*)
              FROM auctions a
              JOIN users u ON u.id = a.seller_id
            """ + whereClause;
        Query c = em.createNativeQuery(countSql);
        bindParams(c, params);
        Object result = c.getSingleResult();
        if (result instanceof Number n) {
            return n.longValue();
        }
        throw new IllegalStateException("Count query returned unexpected type: " + result);
    }

    private void bindParams(Query q, AdminListingFilterParams params) {
        if (params.search() != null) {
            q.setParameter("search", "%" + params.search().toLowerCase() + "%");
        }
        if (params.statuses() != null && !params.statuses().isEmpty()) {
            q.setParameter("statuses", params.statuses().stream().map(Enum::name).toList());
        }
    }

    /**
     * Builds the {@code ORDER BY} fragment from the {@link Sort}. Validates
     * each property against the {@link #SORT_COLUMNS} whitelist and throws
     * {@code INVALID_SORT_COLUMN} on miss. Defaults to
     * {@code ORDER BY a.created_at DESC} when no sort was supplied.
     */
    private String buildOrderBy(Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return " ORDER BY a.created_at DESC ";
        }
        StringBuilder sb = new StringBuilder(" ORDER BY ");
        boolean first = true;
        for (Sort.Order order : sort) {
            String col = SORT_COLUMNS.get(order.getProperty());
            if (col == null) {
                throw new AdminListingStateException(
                    "INVALID_SORT_COLUMN",
                    "Sort column '" + order.getProperty() + "' is not allowed");
            }
            if (!first) {
                sb.append(", ");
            }
            sb.append(col).append(' ').append(order.isAscending() ? "ASC" : "DESC");
            first = false;
        }
        // Stable tiebreaker so paged results don't shuffle.
        sb.append(", a.id ASC ");
        return sb.toString();
    }

    private AdminListingRowDto mapRow(Object[] r) {
        return new AdminListingRowDto(
            (UUID) r[0],
            (String) r[1],
            (UUID) r[2],
            (String) r[3],
            AuctionStatus.valueOf((String) r[4]),
            (Boolean) r[5],
            toOffsetDateTime(r[6]),
            toLong(r[7]),
            toLong(r[8]),
            toInteger(r[9]),
            toLong(r[10]),
            toOffsetDateTime(r[11]),
            (String) r[12],
            (Boolean) r[13],
            toOffsetDateTime(r[14])
        );
    }

    /**
     * Single-row read by {@code publicId}. Used by the admin Featured-toggle
     * endpoint to return the post-write row to the caller (the frontend
     * refreshes the row in place rather than re-fetching the whole list).
     */
    public Optional<AdminListingRowDto> findRowByPublicId(UUID publicId) {
        String sql = """
            SELECT
                a.public_id, a.title, u.public_id, u.username, a.status,
                (a.reserve_price IS NOT NULL),
                a.created_at, a.starting_bid, a.current_bid, a.bid_count,
                COALESCE(s.save_count, 0),
                a.ends_at, ps.region_name,
                a.is_featured, a.featured_until
            FROM auctions a
            JOIN users u ON u.id = a.seller_id
            LEFT JOIN auction_parcel_snapshots ps ON ps.auction_id = a.id
            LEFT JOIN (
                SELECT auction_id, COUNT(*) AS save_count
                  FROM saved_auctions
                 GROUP BY auction_id
            ) s ON s.auction_id = a.id
            WHERE a.public_id = :publicId
            """;
        Query q = em.createNativeQuery(sql);
        q.setParameter("publicId", publicId);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        if (rows.isEmpty()) return Optional.empty();
        return Optional.of(mapRow(rows.get(0)));
    }

    private static Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof BigInteger bi) return bi.longValueExact();
        if (o instanceof BigDecimal bd) return bd.longValueExact();
        if (o instanceof Number n) return n.longValue();
        throw new IllegalStateException("Cannot convert to Long: " + o.getClass());
    }

    private static Integer toInteger(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        throw new IllegalStateException("Cannot convert to Integer: " + o.getClass());
    }

    private static OffsetDateTime toOffsetDateTime(Object o) {
        if (o == null) return null;
        if (o instanceof OffsetDateTime odt) return odt;
        if (o instanceof java.sql.Timestamp ts) {
            return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        }
        if (o instanceof java.time.Instant inst) {
            return inst.atOffset(java.time.ZoneOffset.UTC);
        }
        throw new IllegalStateException("Cannot convert to OffsetDateTime: " + o.getClass());
    }
}
