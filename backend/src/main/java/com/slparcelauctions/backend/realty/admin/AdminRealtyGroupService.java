package com.slparcelauctions.backend.realty.admin;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.dto.RealtyGroupPublicDto;
import com.slparcelauctions.backend.realty.dto.RealtyGroupRowDto;
import com.slparcelauctions.backend.realty.dto.UpdateRealtyGroupRequest;
import com.slparcelauctions.backend.realty.dto.RealtyGroupDtoMapper;
import com.slparcelauctions.backend.realty.exception.CannotRemoveLeaderException;
import com.slparcelauctions.backend.realty.exception.LeaderTransferTargetNotMemberException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.realty.service.RealtyGroupService;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Admin escape-hatch surface for the realty groups slice. Mirrors the
 * {@code AdminListingService} split — the user-facing service ({@link RealtyGroupService})
 * stays focused on the leader/agent flows, and this class encapsulates the bypassed-gate
 * actions an admin can take plus the audit-log writes that record them.
 *
 * <p>Bypassed gates:
 * <ul>
 *   <li>PATCH: the {@code EDIT_GROUP_PROFILE}/{@code CONFIGURE_FEES} permission gate and
 *       the 30-day rename cooldown. Delegates to
 *       {@link RealtyGroupService#updateGroupAsAdmin(UUID, UpdateRealtyGroupRequest, Long)}.</li>
 *   <li>DELETE: the leader gate on dissolve. Delegates to
 *       {@link RealtyGroupService#dissolveGroupAsAdmin(UUID, Long)}.</li>
 *   <li>DELETE member: the leader-protection ({@code CANNOT_REMOVE_LEADER}) when an
 *       explicit {@code newLeaderPublicId} replacement is supplied. Atomically swaps
 *       leadership, elevates the new leader, then deletes the (former) leader's row.</li>
 * </ul>
 *
 * <p>Every write goes through {@link AdminActionService#record} so the audit log captures
 * who did what to which group. Notifications follow the underlying delegate's behavior
 * (the user-facing service fires its own); the admin force-remove-leader path explicitly
 * fires {@link NotificationPublisher#realtyGroupMemberRemoved} for the removed user and
 * intentionally does NOT fire {@code realtyGroupLeadershipTransferred} — this is an admin
 * action and the audit log is the source of truth, not a leader-to-leader handoff.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class AdminRealtyGroupService {

    private final RealtyGroupRepository groups;
    private final RealtyGroupMemberRepository members;
    private final UserRepository users;
    private final RealtyGroupService groupService;
    private final RealtyGroupDtoMapper mapper;
    private final AdminActionService adminActionService;
    private final NotificationPublisher notifications;

    private static final Map<String, Object> SOURCE_METADATA =
        Map.of("source", "ADMIN_REALTY_GROUPS_TABLE");

    /**
     * Status filter for the admin list query. {@code ACTIVE} (default) hides soft-deleted
     * rows; {@code DISSOLVED} surfaces only soft-deleted ones; {@code ALL} returns both.
     */
    public enum StatusFilter {
        ACTIVE, DISSOLVED, ALL;

        public boolean includeActive() { return this == ACTIVE || this == ALL; }
        public boolean includeDissolved() { return this == DISSOLVED || this == ALL; }
    }

    /**
     * Paginated admin list with optional case-insensitive name substring search and a
     * three-way status filter. Hydrates leader display names + member counts in two
     * follow-up queries so the table renders without N+1 work.
     */
    @Transactional(readOnly = true)
    public Page<RealtyGroupRowDto> adminListGroups(StatusFilter status, String search, Pageable pageable) {
        String normalized = (search == null || search.isBlank()) ? null : search.trim();
        Page<RealtyGroup> page = groups.findForAdminWithSearch(
            status.includeActive(), status.includeDissolved(), normalized, pageable);

        List<RealtyGroup> rows = page.getContent();
        if (rows.isEmpty()) return page.map(this::projectRow);

        // Batch-fetch leader users for the page so the leader display-name column doesn't
        // hit N queries.
        List<Long> leaderIds = rows.stream().map(RealtyGroup::getLeaderId).distinct().toList();
        Map<Long, User> leadersById = new HashMap<>();
        for (User u : users.findAllById(leaderIds)) leadersById.put(u.getId(), u);

        return page.map(g -> {
            long memberCount = members.countByGroupId(g.getId());
            User leader = leadersById.get(g.getLeaderId());
            return new RealtyGroupRowDto(
                g.getPublicId(),
                g.getName(),
                g.getSlug(),
                leader == null ? null : leader.getPublicId(),
                leader == null ? null : leader.getDisplayName(),
                (int) memberCount,
                g.isDissolved(),
                g.getCreatedAt(),
                g.getDissolvedAt()
            );
        });
    }

    /**
     * Admin detail lookup. Unlike the public path this includes dissolved groups — admins
     * need to be able to drill into soft-deleted groups for audit reasons. {@link
     * RealtyGroupNotFoundException} (404) when the publicId is unknown.
     */
    @Transactional(readOnly = true)
    public RealtyGroupPublicDto adminGetGroup(UUID publicId) {
        RealtyGroup group = groups.findByPublicId(publicId)
            .orElseThrow(() -> new RealtyGroupNotFoundException(publicId));
        // Admins always see permissions+joinedAt on agents (privacy gate bypassed).
        return mapper.toPublicDto(group, null, /* requesterIsAdmin = */ true);
    }

    /**
     * Admin force-edit. Delegates to {@link RealtyGroupService#updateGroupAsAdmin} which
     * bypasses the permission gates and the rename cooldown. Records a {@code
     * REALTY_GROUP_EDIT} audit row regardless of which fields were touched (the audit log
     * captures the admin's intent; the diff is reconstructable from updatedAt).
     */
    public RealtyGroupPublicDto adminUpdateGroup(UUID publicId, UpdateRealtyGroupRequest req, Long adminUserId) {
        RealtyGroup updated = groupService.updateGroupAsAdmin(publicId, req, adminUserId);
        adminActionService.record(
            adminUserId,
            AdminActionType.REALTY_GROUP_EDIT,
            AdminActionTargetType.REALTY_GROUP,
            updated.getId(),
            null,
            SOURCE_METADATA);
        return mapper.toPublicDto(updated, null, /* requesterIsAdmin = */ true);
    }

    /**
     * Admin force-dissolve. Delegates to {@link RealtyGroupService#dissolveGroupAsAdmin}
     * which sets {@code dissolved_at = NOW()} and fires the
     * {@link NotificationPublisher#realtyGroupDissolved} notification to every former
     * member. Records a {@code REALTY_GROUP_DISSOLVE} audit row.
     */
    public void adminDissolveGroup(UUID publicId, Long adminUserId) {
        RealtyGroup dissolved = groupService.dissolveGroupAsAdmin(publicId, adminUserId);
        adminActionService.record(
            adminUserId,
            AdminActionType.REALTY_GROUP_DISSOLVE,
            AdminActionTargetType.REALTY_GROUP,
            dissolved.getId(),
            null,
            SOURCE_METADATA);
    }

    /**
     * Admin force-remove a member. Two paths:
     *
     * <ul>
     *   <li><b>Target is not the leader.</b> Plain remove: delete the member row, fire
     *       {@code realtyGroupMemberRemoved}.</li>
     *   <li><b>Target is the leader.</b> Requires an explicit
     *       {@code newLeaderPublicId} replacement (a current member of the same group).
     *       Atomic in one transaction: swap {@code leader_id} to the new leader, set the
     *       (now former) leader's permissions to the full flag set so the audit trail
     *       reflects the elevated post-removal state, then delete the former leader's
     *       member row, fire {@code realtyGroupMemberRemoved} for the removed user. If
     *       no replacement is provided, rejected with {@link CannotRemoveLeaderException}
     *       — admin must force-dissolve the group instead. If the replacement is not a
     *       current member, rejected with {@link LeaderTransferTargetNotMemberException}.</li>
     * </ul>
     *
     * <p>Records a single {@code REALTY_GROUP_MEMBER_REMOVE} audit row in either branch.
     * Intentionally does NOT fire {@code realtyGroupLeadershipTransferred} on the
     * force-remove-leader branch — this is an admin action, not a leader-driven handoff;
     * the audit log is the source of truth.
     */
    public void adminRemoveMember(UUID groupPublicId,
                                  UUID memberPublicId,
                                  Optional<UUID> newLeaderPublicIdOpt,
                                  Long adminUserId) {
        RealtyGroup group = groups.findByPublicId(groupPublicId)
            .orElseThrow(() -> new RealtyGroupNotFoundException(groupPublicId));

        RealtyGroupMember target = members.findByPublicId(memberPublicId)
            .orElseThrow(() -> new RealtyGroupNotFoundException(memberPublicId));
        if (!Objects.equals(target.getGroupId(), group.getId())) {
            // Cross-group; surface as not-found in this scope.
            throw new RealtyGroupNotFoundException(memberPublicId);
        }

        boolean targetIsLeader = Objects.equals(target.getUserId(), group.getLeaderId());

        if (targetIsLeader) {
            if (newLeaderPublicIdOpt.isEmpty()) {
                // Surface as CANNOT_REMOVE_LEADER (409) with a payload pointing the admin
                // at the correct escape hatch. The body of the exception carries the
                // suggestion; the controller's exception handler keeps the wire shape.
                throw new CannotRemoveLeaderException(
                    "Cannot remove the group leader without specifying a replacement via "
                    + "the newLeaderPublicId query parameter. To dissolve the group with "
                    + "no replacement, use DELETE /api/v1/admin/realty-groups/{publicId}.");
            }
            UUID newLeaderPublicId = newLeaderPublicIdOpt.get();
            RealtyGroupMember newLeaderRow = members.findByPublicId(newLeaderPublicId)
                .orElseThrow(LeaderTransferTargetNotMemberException::new);
            if (!Objects.equals(newLeaderRow.getGroupId(), group.getId())) {
                throw new LeaderTransferTargetNotMemberException();
            }
            if (Objects.equals(newLeaderRow.getUserId(), group.getLeaderId())) {
                // Self-as-replacement would leave the leader in place; treat as invalid.
                throw new LeaderTransferTargetNotMemberException();
            }

            Long oldLeaderUserId = target.getUserId();

            // Step 1: swap leader_id on the group.
            group.setLeaderId(newLeaderRow.getUserId());
            groups.save(group);

            // Step 2: set former leader's permissions to all flags (mirrors STAY semantics
            // on a normal leader transfer; the row is about to be deleted but we record
            // this for audit consistency before deletion).
            target.setPermissionSet(EnumSet.allOf(RealtyGroupPermission.class));
            members.save(target);

            // Step 3: delete the former leader's member row.
            members.deleteByGroupIdAndUserId(group.getId(), oldLeaderUserId);

            // Notify the removed user.
            Optional<User> removedUser = users.findById(oldLeaderUserId);
            removedUser.ifPresent(u -> notifications.realtyGroupMemberRemoved(group, u));
        } else {
            Long removedUserId = target.getUserId();
            members.deleteByGroupIdAndUserId(group.getId(), removedUserId);
            Optional<User> removedUser = users.findById(removedUserId);
            removedUser.ifPresent(u -> notifications.realtyGroupMemberRemoved(group, u));
        }

        adminActionService.record(
            adminUserId,
            AdminActionType.REALTY_GROUP_MEMBER_REMOVE,
            AdminActionTargetType.REALTY_GROUP,
            group.getId(),
            null,
            SOURCE_METADATA);

        log.info("Admin force-removed realty group member: groupPublicId={} memberPublicId={} adminUserId={} replacementLeader={}",
            groupPublicId, memberPublicId, adminUserId,
            newLeaderPublicIdOpt.orElse(null));
    }

    private RealtyGroupRowDto projectRow(RealtyGroup g) {
        // Fallback projection used when the page is empty after the batch hydration
        // path early-returns. Should rarely fire in practice.
        long memberCount = members.countByGroupId(g.getId());
        return new RealtyGroupRowDto(
            g.getPublicId(),
            g.getName(),
            g.getSlug(),
            null,
            null,
            (int) memberCount,
            g.isDissolved(),
            g.getCreatedAt(),
            g.getDissolvedAt());
    }
}
