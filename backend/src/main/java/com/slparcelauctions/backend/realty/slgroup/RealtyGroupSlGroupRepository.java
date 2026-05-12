package com.slparcelauctions.backend.realty.slgroup;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.slparcelauctions.backend.realty.RealtyGroup;

public interface RealtyGroupSlGroupRepository extends JpaRepository<RealtyGroupSlGroup, Long> {

    Optional<RealtyGroupSlGroup> findByPublicId(UUID publicId);

    /** UNIQUE constraint guarantees at most one row per sl_group_uuid. */
    Optional<RealtyGroupSlGroup> findBySlGroupUuid(UUID slGroupUuid);

    List<RealtyGroupSlGroup> findByRealtyGroupIdOrderByCreatedAtDesc(Long realtyGroupId);

    /**
     * Verified row for a (realty group, sl group) pair, if any. Used by the listing-create
     * gate: the parcel's owner SL group UUID must have a verified registration for the realty
     * group the agent is listing under.
     */
    @Query("""
        SELECT r FROM RealtyGroupSlGroup r
         WHERE r.realtyGroupId = :realtyGroupId
           AND r.slGroupUuid = :slGroupUuid
           AND r.verified = true
        """)
    Optional<RealtyGroupSlGroup> findVerifiedForListing(
            @Param("realtyGroupId") Long realtyGroupId,
            @Param("slGroupUuid") UUID slGroupUuid);

    /** Pending rows whose verification window has expired. Used by the hourly cleanup task. */
    @Query("""
        SELECT r FROM RealtyGroupSlGroup r
         WHERE r.verified = false
           AND r.verificationCodeExpiresAt < :now
        """)
    List<RealtyGroupSlGroup> findExpiredPending(@Param("now") OffsetDateTime now);

    /** Used by founder-terminal callback to find the pending row by code. */
    @Query("""
        SELECT r FROM RealtyGroupSlGroup r
         WHERE r.verified = false
           AND r.verificationCode = :code
           AND r.verificationCodeExpiresAt > :now
        """)
    Optional<RealtyGroupSlGroup> findPendingByCode(
            @Param("code") String code,
            @Param("now") OffsetDateTime now);

    /** Used by dissolve gate. */
    long countByRealtyGroupId(Long realtyGroupId);

    /**
     * Sub-project F §13.1 — rows due for periodic re-validation against the SL World API.
     *
     * <p>Returns verified, still-registered ({@code unregistered_at IS NULL}) rows whose
     * {@code last_revalidated_at} is older than the cadence threshold (or NULL, meaning
     * they've never been revalidated). The {@code SlGroupReverifyTask} computes
     * {@code threshold = now - reverifyCadenceDays} and hands it in; this query then
     * filters everything that's still within cadence.
     */
    @Query("""
        SELECT r FROM RealtyGroupSlGroup r
         WHERE r.verified = true
           AND r.unregisteredAt IS NULL
           AND (r.lastRevalidatedAt IS NULL OR r.lastRevalidatedAt < :threshold)
        """)
    List<RealtyGroupSlGroup> findDueForReverify(@Param("threshold") OffsetDateTime threshold);

    /**
     * Sub-project E §5.3 — parcel-aware listing-eligible-groups.
     *
     * <p>Returns the (active) realty groups that:
     * <ul>
     *   <li>have a verified registration ({@link RealtyGroupSlGroup#isVerified() verified=true})
     *       for the SL group UUID that owns the parcel the caller wants to list, AND</li>
     *   <li>the caller holds a {@link com.slparcelauctions.backend.realty.RealtyGroupMember
     *       membership} row in.</li>
     * </ul>
     *
     * <p>The {@code CREATE_LISTING}-or-leader filter is applied in the service layer in
     * Java — the {@code permissions text[]} column is array-typed Postgres-native and the
     * codebase's idiom is to load the member row and call {@code permissionSet().contains(...)}
     * (mirroring the leader-implicit-all-permissions rule). See
     * {@code RealtyGroupListingController.myEligibleGroups} (pre-E) for the prior idiom.
     */
    @Query("""
        SELECT g FROM RealtyGroup g
         WHERE g.dissolvedAt IS NULL
           AND g.id IN (
               SELECT r.realtyGroupId FROM RealtyGroupSlGroup r
                WHERE r.slGroupUuid = :slGroupUuid AND r.verified = true)
           AND g.id IN (
               SELECT m.groupId FROM RealtyGroupMember m WHERE m.userId = :callerUserId)
         ORDER BY g.createdAt DESC
        """)
    List<RealtyGroup> findRealtyGroupsForListingCaller(
            @Param("callerUserId") Long callerUserId,
            @Param("slGroupUuid") UUID slGroupUuid);

    /**
     * Sub-project G §7.3 -- the realty group's currently-registered SL group,
     * if any. Excludes force-unregistered rows ({@code unregistered_at IS NOT
     * NULL}). {@code UNIQUE(sl_group_uuid)} and the per-realty-group registration
     * convention guarantee at most one such row per realty group at a time;
     * the helper returns a list (rather than {@code Optional}) to stay safe
     * against the unlikely race where two rows are momentarily verified -- the
     * caller takes the first.
     */
    @Query("""
        SELECT r FROM RealtyGroupSlGroup r
         WHERE r.realtyGroupId = :realtyGroupId
           AND r.verified = true
           AND r.unregisteredAt IS NULL
         ORDER BY r.verifiedAt DESC
        """)
    List<RealtyGroupSlGroup> findCurrentRegisteredForRealtyGroup(
            @Param("realtyGroupId") Long realtyGroupId);
}
