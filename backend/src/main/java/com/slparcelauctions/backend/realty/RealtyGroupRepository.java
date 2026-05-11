package com.slparcelauctions.backend.realty;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RealtyGroupRepository extends JpaRepository<RealtyGroup, Long> {

    Optional<RealtyGroup> findByPublicId(UUID publicId);

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
     */
    @Query("""
        SELECT g FROM RealtyGroup g
         WHERE ((:includeActive = TRUE AND g.dissolvedAt IS NULL)
             OR (:includeDissolved = TRUE AND g.dissolvedAt IS NOT NULL))
           AND (:search IS NULL OR LOWER(g.name) LIKE LOWER(CONCAT('%', :search, '%')))
        """)
    Page<RealtyGroup> findForAdminWithSearch(
        @Param("includeActive") boolean includeActive,
        @Param("includeDissolved") boolean includeDissolved,
        @Param("search") String search,
        Pageable pageable);
}
