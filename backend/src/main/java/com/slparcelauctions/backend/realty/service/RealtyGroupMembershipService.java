package com.slparcelauctions.backend.realty.service;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.OldLeaderAction;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupInvitationRepository;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.dto.TransferLeadershipRequest;
import com.slparcelauctions.backend.realty.exception.CannotRemoveLeaderException;
import com.slparcelauctions.backend.realty.exception.GroupDissolvedException;
import com.slparcelauctions.backend.realty.exception.LeaderCannotLeaveException;
import com.slparcelauctions.backend.realty.exception.LeaderTransferTargetNotMemberException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Membership lifecycle service: leave / remove / transfer-leadership. Split from {@link
 * RealtyGroupService} so each surface stays independently testable.
 *
 * <p>Each mutation is its own {@code @Transactional} method. Notifications fire inside the
 * tx — Phase 6 will move them post-commit where appropriate.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class RealtyGroupMembershipService {

    private final RealtyGroupRepository groups;
    private final RealtyGroupMemberRepository members;
    @SuppressWarnings("unused") // wired for future cross-service revoke-pending-invites paths.
    private final RealtyGroupInvitationRepository invitations;
    private final RealtyGroupAuthorizer authorizer;
    private final NotificationPublisher notifications;
    private final UserRepository users;

    /**
     * Caller leaves the group. Deletes their own member row. Leader cannot leave — must
     * transfer or dissolve first ({@link LeaderCannotLeaveException} 409). Dissolved
     * group rejected (410). Non-member rejected (404).
     *
     * <p>Fires {@link NotificationPublisher#realtyGroupMemberLeft} to leader + INVITE_AGENTS
     * delegates (resolution happens inside the publisher; Phase 6 fleshes it out).
     */
    public void leave(UUID groupPublicId, Long callerUserId) {
        RealtyGroup group = loadActive(groupPublicId);
        if (Objects.equals(group.getLeaderId(), callerUserId)) {
            throw new LeaderCannotLeaveException();
        }
        RealtyGroupMember row = members.findByGroupIdAndUserId(group.getId(), callerUserId)
            .orElseThrow(() -> new RealtyGroupNotFoundException(groupPublicId));

        members.deleteByGroupIdAndUserId(group.getId(), callerUserId);
        // Resolve the User entity for the notification payload. Skip the fire if the row
        // somehow vanished mid-tx (defensive — same defensive posture as the dissolve path).
        Optional<User> leftUser = users.findById(callerUserId);
        leftUser.ifPresent(u -> notifications.realtyGroupMemberLeft(group, u));
        log.info("Realty group leave: groupPublicId={} userId={} memberId={}",
            groupPublicId, callerUserId, row.getId());
    }

    /**
     * Caller removes another member from the group. Caller must hold {@link
     * RealtyGroupPermission#REMOVE_AGENTS} or be the leader; the leader gate is implicit
     * because {@link RealtyGroupAuthorizer#canDo(Long, Long, RealtyGroupPermission)} treats
     * the leader as holding every permission.
     *
     * <p>Cannot target the leader: {@link CannotRemoveLeaderException} (409). 404 if the
     * member row exists but belongs to a different group.
     *
     * <p>Fires {@link NotificationPublisher#realtyGroupMemberRemoved} to the removed user.
     */
    public void removeMember(UUID groupPublicId, UUID memberPublicId, Long callerUserId) {
        RealtyGroup group = loadActive(groupPublicId);
        authorizer.assertCan(callerUserId, group.getId(), RealtyGroupPermission.REMOVE_AGENTS);

        RealtyGroupMember row = members.findByPublicId(memberPublicId)
            .orElseThrow(() -> new RealtyGroupNotFoundException(memberPublicId));
        if (!Objects.equals(row.getGroupId(), group.getId())) {
            // Cross-group; surface as not-found in this scope.
            throw new RealtyGroupNotFoundException(memberPublicId);
        }
        if (Objects.equals(row.getUserId(), group.getLeaderId())) {
            throw new CannotRemoveLeaderException();
        }

        members.deleteByGroupIdAndUserId(group.getId(), row.getUserId());
        Optional<User> removedUser = users.findById(row.getUserId());
        removedUser.ifPresent(u -> notifications.realtyGroupMemberRemoved(group, u));
        log.info("Realty group member removed: groupPublicId={} memberPublicId={} callerUserId={}",
            groupPublicId, memberPublicId, callerUserId);
    }

    /**
     * Transfer leadership. Leader-only. Body specifies {@code newLeaderPublicId} (UUID of
     * a current member) and {@code oldLeaderAction}:
     *
     * <ul>
     *   <li>{@code STAY}: old leader's permissions are set to the full flag set so they
     *       remain a fully-empowered delegate.</li>
     *   <li>{@code LEAVE}: old leader's member row is deleted.</li>
     * </ul>
     *
     * <p>Atomic — single transaction. If the target is not a current member of this group,
     * rejected with {@link LeaderTransferTargetNotMemberException} (400). The transfer
     * itself is a {@code leader_id} swap on the group row.
     *
     * <p>Fires {@link NotificationPublisher#realtyGroupLeadershipTransferred} to the old
     * leader, new leader, and every other current member.
     */
    public void transferLeadership(UUID groupPublicId, TransferLeadershipRequest req, Long callerUserId) {
        RealtyGroup group = loadActive(groupPublicId);
        authorizer.assertLeader(callerUserId, group.getId());

        // The target row must already exist as a member of this group. UUID lookup so the
        // public path keeps its UUID contract.
        RealtyGroupMember newLeaderRow = members.findByPublicId(req.newLeaderPublicId())
            .orElseThrow(LeaderTransferTargetNotMemberException::new);
        if (!Objects.equals(newLeaderRow.getGroupId(), group.getId())) {
            throw new LeaderTransferTargetNotMemberException();
        }

        Long oldLeaderId = group.getLeaderId();
        Long newLeaderId = newLeaderRow.getUserId();
        if (Objects.equals(oldLeaderId, newLeaderId)) {
            // Self-transfer is a no-op; reject as not-a-meaningful-target.
            throw new LeaderTransferTargetNotMemberException();
        }

        // Swap leader_id atomically; the old leader's row is then either elevated (STAY)
        // or deleted (LEAVE).
        group.setLeaderId(newLeaderId);
        groups.save(group);

        RealtyGroupMember oldLeaderRow = members.findByGroupIdAndUserId(group.getId(), oldLeaderId)
            .orElseThrow(() -> new RealtyGroupNotFoundException(groupPublicId));

        boolean oldStayed;
        if (req.oldLeaderAction() == OldLeaderAction.STAY) {
            oldLeaderRow.setPermissionSet(EnumSet.allOf(RealtyGroupPermission.class));
            members.save(oldLeaderRow);
            oldStayed = true;
        } else {
            members.deleteByGroupIdAndUserId(group.getId(), oldLeaderId);
            oldStayed = false;
        }

        Optional<User> oldLeader = users.findById(oldLeaderId);
        Optional<User> newLeader = users.findById(newLeaderId);
        if (oldLeader.isPresent() && newLeader.isPresent()) {
            notifications.realtyGroupLeadershipTransferred(
                group, oldLeader.get(), newLeader.get(), oldStayed);
        }
        log.info("Realty group leadership transferred: groupPublicId={} oldLeaderId={} newLeaderId={} oldStayed={}",
            groupPublicId, oldLeaderId, newLeaderId, oldStayed);
    }

    // ─────────────────────── helpers ───────────────────────

    private RealtyGroup loadActive(UUID groupPublicId) {
        RealtyGroup group = groups.findByPublicId(groupPublicId)
            .orElseThrow(() -> new RealtyGroupNotFoundException(groupPublicId));
        if (group.isDissolved()) {
            throw new GroupDissolvedException(group.getPublicId());
        }
        return group;
    }
}
