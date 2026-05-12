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
}
