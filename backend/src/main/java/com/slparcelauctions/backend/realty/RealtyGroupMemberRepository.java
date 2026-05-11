package com.slparcelauctions.backend.realty;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
