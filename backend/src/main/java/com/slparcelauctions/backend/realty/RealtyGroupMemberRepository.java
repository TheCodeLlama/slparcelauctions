package com.slparcelauctions.backend.realty;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RealtyGroupMemberRepository extends JpaRepository<RealtyGroupMember, Long> {

    Optional<RealtyGroupMember> findByPublicId(UUID publicId);

    Optional<RealtyGroupMember> findByGroupIdAndUserId(Long groupId, Long userId);

    boolean existsByGroupIdAndUserId(Long groupId, Long userId);

    long countByGroupId(Long groupId);

    List<RealtyGroupMember> findByGroupIdOrderByJoinedAtAsc(Long groupId);

    List<RealtyGroupMember> findByUserIdOrderByJoinedAtDesc(Long userId);

    /** Deletion path used by leadership-transfer (when oldLeaderAction = LEAVE) and by
     *  remove/leave flows. */
    void deleteByGroupIdAndUserId(Long groupId, Long userId);

    /**
     * Returns the group IDs of every group the given user currently belongs to.
     * Used by the login/refresh-token dormancy-reset hook to clear dormancy state
     * on any group the user is a member of (spec §10.4 reset path 1).
     */
    @Query("SELECT m.groupId FROM RealtyGroupMember m WHERE m.userId = :userId")
    List<Long> findGroupIdsByUserId(@Param("userId") Long userId);

    /**
     * Returns just the commission rate without loading the full entity graph; used at
     * listing-create snapshot time.
     */
    @Query("""
        SELECT m.agentCommissionRate FROM RealtyGroupMember m
         WHERE m.groupId = :groupId AND m.userId = :userId
        """)
    Optional<BigDecimal> findCommissionRate(
            @Param("groupId") Long groupId,
            @Param("userId") Long userId);

    /**
     * Projection for {@link #findGroupsAvatarCanDepositTo}.
     *
     * <p>Native query returns the group's {@code publicId} + display {@code name}; the
     * controller wraps the page into {@code SlAvatarGroupsResponse.GroupRef} records.
     */
    interface GroupNameAndPublicIdProjection {
        UUID getPublicId();
        String getName();
    }

    /**
     * Lists every non-dissolved, non-suspended group the given user can deposit into:
     * either they are the leader, or they hold the {@code DEPOSIT_TO_GROUP_WALLET}
     * permission as an agent. Used by {@code POST /api/v1/sl/wallet/avatar-groups} so
     * the in-world terminal can render the avatar's eligible groups as dialog buttons.
     *
     * <p>Pagination: callers pass {@code after} = the last group name from the previous
     * page (alphabetical, lowercase-compared), {@code null} for the first page. Pass
     * {@code limit = PAGE_SIZE + 1} and detect {@code hasMore} from the extra row,
     * which avoids a second count query.
     *
     * <p>Footgun: {@code CAST(:after AS text)} is mandatory. Without the cast, passing
     * {@code null} for a {@code text} parameter is silently typed as {@code bytea} by
     * Postgres and breaks {@code lower(...)}. Documented in the admin-search fix on
     * the dev branch.
     */
    @Query(value = """
        SELECT g.public_id AS publicId, g.name AS name
        FROM realty_groups g
        LEFT JOIN realty_group_members m
            ON m.group_id = g.id AND m.user_id = :userId
        WHERE g.dissolved_at IS NULL
          AND NOT EXISTS (
              SELECT 1 FROM realty_group_suspensions s
              WHERE s.realty_group_id = g.id
                AND s.lifted_at IS NULL
                AND (s.expires_at IS NULL OR s.expires_at > now())
          )
          AND (g.leader_id = :userId OR 'DEPOSIT_TO_GROUP_WALLET' = ANY(m.permissions))
          AND (CAST(:after AS text) IS NULL OR lower(g.name) > lower(CAST(:after AS text)))
        ORDER BY lower(g.name) ASC
        LIMIT :limit
        """, nativeQuery = true)
    List<GroupNameAndPublicIdProjection> findGroupsAvatarCanDepositTo(
            @Param("userId") Long userId,
            @Param("after") String after,
            @Param("limit") int limit);
}
