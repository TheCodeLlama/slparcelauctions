package com.slparcelauctions.backend.realty.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.InvitationStatus;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupInvitation;
import com.slparcelauctions.backend.realty.RealtyGroupInvitationRepository;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.dto.CreateInvitationRequest;
import com.slparcelauctions.backend.realty.exception.AlreadyMemberException;
import com.slparcelauctions.backend.realty.exception.GroupDissolvedException;
import com.slparcelauctions.backend.realty.exception.InvitationAlreadyPendingException;
import com.slparcelauctions.backend.realty.exception.InvitationExpiredException;
import com.slparcelauctions.backend.realty.exception.InvitationNotFoundException;
import com.slparcelauctions.backend.realty.exception.MemberSeatLimitReachedException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupPermissionDeniedException;
import com.slparcelauctions.backend.realty.moderation.RealtyGroupGuard;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Invitation lifecycle service: invite / accept / decline / revoke. Split from {@link
 * RealtyGroupService} so each surface stays independently testable.
 *
 * <p>Each public method is its own {@code @Transactional} method. The accept path
 * re-validates inside the same tx (group-dissolved-race / already-member-race /
 * seat-limit-race) so concurrent accepts can't push a group over its seat limit.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class RealtyGroupInvitationService {

    /** Live invitation window per spec §3.3 — 7 days from creation. */
    static final Duration INVITATION_TTL = Duration.ofDays(7);

    private final RealtyGroupRepository groups;
    private final RealtyGroupMemberRepository members;
    private final RealtyGroupInvitationRepository invitations;
    private final RealtyGroupAuthorizer authorizer;
    private final NotificationPublisher notifications;
    private final UserRepository users;
    private final RealtyGroupGuard realtyGroupGuard;

    /**
     * Issue a new invitation. Caller needs {@link RealtyGroupPermission#INVITE_AGENTS} or
     * to be the leader (treated as implicit-all by the authorizer).
     *
     * <p>Validates, in order:
     * <ol>
     *   <li>Group is active (dissolved → 410 via {@link #loadActive}).</li>
     *   <li>Caller can INVITE_AGENTS (403 otherwise).</li>
     *   <li>Invitee resolves by username (404 otherwise).</li>
     *   <li>Invitee is not already a member (409 {@link AlreadyMemberException}).</li>
     *   <li>No existing PENDING invitation for the (group, invitee) pair (409
     *       {@link InvitationAlreadyPendingException}). The partial unique index enforces
     *       this at the DB layer too; this check produces the friendly response shape
     *       before the constraint fires.</li>
     *   <li>Seat limit not yet reached (409 {@link MemberSeatLimitReachedException}).</li>
     * </ol>
     *
     * <p>Permissions on the new invitation are copied verbatim from the request and will
     * be re-copied verbatim onto the {@link RealtyGroupMember} row at accept time.
     */
    public RealtyGroupInvitation invite(UUID groupPublicId, CreateInvitationRequest req,
                                         Long callerUserId) {
        RealtyGroup group = loadActive(groupPublicId);
        realtyGroupGuard.requireGroupCanOperate(group.getId());
        authorizer.assertCan(callerUserId, group.getId(), RealtyGroupPermission.INVITE_AGENTS);

        User invitee = users.findByUsername(req.invitedUsername())
            .orElseThrow(() -> new UserNotFoundException(
                "User not found by username: " + req.invitedUsername()));

        if (members.existsByGroupIdAndUserId(group.getId(), invitee.getId())) {
            throw new AlreadyMemberException();
        }
        invitations.findByGroupIdAndInvitedUserIdAndStatus(
                group.getId(), invitee.getId(), InvitationStatus.PENDING)
            .ifPresent(existing -> { throw new InvitationAlreadyPendingException(); });

        long currentMembers = members.countByGroupId(group.getId());
        if (currentMembers >= group.getMemberSeatLimit()) {
            throw new MemberSeatLimitReachedException(group.getMemberSeatLimit(), currentMembers);
        }

        Set<RealtyGroupPermission> perms = (req.permissions() == null)
            ? EnumSet.noneOf(RealtyGroupPermission.class)
            : req.permissions();

        BigDecimal commissionRate = req.agentCommissionRate() == null
            ? BigDecimal.ZERO
            : req.agentCommissionRate();

        RealtyGroupInvitation invitation = RealtyGroupInvitation.builder()
            .groupId(group.getId())
            .invitedUserId(invitee.getId())
            .invitedById(callerUserId)
            .status(InvitationStatus.PENDING)
            .expiresAt(OffsetDateTime.now().plus(INVITATION_TTL))
            .agentCommissionRate(commissionRate)
            .build();
        invitation.setPermissionSet(perms);

        RealtyGroupInvitation saved = invitations.save(invitation);
        notifications.realtyGroupInvitationSent(saved);
        log.info("Realty group invitation created: groupPublicId={} invitedUserId={} invitedById={} perms={}",
            groupPublicId, invitee.getId(), callerUserId, perms);
        return saved;
    }

    /**
     * Invitee accepts. Must address the caller (403 otherwise). Must be PENDING and not
     * past {@code expiresAt} (410 {@link InvitationExpiredException}). Re-validates the
     * group/membership/seat-limit invariants inside this tx so concurrent accepts can't
     * over-fill the group.
     *
     * <p>Permissions are copied verbatim from the invitation onto the new
     * {@link RealtyGroupMember} row. Invitation status flips to ACCEPTED with
     * {@code responded_at} set.
     */
    public RealtyGroupMember accept(UUID invitationPublicId, Long callerUserId) {
        RealtyGroupInvitation inv = invitations.findByPublicId(invitationPublicId)
            .orElseThrow(() -> new InvitationNotFoundException(invitationPublicId));
        if (!Objects.equals(inv.getInvitedUserId(), callerUserId)) {
            // Not the addressed invitee — surface as forbidden (don't leak the existence of
            // an unrelated invitation).
            throw new RealtyGroupPermissionDeniedException(
                "Invitation does not address the calling user");
        }
        if (inv.getStatus() != InvitationStatus.PENDING
            || (inv.getExpiresAt() != null && inv.getExpiresAt().isBefore(OffsetDateTime.now()))) {
            throw new InvitationExpiredException(invitationPublicId);
        }

        realtyGroupGuard.requireGroupCanOperate(inv.getGroupId());

        RealtyGroup group = groups.findById(inv.getGroupId())
            .orElseThrow(() -> new RealtyGroupNotFoundException((UUID) null));
        if (group.isDissolved()) {
            throw new GroupDissolvedException(group.getPublicId());
        }
        // Concurrent accept race: another invitation for the same user may have already
        // landed them in the group between findByPublicId and now.
        if (members.existsByGroupIdAndUserId(group.getId(), callerUserId)) {
            throw new AlreadyMemberException();
        }
        long currentMembers = members.countByGroupId(group.getId());
        if (currentMembers >= group.getMemberSeatLimit()) {
            throw new MemberSeatLimitReachedException(group.getMemberSeatLimit(), currentMembers);
        }

        BigDecimal copiedRate = inv.getAgentCommissionRate() == null
            ? BigDecimal.ZERO
            : inv.getAgentCommissionRate();
        RealtyGroupMember row = RealtyGroupMember.builder()
            .groupId(group.getId())
            .userId(callerUserId)
            .joinedAt(OffsetDateTime.now())
            .agentCommissionRate(copiedRate)
            .build();
        row.setPermissionSet(inv.permissionSet());
        RealtyGroupMember savedMember = members.save(row);

        inv.setStatus(InvitationStatus.ACCEPTED);
        inv.setRespondedAt(OffsetDateTime.now());
        invitations.save(inv);

        notifications.realtyGroupInvitationAccepted(inv);
        log.info("Realty group invitation accepted: invitationPublicId={} groupId={} userId={}",
            invitationPublicId, group.getId(), callerUserId);
        return savedMember;
    }

    /**
     * Invitee declines. Must address the caller. Must be PENDING (already-resolved
     * invitations are rejected with 410). Status flips to DECLINED; {@code responded_at}
     * is set. Notification fires to leader + INVITE_AGENTS delegates per spec §8.
     */
    public RealtyGroupInvitation decline(UUID invitationPublicId, Long callerUserId) {
        RealtyGroupInvitation inv = invitations.findByPublicId(invitationPublicId)
            .orElseThrow(() -> new InvitationNotFoundException(invitationPublicId));
        if (!Objects.equals(inv.getInvitedUserId(), callerUserId)) {
            throw new RealtyGroupPermissionDeniedException(
                "Invitation does not address the calling user");
        }
        if (inv.getStatus() != InvitationStatus.PENDING) {
            throw new InvitationExpiredException(invitationPublicId);
        }

        inv.setStatus(InvitationStatus.DECLINED);
        inv.setRespondedAt(OffsetDateTime.now());
        RealtyGroupInvitation saved = invitations.save(inv);
        notifications.realtyGroupInvitationDeclined(saved);
        log.info("Realty group invitation declined: invitationPublicId={} userId={}",
            invitationPublicId, callerUserId);
        return saved;
    }

    /**
     * Group-side revoke. INVITE_AGENTS-gated (or leader). Must be PENDING. Status flips
     * to REVOKED; {@code responded_at} is set.
     *
     * <p>Per spec §8: no notification fires — revoke is silent. The invitee simply sees
     * their pending invite disappear from their dashboard.
     */
    public RealtyGroupInvitation revoke(UUID groupPublicId, UUID invitationPublicId,
                                         Long callerUserId) {
        RealtyGroup group = loadActive(groupPublicId);
        authorizer.assertCan(callerUserId, group.getId(), RealtyGroupPermission.INVITE_AGENTS);

        RealtyGroupInvitation inv = invitations.findByPublicId(invitationPublicId)
            .orElseThrow(() -> new InvitationNotFoundException(invitationPublicId));
        if (!Objects.equals(inv.getGroupId(), group.getId())) {
            // Surface as not-found in this scope (cross-group revoke shouldn't leak data).
            throw new InvitationNotFoundException(invitationPublicId);
        }
        if (inv.getStatus() != InvitationStatus.PENDING) {
            throw new InvitationExpiredException(invitationPublicId);
        }

        inv.setStatus(InvitationStatus.REVOKED);
        inv.setRespondedAt(OffsetDateTime.now());
        RealtyGroupInvitation saved = invitations.save(inv);
        log.info("Realty group invitation revoked: invitationPublicId={} groupPublicId={} callerUserId={}",
            invitationPublicId, groupPublicId, callerUserId);
        return saved;
    }

    /**
     * Group-side list of every invitation, newest first. INVITE_AGENTS-gated (or leader).
     * Used by the {@code GET /api/v1/realty-groups/{publicId}/invitations} endpoint —
     * dissolved groups still reject via {@link #loadActive}.
     */
    @Transactional(readOnly = true)
    public java.util.List<RealtyGroupInvitation> listForGroup(UUID groupPublicId, Long callerUserId) {
        RealtyGroup group = loadActive(groupPublicId);
        authorizer.assertCan(callerUserId, group.getId(), RealtyGroupPermission.INVITE_AGENTS);
        return invitations.findByGroupIdOrderByCreatedAtDesc(group.getId());
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

    /** Defensive {@link Optional}-style getter for the user repo, kept here for symmetry. */
    @SuppressWarnings("unused")
    private Optional<User> tryFindUser(Long id) {
        return id == null ? Optional.empty() : users.findById(id);
    }
}
