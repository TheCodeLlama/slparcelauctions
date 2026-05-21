package com.slparcelauctions.backend.realty.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupActivityProjection;
import com.slparcelauctions.backend.realty.RealtyGroupInvitation;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRole;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.realty.rating.GroupRatingService;
import com.slparcelauctions.backend.realty.rating.dto.GroupRatingDto;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserAvatarUrl;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Builds wire-shape DTOs for the realty groups slice from persisted entities.
 *
 * <p>Centralises the small but repeated bits of work: display-name fallback, avatar URL
 * convention, role computation, and the privacy gate that hides
 * {@code permissions}/{@code joinedAt} from anonymous viewers + non-member, non-admin
 * authenticated viewers.
 *
 * <p>Avatar URLs follow the codebase-wide {@code /api/v1/users/{publicId}/avatar/256}
 * convention used by review and auction mappers. Logo and cover URLs point at the byte
 * endpoints introduced in Phase 10; they remain {@code null} until then if the underlying
 * object key has not been set.
 */
@Component
@RequiredArgsConstructor
public class RealtyGroupDtoMapper {

    private final UserRepository users;
    private final RealtyGroupMemberRepository members;
    private final RealtyGroupRepository groups;
    private final GroupRatingService ratingService;

    // ─────────────────────── Public group DTO ───────────────────────

    /**
     * Build the full public group payload, including the agents roster. {@code requesterUserId}
     * may be {@code null} (anonymous) — in that case permissions+joinedAt on each agent card
     * are hidden. When the caller is admin, permissions+joinedAt are always exposed regardless
     * of group membership.
     */
    public RealtyGroupPublicDto toPublicDto(RealtyGroup group, Long requesterUserId, boolean requesterIsAdmin) {
        List<RealtyGroupMember> rows = members.findByGroupIdOrderByJoinedAtAsc(group.getId());
        Map<Long, User> userById = hydrateUsers(rows);

        boolean requesterIsMemberOfThisGroup = requesterUserId != null
            && members.existsByGroupIdAndUserId(group.getId(), requesterUserId);
        boolean exposePrivate = requesterIsAdmin || requesterIsMemberOfThisGroup;

        LeaderCardDto leader = buildLeaderCard(group, userById);
        List<AgentCardDto> agentCards = new ArrayList<>(rows.size());
        for (RealtyGroupMember row : rows) {
            User u = userById.get(row.getUserId());
            agentCards.add(buildAgentCard(group, row, u, exposePrivate));
        }

        GroupRatingDto rating = ratingService.computeRating(group.getId());

        // Single round-trip for the public profile's activity strip + verified
        // SL-group badge. {@code findActivity} always returns one row (counts
        // collapse from zero rows to 0); we guard against a null defensively in
        // case the projection layer ever changes shape.
        RealtyGroupActivityProjection activity = groups.findActivity(group.getId());
        int activeListings = activity == null ? 0 : activity.getActiveListings();
        int completedSales = activity == null ? 0 : activity.getCompletedSales();
        boolean hasVerifiedSlGroup = activity != null && activity.getHasVerifiedSlGroup();

        return new RealtyGroupPublicDto(
            group.getPublicId(),
            group.getName(),
            group.getSlug(),
            group.getDescription(),
            group.getWebsite(),
            logoUrlFor(group),
            coverUrlFor(group),
            group.getCreatedAt(),
            leader,
            agentCards,
            group.getMemberSeatLimit() == null ? 0 : group.getMemberSeatLimit(),
            rows.size(),
            rating,
            activeListings,
            completedSales,
            hasVerifiedSlGroup);
    }

    /**
     * Build only the agents list — used by the standalone {@code GET .../members} endpoint
     * that does not include the rest of the group payload.
     */
    public List<AgentCardDto> toAgentCards(RealtyGroup group, Long requesterUserId, boolean requesterIsAdmin) {
        List<RealtyGroupMember> rows = members.findByGroupIdOrderByJoinedAtAsc(group.getId());
        Map<Long, User> userById = hydrateUsers(rows);
        boolean requesterIsMemberOfThisGroup = requesterUserId != null
            && members.existsByGroupIdAndUserId(group.getId(), requesterUserId);
        boolean exposePrivate = requesterIsAdmin || requesterIsMemberOfThisGroup;
        List<AgentCardDto> out = new ArrayList<>(rows.size());
        for (RealtyGroupMember row : rows) {
            User u = userById.get(row.getUserId());
            out.add(buildAgentCard(group, row, u, exposePrivate));
        }
        return out;
    }

    /**
     * Convenience for endpoints that return only the updated member card (e.g. the PATCH
     * permissions response). Always exposes permissions+joinedAt because the caller is the
     * leader by gate construction.
     */
    public AgentCardDto toAgentCard(RealtyGroup group, RealtyGroupMember member) {
        User u = users.findById(member.getUserId()).orElse(null);
        return buildAgentCard(group, member, u, true);
    }

    // ─────────────────────── Summary cards (my-groups / user profile) ───────────────────────

    /**
     * "My groups" / user-profile group-chip shape. {@code memberSince} mirrors the group's
     * creation date — the user-facing "since" badge is the group's age, not the caller's
     * own join date (a deliberate distinction so the group leader sees the same "since"
     * value an agent who joined yesterday would see).
     */
    public RealtyGroupSummaryDto toSummaryDto(RealtyGroup group) {
        long memberCount = members.countByGroupId(group.getId());
        return new RealtyGroupSummaryDto(
            group.getPublicId(),
            group.getName(),
            group.getSlug(),
            logoUrlFor(group),
            (int) memberCount,
            group.getCreatedAt());
    }

