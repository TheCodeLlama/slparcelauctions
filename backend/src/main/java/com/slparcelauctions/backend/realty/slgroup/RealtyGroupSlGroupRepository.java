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

    /**
     * Pending rows due for an about-text poll. The partial index
     * ix_rg_sl_groups_pending_poll covers this query (verified=false AND
     * verified_via IS NULL).
     */
    @Query("""
        SELECT r FROM RealtyGroupSlGroup r
         WHERE r.verified = false
           AND r.verifiedVia IS NULL
           AND r.verificationCodeExpiresAt > :now
           AND (r.lastPolledAt IS NULL OR r.lastPolledAt < :pollCutoff)
        """)
    List<RealtyGroupSlGroup> findDueForAboutTextPoll(
            @Param("now") OffsetDateTime now,
            @Param("pollCutoff") OffsetDateTime pollCutoff);

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
}
