package com.slparcelauctions.backend.realty;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.slparcelauctions.backend.realty.browse.RealtyGroupCardProjection;

public interface RealtyGroupRepository extends JpaRepository<RealtyGroup, Long> {

    Optional<RealtyGroup> findByPublicId(UUID publicId);

    /** Active-group lookup by public UUID; used by the listing integration service. */
    Optional<RealtyGroup> findByPublicIdAndDissolvedAtIsNull(UUID publicId);

    /** Public-page lookup; only returns active groups. */
    Optional<RealtyGroup> findBySlugAndDissolvedAtIsNull(String slug);

    /** All rows matching a slug regardless of dissolution; used by the public controller to
     *  distinguish "never existed" (404) from "dissolved" (410). Returns at most one active
     *  row at a time (partial unique index), but multiple dissolved rows may share the same
     *  slug — we surface the most recently dissolved one. */
    Optional<RealtyGroup> findFirstBySlugAndDissolvedAtIsNotNullOrderByDissolvedAtDesc(String slug);

    /** Case-insensitive name lookup; only returns active groups (the partial unique index
     *  on name_lower enforces uniqueness over the active set). */
    @Query("SELECT g FROM RealtyGroup g WHERE LOWER(g.name) = LOWER(:name) AND g.dissolvedAt IS NULL")
    Optional<RealtyGroup> findByNameIgnoreCaseActive(@Param("name") String name);

    @Query("SELECT COUNT(g) FROM RealtyGroup g WHERE g.slug = :slug AND g.dissolvedAt IS NULL")
    long countActiveBySlug(@Param("slug") String slug);

    /** Used by the slug factory during rename so the row being renamed doesn't collide with
     *  itself. */
    @Query("SELECT COUNT(g) FROM RealtyGroup g WHERE g.slug = :slug AND g.dissolvedAt IS NULL AND g.id <> :excludeId")
    long countOtherActiveBySlug(@Param("slug") String slug, @Param("excludeId") Long excludeId);

    /** Groups led by the given user (active only). */
    List<RealtyGroup> findByLeaderIdAndDissolvedAtIsNullOrderByCreatedAtDesc(Long leaderId);

    /** Active groups in which the given user holds a membership row. Joined via the
     *  member table so the result includes groups the user leads (the leader's row exists
     *  by invariant) as well as groups they joined as an agent. */
    @Query("""
        SELECT g FROM RealtyGroup g
         WHERE g.dissolvedAt IS NULL
           AND g.id IN (SELECT m.groupId FROM RealtyGroupMember m WHERE m.userId = :userId)
         ORDER BY g.createdAt DESC
        """)
    List<RealtyGroup> findActiveByMemberUserId(@Param("userId") Long userId);

    /** Admin list: paginated, filterable by status (active/dissolved/all). */
    @Query("""
        SELECT g FROM RealtyGroup g
         WHERE (:includeActive = TRUE AND g.dissolvedAt IS NULL)
            OR (:includeDissolved = TRUE AND g.dissolvedAt IS NOT NULL)
        """)
    Page<RealtyGroup> findForAdmin(
        @Param("includeActive") boolean includeActive,
        @Param("includeDissolved") boolean includeDissolved,
        Pageable pageable);

    /**
     * Admin list with case-insensitive substring search on the group's display name.
     * {@code search} is matched via {@code LOWER(name) LIKE %lower%} so e.g. "Mainland" finds
     * "Mainland Realty Co". When {@code search} is blank/null the predicate degenerates to
     * "match any" via the OR-with-null guard.
     *
     * <p>{@code :search} is wrapped in {@code CAST(... AS string)} so PostgreSQL knows the
     * parameter's type at SQL-prepare time. Without the cast, Hibernate emits the parameter
     * as a typed null whose JDBC type code maps to {@code bytea} server-side, and the
     * planner blows up with {@code ERROR: function lower(bytea) does not exist} the first
     * time an admin hits the list page with no search query. Same cast pattern as the
     * browse-cards native query.
     */
    @Query("""
        SELECT g FROM RealtyGroup g
         WHERE ((:includeActive = TRUE AND g.dissolvedAt IS NULL)
             OR (:includeDissolved = TRUE AND g.dissolvedAt IS NOT NULL))
           AND (:search IS NULL OR LOWER(g.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
        """)
    Page<RealtyGroup> findForAdminWithSearch(
        @Param("includeActive") boolean includeActive,
        @Param("includeDissolved") boolean includeDissolved,
        @Param("search") String search,
        Pageable pageable);