    public UserRealtyGroupAffiliationDto toAffiliationDto(RealtyGroup group, Long viewedUserId) {
        RealtyGroupRole role = Objects.equals(group.getLeaderId(), viewedUserId)
            ? RealtyGroupRole.LEADER
            : RealtyGroupRole.AGENT;
        return new UserRealtyGroupAffiliationDto(
            group.getPublicId(),
            group.getName(),
            group.getSlug(),
            logoUrlFor(group),
            role);
    }

    // ─────────────────────── Invitation DTOs ───────────────────────

    public InvitationDto toInvitationDto(RealtyGroupInvitation inv) {
        RealtyGroup group = groups.findById(inv.getGroupId()).orElse(null);
        User invitedBy = users.findById(inv.getInvitedById()).orElse(null);
        Set<RealtyGroupPermission> perms = inv.permissionSet();
        return new InvitationDto(
            inv.getPublicId(),
            group == null ? null : group.getPublicId(),
            group == null ? null : group.getName(),
            group == null ? null : group.getSlug(),
            invitedBy == null ? null : invitedBy.getPublicId(),
            invitedBy == null ? null : invitedBy.getDisplayName(),
            perms,
            inv.getStatus(),
            inv.getExpiresAt(),
            inv.getCreatedAt(),
            inv.getRespondedAt());
    }

    /**
     * Batch variant for endpoints returning a list — hydrates users + groups in two queries
     * rather than one per row.
     */
    public List<InvitationDto> toInvitationDtos(List<RealtyGroupInvitation> invitations) {
        if (invitations == null || invitations.isEmpty()) return Collections.emptyList();
        List<Long> groupIds = new ArrayList<>(invitations.size());
        List<Long> userIds = new ArrayList<>(invitations.size());
        for (RealtyGroupInvitation inv : invitations) {
            groupIds.add(inv.getGroupId());
            userIds.add(inv.getInvitedById());
        }
        Map<Long, RealtyGroup> groupById = new HashMap<>();
        for (RealtyGroup g : groups.findAllById(groupIds)) groupById.put(g.getId(), g);
        Map<Long, User> userById = new HashMap<>();
        for (User u : users.findAllById(userIds)) userById.put(u.getId(), u);

        List<InvitationDto> out = new ArrayList<>(invitations.size());
        for (RealtyGroupInvitation inv : invitations) {
            RealtyGroup g = groupById.get(inv.getGroupId());
            User invitedBy = userById.get(inv.getInvitedById());
            out.add(new InvitationDto(
                inv.getPublicId(),
                g == null ? null : g.getPublicId(),
                g == null ? null : g.getName(),
                g == null ? null : g.getSlug(),
                invitedBy == null ? null : invitedBy.getPublicId(),
                invitedBy == null ? null : invitedBy.getDisplayName(),
                inv.permissionSet(),
                inv.getStatus(),
                inv.getExpiresAt(),
                inv.getCreatedAt(),
                inv.getRespondedAt()));
        }
        return out;
    }

    // ─────────────────────── helpers ───────────────────────

    private LeaderCardDto buildLeaderCard(RealtyGroup group, Map<Long, User> userById) {
        User leader = userById.get(group.getLeaderId());
        if (leader == null) {
            // Fall back to a single lookup if the leader doesn't appear in the member rows
            // (defensive — leader-row invariant should keep them present).
            leader = users.findById(group.getLeaderId()).orElse(null);
        }
        if (leader == null) {
            return new LeaderCardDto(null, null, null);
        }
        return new LeaderCardDto(
            leader.getPublicId(),
            leader.getDisplayName(),
            avatarUrlFor(leader));
    }

    private AgentCardDto buildAgentCard(RealtyGroup group, RealtyGroupMember row,
                                         User user, boolean exposePrivate) {
        RealtyGroupRole role = Objects.equals(row.getUserId(), group.getLeaderId())
            ? RealtyGroupRole.LEADER
            : RealtyGroupRole.AGENT;
        Set<RealtyGroupPermission> perms = exposePrivate ? row.permissionSet() : null;
        return new AgentCardDto(
            row.getPublicId(),
            user == null ? null : user.getPublicId(),
            user == null ? null : user.getDisplayName(),
            user == null ? null : avatarUrlFor(user),
            role,
            perms,
            exposePrivate ? row.getJoinedAt() : null,
            exposePrivate ? row.getAgentCommissionRate() : null);
    }

    private Map<Long, User> hydrateUsers(List<RealtyGroupMember> rows) {
        if (rows.isEmpty()) return Collections.emptyMap();
        List<Long> ids = new ArrayList<>(rows.size());
        for (RealtyGroupMember r : rows) ids.add(r.getUserId());
        Map<Long, User> out = new HashMap<>();
        for (User u : users.findAllById(ids)) out.put(u.getId(), u);
        return out;
    }

    private static String avatarUrlFor(User u) {
        return u == null ? null : UserAvatarUrl.forUserOrNull(u.getPublicId());
    }

    // Plan Task 1: still keyed off the LIGHT slot only; URL stays variant-free.
    // Plan Task 2 swaps these to per-variant URLs once the dark surface ships.
    private static String logoUrlFor(RealtyGroup g) {
        if (g.getLogoLightObjectKey() == null) return null;
        return "/api/v1/realty-groups/" + g.getPublicId() + "/logo/image";
    }

    private static String coverUrlFor(RealtyGroup g) {
        if (g.getCoverLightObjectKey() == null) return null;
        return "/api/v1/realty-groups/" + g.getPublicId() + "/cover/image";
    }

    /** Resolve a {@link UUID} that may or may not be present — kept for upcoming admin DTO. */
    @SuppressWarnings("unused")
    private static UUID nullSafePublicId(User u) {
        return u == null ? null : u.getPublicId();
    }
}
