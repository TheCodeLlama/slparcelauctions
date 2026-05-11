package com.slparcelauctions.backend.realty.auth;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.exception.GroupDissolvedException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupPermissionDeniedException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;

import lombok.RequiredArgsConstructor;

/**
 * Per-(user, group, permission) authorization checks for the realty groups slice.
 *
 * <p>Resolution rule (spec §4.2):
 * <ol>
 *   <li>Load the group by id; if absent → {@link RealtyGroupNotFoundException}.</li>
 *   <li>If the group is dissolved → {@link GroupDissolvedException}. Check happens before
 *       membership lookup so even ex-members cannot mutate a dissolved group.</li>
 *   <li>If {@code userId == group.leaderId} → {@code true} (leader holds every permission
 *       implicitly).</li>
 *   <li>Load the member row; if absent → {@code false}.</li>
 *   <li>Otherwise → membership's permission set contains {@code p}.</li>
 * </ol>
 *
 * <p>Plain DB read at each call; no caching. Permission revocation takes effect on the
 * next request.
 */
@Service
@RequiredArgsConstructor
public class RealtyGroupAuthorizer {

    private final RealtyGroupRepository groups;
    private final RealtyGroupMemberRepository members;

    public boolean canDo(Long userId, Long groupId, RealtyGroupPermission p) {
        RealtyGroup group = loadActive(groupId);
        if (group.getLeaderId().equals(userId)) return true;
        Optional<RealtyGroupMember> row = members.findByGroupIdAndUserId(groupId, userId);
        return row.map(m -> m.hasPermission(p)).orElse(false);
    }

    public void assertCan(Long userId, Long groupId, RealtyGroupPermission p) {
        if (!canDo(userId, groupId, p)) {
            throw new RealtyGroupPermissionDeniedException(p);
        }
    }

    public boolean isMember(Long userId, Long groupId) {
        loadActive(groupId);
        return members.existsByGroupIdAndUserId(groupId, userId);
    }

    public boolean isLeader(Long userId, Long groupId) {
        RealtyGroup group = loadActive(groupId);
        return group.getLeaderId().equals(userId);
    }

    public void assertLeader(Long userId, Long groupId) {
        if (!isLeader(userId, groupId)) {
            throw new RealtyGroupPermissionDeniedException("Leader-only action");
        }
    }

    private RealtyGroup loadActive(Long groupId) {
        RealtyGroup group = groups.findById(groupId)
            .orElseThrow(() -> new RealtyGroupNotFoundException((java.util.UUID) null));
        if (group.isDissolved()) {
            throw new GroupDissolvedException(group.getPublicId());
        }
        return group;
    }
}
