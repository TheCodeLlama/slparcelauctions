package com.slparcelauctions.backend.realty.rating;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.realty.rating.dto.GroupRatingDto;
import com.slparcelauctions.backend.realty.rating.dto.GroupReviewRowDto;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Aggregated star-rating computation for a realty group, with a Redis
 * read-through cache keyed by the group's internal id. Reads pull from
 * {@code reviews} via the auction-&gt;group linkage described in spec
 * §16.1 (both direct {@code auctions.realty_group_id} and indirect
 * group-sale {@code realty_group_sl_groups} coverage).
 *
 * <p>The cached value is a tiny {@code "{avg}|{count}"} string — small
 * enough that a dedicated JSON serializer would add more risk (cache
 * shape drift across redeploys) than value. {@code averageRating} is
 * {@code null} when no reviews exist; the cache encoding represents that
 * as the literal string {@code "null"} so a parse round-trip is
 * unambiguous.
 *
 * <p>Cache invalidation is the {@link GroupRatingCacheInvalidator}'s job;
 * this service exposes {@link #invalidate(Long)} for that listener and
 * does not invalidate on its own.
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-05-12-realty-groups-admin-moderation-design.md} §16.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GroupRatingService {

    /** Redis key prefix. The full key is {@code realty_groups_rating:{groupId}}. */
    static final String CACHE_KEY_PREFIX = "realty_groups_rating:";

    /** Redis entry TTL. Long enough to absorb a profile-page-traffic spike, short enough
     *  that a missed invalidation event self-heals within an hour. */
    static final Duration TTL = Duration.ofHours(1);

    private final EntityManager em;
    private final StringRedisTemplate redis;

    /**
     * Compute or read-through the rating for a group. Returns an empty
     * DTO ({@code averageRating=null, reviewCount=0}) when no qualifying
     * reviews exist; never throws on cache failure — a Redis hiccup falls
     * back to a fresh DB read and the write-back is best-effort.
     */
    @Transactional(readOnly = true)
    public GroupRatingDto computeRating(Long groupId) {
        String cacheKey = CACHE_KEY_PREFIX + groupId;

        String cached = null;
        try {
            cached = redis.opsForValue().get(cacheKey);
        } catch (RuntimeException e) {
            log.warn("Failed to read group rating cache for group {}: {}", groupId, e.toString());
        }
        if (cached != null) {
            GroupRatingDto parsed = parse(cached);
            if (parsed != null) {
                return parsed;
            }
            log.warn("Unparseable cached rating for group {}: {}", groupId, cached);
        }

        // Direct (auction.realty_group_id) UNION group-sale
        // (auction.realty_group_sl_group_id -> realty_group_sl_groups.realty_group_id).
        // Column name is `rating` on this codebase's
        // `reviews` table (not `star_rating` as some spec snippets use). Cast AVG to double
        // precision so Hibernate returns Double, not BigDecimal — keeps the DTO shape simple.
        Tuple result = (Tuple) em.createNativeQuery("""
            SELECT AVG(r.rating)::double precision AS avg_rating,
                   COUNT(*)                         AS review_count
              FROM reviews r
              JOIN auctions a ON a.id = r.auction_id
             WHERE a.realty_group_id = :groupId
                OR EXISTS (
                  SELECT 1 FROM realty_group_sl_groups rsg
                   WHERE rsg.id = a.realty_group_sl_group_id
                     AND rsg.realty_group_id = :groupId
                )
            """, Tuple.class)
            .setParameter("groupId", groupId)
            .getSingleResult();

        Double avg = result.get(0) == null ? null : ((Number) result.get(0)).doubleValue();
        long count = result.get(1) == null ? 0L : ((Number) result.get(1)).longValue();
        GroupRatingDto dto = new GroupRatingDto(avg, count);

        try {
            redis.opsForValue().set(cacheKey, serialize(dto), TTL);
        } catch (RuntimeException e) {
            log.warn("Failed to cache group rating for group {}: {}", groupId, e.toString());
        }
        return dto;
    }

    /**
     * Sub-project G §13.1 — paginated list of every visible review on
     * auctions attributed to the realty group. Attribution mirrors
     * {@link #computeRating(Long)}: direct via
     * {@code auctions.realty_group_id} OR group-sale indirect via
     * {@code realty_group_sl_groups.realty_group_id}. Anonymous-accessible;
     * returns an empty page when no reviews exist.
     *
     * <p>Only {@code visible = true} rows are listed — pending blind-reveal
     * submissions are not exposed on the public group reviews page (the
     * reviewer sees their own pending row on the user-side reviews surface
     * via {@code ReviewDto#of}, not here).
     *
     * <p>Ordering: newest first ({@code r.created_at DESC}). Caller is
     * responsible for translating the {@link Pageable} into
     * {@code page=N&size=M} query params.
     *
     * <p>Implementation: native SQL keeps parity with
     * {@link #computeRating(Long)}'s attribution query without forcing a
     * second JPQL path that could drift from the rating predicate.
     */
    @Transactional(readOnly = true)
    public Page<GroupReviewRowDto> listReviews(Long groupId, Pageable pageable) {
        String selectSql = """
            SELECT u.public_id           AS reviewer_public_id,
                   u.display_name        AS reviewer_display_name,
                   r.rating              AS rating,
                   r.text                AS comment,
                   a.public_id           AS auction_public_id,
                   a.title               AS auction_title,
                   r.created_at          AS created_at
              FROM reviews r
              JOIN auctions a ON a.id = r.auction_id
              JOIN users    u ON u.id = r.reviewer_id
             WHERE r.visible = true
               AND (a.realty_group_id = :groupId
                    OR EXISTS (SELECT 1 FROM realty_group_sl_groups rsg
                                WHERE rsg.id = a.realty_group_sl_group_id
                                  AND rsg.realty_group_id = :groupId))
             ORDER BY r.created_at DESC
             LIMIT :limit OFFSET :offset
            """;
        String countSql = """
            SELECT COUNT(*)
              FROM reviews r
              JOIN auctions a ON a.id = r.auction_id
             WHERE r.visible = true
               AND (a.realty_group_id = :groupId
                    OR EXISTS (SELECT 1 FROM realty_group_sl_groups rsg
                                WHERE rsg.id = a.realty_group_sl_group_id
                                  AND rsg.realty_group_id = :groupId))
            """;

        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery(selectSql, Tuple.class)
            .setParameter("groupId", groupId)
            .setParameter("limit", pageable.getPageSize())
            .setParameter("offset", pageable.getOffset())
            .getResultList();

        long total = ((Number) em.createNativeQuery(countSql)
            .setParameter("groupId", groupId)
            .getSingleResult()).longValue();

        List<GroupReviewRowDto> content = rows.stream().map(t -> new GroupReviewRowDto(
                (UUID) t.get("reviewer_public_id"),
                (String) t.get("reviewer_display_name"),
                ((Number) t.get("rating")).intValue(),
                (String) t.get("comment"),
                (UUID) t.get("auction_public_id"),
                (String) t.get("auction_title"),
                toInstant(t.get("created_at"))))
            .toList();

        return new PageImpl<>(content, pageable, total);
    }

    /**
     * Evict the cached entry for one group. No-op if no entry was
     * cached. Safe to call from outside a transaction.
     */
    public void invalidate(Long groupId) {
        try {
            redis.delete(CACHE_KEY_PREFIX + groupId);
        } catch (RuntimeException e) {
            log.warn("Failed to invalidate group rating cache for group {}: {}", groupId, e.toString());
        }
    }

    /**
     * Coerce a {@code timestamptz} cell to {@link java.time.Instant}. Postgres + Hibernate 6
     * binds these to {@link java.time.OffsetDateTime} by default, but some driver versions
     * surface them as {@link java.sql.Timestamp} or already as {@link java.time.Instant};
     * accept all three so the native query doesn't trip {@code ClassCastException} when the
     * driver flavour changes between local / CI / prod.
     */
    private static java.time.Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof java.time.Instant i) return i;
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        throw new IllegalStateException(
            "Unexpected timestamptz mapping: " + value.getClass().getName());
    }

    /** Encode {@code "{avg}|{count}"}; uses the literal {@code "null"} for an absent average. */
    static String serialize(GroupRatingDto dto) {
        String avgPart = dto.averageRating() == null ? "null" : Double.toString(dto.averageRating());
        return avgPart + "|" + dto.reviewCount();
    }

    /** Decode the cache string back to a DTO. Returns {@code null} when the encoding is
     *  unrecognised (e.g. left over from a prior schema). Caller falls back to a DB read. */
    static GroupRatingDto parse(String cached) {
        int sep = cached.indexOf('|');
        if (sep < 0) return null;
        String avgPart = cached.substring(0, sep);
        String countPart = cached.substring(sep + 1);
        try {
            Double avg = "null".equals(avgPart) ? null : Double.valueOf(avgPart);
            long count = Long.parseLong(countPart);
            return new GroupRatingDto(avg, count);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