    /** Pessimistic-write lock for wallet operations. Spec §5.3 step 6. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM RealtyGroup g WHERE g.id = :id")
    Optional<RealtyGroup> findByIdForUpdate(@Param("id") Long id);

    /**
     * Sub-project D §10.1: groups with positive balance and no dormancy phase
     * where no current member has rotated a refresh token within {@code windowDays}.
     */
    @Query(value = """
        SELECT g.* FROM realty_groups g
        WHERE g.dissolved_at IS NULL
          AND g.balance_lindens > 0
          AND g.wallet_dormancy_phase IS NULL
          AND NOT EXISTS (
              SELECT 1
              FROM realty_group_members m
              JOIN refresh_tokens rt ON rt.user_id = m.user_id
              WHERE m.group_id = g.id
                AND rt.created_at > now() - make_interval(days => :windowDays)
          )
        """, nativeQuery = true)
    List<RealtyGroup> findEligibleForDormancyFlag(@Param("windowDays") int windowDays);

    /**
     * Groups whose current dormancy phase is due for the next escalation
     * (phase 1 to 2 etc) based on {@code phaseDurationDays} since
     * wallet_dormancy_started_at.
     */
    @Query(value = """
        SELECT g.* FROM realty_groups g
        WHERE g.wallet_dormancy_phase BETWEEN 1 AND 4
          AND g.wallet_dormancy_started_at < (now() - make_interval(days => :phaseDurationDays * g.wallet_dormancy_phase))
        """, nativeQuery = true)
    List<RealtyGroup> findDormancyPhaseDue(@Param("phaseDurationDays") int phaseDurationDays);

    /**
     * Public browse-cards query for {@code GET /api/v1/realty-groups}. Spec
     * section 6.1, extended in the template-1:1 restoration with optional
     * user-driven filters that mirror the template's sidebar controls.
     *
     * <p>Filters always applied (impossible to disable from the wire):
     * <ul>
     *   <li>group not dissolved ({@code dissolved_at IS NULL}),</li>
     *   <li>at least one verified SL-group linkage exists, and</li>
     *   <li>no currently-active suspension is in force.</li>
     * </ul>
     *
     * <p>Optional caller-driven filters:
     * <ul>
     *   <li>{@code q} — case-insensitive substring match on name + description;
     *       NULL or blank disables the predicate.</li>
     *   <li>{@code minRating} — minimum average review rating in {@code [0, 5]}.
     *       A group with no reviews has a coalesced rating of {@code 0}, so
     *       any {@code minRating > 0} implicitly hides unreviewed groups
     *       (same semantics as the template's StarPicker filter). Zero is a
     *       no-op.</li>
     *   <li>{@code minReviews} — minimum review count. Zero is a no-op.</li>
     *   <li>{@code activeOnly} — when {@code TRUE}, restrict to groups with
     *       at least one scheduled or live auction (matches the template's
     *       "Has active listing" checkbox). {@code FALSE} is a no-op.</li>
     * </ul>
     *
     * <p>{@code AVG(r.rating)} is cast to {@code double precision} so Hibernate
     * binds it as {@link Double} (matching {@code GroupRatingDto.averageRating}'s
     * shape); the same cast pattern lives in
     * {@code GroupRatingService.computeRating}.
     */
    @Query(value = """
        SELECT
          g.public_id AS publicId,
          g.name AS name,
          g.slug AS slug,
          g.description AS description,
          g.logo_object_key AS logoObjectKey,
          g.cover_object_key AS coverObjectKey,
          g.created_at AS createdAt,
          (SELECT count(*)::int FROM realty_group_members m
             WHERE m.group_id = g.id) AS memberCount,
          g.member_seat_limit AS memberSeatLimit,
          (SELECT count(*) FROM auctions a
             WHERE a.realty_group_id = g.id
               AND a.status IN ('SCHEDULED','LIVE')) AS activeListings,
          (SELECT count(*) FROM auctions a
             WHERE a.realty_group_id = g.id
               AND a.status = 'COMPLETED') AS completedSales,
          (SELECT AVG(r.rating)::double precision FROM reviews r
             JOIN auctions a ON r.auction_id = a.id
             WHERE a.realty_group_id = g.id) AS averageRating,
          (SELECT count(*) FROM reviews r
             JOIN auctions a ON r.auction_id = a.id
             WHERE a.realty_group_id = g.id) AS reviewCount
        FROM realty_groups g
        WHERE g.dissolved_at IS NULL
          AND EXISTS (SELECT 1 FROM realty_group_sl_groups s
                       WHERE s.realty_group_id = g.id AND s.verified = TRUE)
          AND NOT EXISTS (SELECT 1 FROM realty_group_suspensions sus
                           WHERE sus.realty_group_id = g.id
                             AND sus.lifted_at IS NULL)
          AND (:q IS NULL OR LOWER(g.name) LIKE CONCAT('%', LOWER(CAST(:q AS text)), '%')
                          OR LOWER(g.description) LIKE CONCAT('%', LOWER(CAST(:q AS text)), '%'))
          AND (:minRating <= 0 OR COALESCE(
                 (SELECT AVG(r.rating)::double precision FROM reviews r
                    JOIN auctions a ON r.auction_id = a.id
                    WHERE a.realty_group_id = g.id),
                 0) >= :minRating)
          AND (:minReviews <= 0 OR
                 (SELECT count(*) FROM reviews r
                    JOIN auctions a ON r.auction_id = a.id
                    WHERE a.realty_group_id = g.id) >= :minReviews)
          AND (:activeOnly = FALSE OR EXISTS (
                 SELECT 1 FROM auctions a
                  WHERE a.realty_group_id = g.id
                    AND a.status IN ('SCHEDULED','LIVE')))
        """,
        countQuery = """
        SELECT count(*)
        FROM realty_groups g
        WHERE g.dissolved_at IS NULL
          AND EXISTS (SELECT 1 FROM realty_group_sl_groups s
                       WHERE s.realty_group_id = g.id AND s.verified = TRUE)
          AND NOT EXISTS (SELECT 1 FROM realty_group_suspensions sus
                           WHERE sus.realty_group_id = g.id
                             AND sus.lifted_at IS NULL)
          AND (:q IS NULL OR LOWER(g.name) LIKE CONCAT('%', LOWER(CAST(:q AS text)), '%')
                          OR LOWER(g.description) LIKE CONCAT('%', LOWER(CAST(:q AS text)), '%'))
          AND (:minRating <= 0 OR COALESCE(
                 (SELECT AVG(r.rating)::double precision FROM reviews r
                    JOIN auctions a ON r.auction_id = a.id
                    WHERE a.realty_group_id = g.id),
                 0) >= :minRating)
          AND (:minReviews <= 0 OR
                 (SELECT count(*) FROM reviews r
                    JOIN auctions a ON r.auction_id = a.id
                    WHERE a.realty_group_id = g.id) >= :minReviews)
          AND (:activeOnly = FALSE OR EXISTS (
                 SELECT 1 FROM auctions a
                  WHERE a.realty_group_id = g.id
                    AND a.status IN ('SCHEDULED','LIVE')))
        """,
        nativeQuery = true)
    Page<RealtyGroupCardProjection> browseCards(
        @Param("q") String q,
        @Param("minRating") double minRating,
        @Param("minReviews") int minReviews,
        @Param("activeOnly") boolean activeOnly,
        Pageable pageable);

    /**
     * Single-row activity summary for one group, used by the public profile DTO
     * to fill the template's 4-stat grid + Verified-SL-group badge without
     * three extra round-trips to the {@code auctions} and
     * {@code realty_group_sl_groups} tables.
     *
     * <p>Cast active/sales counts to {@code int} server-side; the DTO field is
     * {@code int}, and SL Parcels caps a group's lifetime auctions far below
     * 2^31.
     */
    @Query(value = """
        SELECT
          (SELECT count(*) FROM auctions a
             WHERE a.realty_group_id = :groupId
               AND a.status IN ('SCHEDULED','LIVE'))::int AS activeListings,
          (SELECT count(*) FROM auctions a
             WHERE a.realty_group_id = :groupId
               AND a.status = 'COMPLETED')::int AS completedSales,
          EXISTS (SELECT 1 FROM realty_group_sl_groups s
                    WHERE s.realty_group_id = :groupId
                      AND s.verified = TRUE) AS hasVerifiedSlGroup
        """, nativeQuery = true)
    RealtyGroupActivityProjection findActivity(@Param("groupId") Long groupId);
}
