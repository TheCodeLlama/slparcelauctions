package com.slparcelauctions.backend.realty.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.InvitationStatus;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupInvitation;
import com.slparcelauctions.backend.realty.RealtyGroupInvitationRepository;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.exception.AlreadyMemberException;
import com.slparcelauctions.backend.realty.exception.GroupDissolvedException;
import com.slparcelauctions.backend.realty.exception.InvitationExpiredException;
import com.slparcelauctions.backend.realty.exception.InvitationNotFoundException;
import com.slparcelauctions.backend.realty.exception.MemberSeatLimitReachedException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupPermissionDeniedException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Unit tests for {@link RealtyGroupInvitationService#accept(UUID, Long)}.
 *
 * <p>Re-validates the group/membership/seat-limit invariants inside the accept transaction
 * so concurrent accepts can't over-fill the group.
 */
@ExtendWith(MockitoExtension.class)
class RealtyGroupInvitationServiceAcceptTest {

    @Mock RealtyGroupRepository groups;
    @Mock RealtyGroupMemberRepository members;
    @Mock RealtyGroupInvitationRepository invitations;
    @Mock RealtyGroupAuthorizer authorizer;
    @Mock NotificationPublisher notifications;
    @Mock UserRepository users;
    @Mock com.slparcelauctions.backend.realty.moderation.RealtyGroupGuard realtyGroupGuard;

    @InjectMocks RealtyGroupInvitationService service;

    private static RealtyGroup buildGroup(Long leaderId, int seatLimit) {
        return RealtyGroup.builder()
            .name("G").slug("g").leaderId(leaderId)
            .memberSeatLimit(seatLimit)
            .agentFeeRate(new BigDecimal("0.0000"))
            .agentFeeSplit(new BigDecimal("0.5000"))
            .build();
    }

    private static RealtyGroupInvitation buildPendingInvite(Long groupId, Long invitedUserId,
                                                              EnumSet<RealtyGroupPermission> perms) {
        RealtyGroupInvitation inv = RealtyGroupInvitation.builder()
            .groupId(groupId)
            .invitedUserId(invitedUserId)
            .invitedById(100L)
            .status(InvitationStatus.PENDING)
            .expiresAt(OffsetDateTime.now().plusDays(6))
            .build();
        inv.setPermissionSet(perms);
        return inv;
    }

    @Test
    void accept_groupSuspended_throwsAndDoesNotPersist() {
        UUID invPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L, 50);
        RealtyGroupInvitation inv = buildPendingInvite(
            g.getId(), 200L, EnumSet.of(RealtyGroupPermission.INVITE_AGENTS));
        when(invitations.findByPublicId(invPid)).thenReturn(Optional.of(inv));
        org.mockito.Mockito.doThrow(new com.slparcelauctions.backend.realty.moderation.exception
                    .RealtyGroupSuspendedException(
                    com.slparcelauctions.backend.realty.moderation.exception
                        .RealtyGroupSuspendedException.Status.SUSPENDED,
                    java.time.OffsetDateTime.now().plusDays(7), "TOS"))
            .when(realtyGroupGuard).requireGroupCanOperate(g.getId());

        assertThrows(
            com.slparcelauctions.backend.realty.moderation.exception
                .RealtyGroupSuspendedException.class,
            () -> service.accept(invPid, 200L));

        verify(members, never()).save(any(RealtyGroupMember.class));
        verify(notifications, never()).realtyGroupInvitationAccepted(any());
    }

    @Test
    void acceptCreatesMemberWithInvitationPermissionsAndFlipsStatus() {
        UUID invPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L, 50);
        EnumSet<RealtyGroupPermission> perms = EnumSet.of(
            RealtyGroupPermission.INVITE_AGENTS, RealtyGroupPermission.EDIT_GROUP_PROFILE);
        RealtyGroupInvitation inv = buildPendingInvite(g.getId(), 200L, perms);

        when(invitations.findByPublicId(invPid)).thenReturn(Optional.of(inv));
        when(groups.findById(g.getId())).thenReturn(Optional.of(g));
        when(members.existsByGroupIdAndUserId(g.getId(), 200L)).thenReturn(false);
        when(members.countByGroupId(g.getId())).thenReturn(5L);
        when(members.save(any(RealtyGroupMember.class))).thenAnswer(i -> i.getArgument(0));
        when(invitations.save(any(RealtyGroupInvitation.class))).thenAnswer(i -> i.getArgument(0));

        RealtyGroupMember saved = service.accept(invPid, 200L);

        // Invitation now ACCEPTED with respondedAt set.
        assertEquals(InvitationStatus.ACCEPTED, inv.getStatus());
        // Member created with permissions copied verbatim from the invitation.
        assertEquals(perms, saved.permissionSet());
        verify(notifications).realtyGroupInvitationAccepted(any(RealtyGroupInvitation.class));
    }

    @Test
    void rejectsForeignInvitee() {
        UUID invPid = UUID.randomUUID();
        RealtyGroupInvitation inv = buildPendingInvite(10L, 200L, EnumSet.noneOf(RealtyGroupPermission.class));
        when(invitations.findByPublicId(invPid)).thenReturn(Optional.of(inv));

        assertThrows(RealtyGroupPermissionDeniedException.class,
            () -> service.accept(invPid, /* not the invitee */ 999L));
        verify(members, never()).save(any());
    }

    @Test
    void rejectsAlreadyResolvedInvite() {
        UUID invPid = UUID.randomUUID();
        RealtyGroupInvitation inv = buildPendingInvite(10L, 200L, EnumSet.noneOf(RealtyGroupPermission.class));
        inv.setStatus(InvitationStatus.DECLINED);
        when(invitations.findByPublicId(invPid)).thenReturn(Optional.of(inv));

        assertThrows(InvitationExpiredException.class,
            () -> service.accept(invPid, 200L));
        verify(members, never()).save(any());
    }

    @Test
    void rejectsExpiredInvite() {
        UUID invPid = UUID.randomUUID();
        RealtyGroupInvitation inv = buildPendingInvite(10L, 200L, EnumSet.noneOf(RealtyGroupPermission.class));
        inv.setExpiresAt(OffsetDateTime.now().minusHours(1));
        when(invitations.findByPublicId(invPid)).thenReturn(Optional.of(inv));

        assertThrows(InvitationExpiredException.class,
            () -> service.accept(invPid, 200L));
    }

    @Test
    void rejectsUnknownInvite() {
        UUID invPid = UUID.randomUUID();
        when(invitations.findByPublicId(invPid)).thenReturn(Optional.empty());

        assertThrows(InvitationNotFoundException.class,
            () -> service.accept(invPid, 200L));
    }

    @Test
    void rejectsAcceptIntoDissolvedGroup() {
        UUID invPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L, 50);
        g.setDissolvedAt(OffsetDateTime.now());
        RealtyGroupInvitation inv = buildPendingInvite(g.getId(), 200L, EnumSet.noneOf(RealtyGroupPermission.class));
        when(invitations.findByPublicId(invPid)).thenReturn(Optional.of(inv));
        when(groups.findById(g.getId())).thenReturn(Optional.of(g));

        assertThrows(GroupDissolvedException.class,
            () -> service.accept(invPid, 200L));
        verify(members, never()).save(any());
    }

    @Test
    void rejectsAlreadyMemberRace() {
        UUID invPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L, 50);
        RealtyGroupInvitation inv = buildPendingInvite(g.getId(), 200L, EnumSet.noneOf(RealtyGroupPermission.class));
        when(invitations.findByPublicId(invPid)).thenReturn(Optional.of(inv));
        when(groups.findById(g.getId())).thenReturn(Optional.of(g));
        // The race: a concurrent accept landed this user as a member before this tx ran.
        when(members.existsByGroupIdAndUserId(g.getId(), 200L)).thenReturn(true);

        assertThrows(AlreadyMemberException.class,
            () -> service.accept(invPid, 200L));
        verify(members, never()).save(any());
    }

    @Test
    void rejectsSeatLimitRace() {
        UUID invPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L, 50);
        RealtyGroupInvitation inv = buildPendingInvite(g.getId(), 200L, EnumSet.noneOf(RealtyGroupPermission.class));
        when(invitations.findByPublicId(invPid)).thenReturn(Optional.of(inv));
        when(groups.findById(g.getId())).thenReturn(Optional.of(g));
        when(members.existsByGroupIdAndUserId(g.getId(), 200L)).thenReturn(false);
        // Race: another accept filled the last seat between invite time and accept time.
        when(members.countByGroupId(g.getId())).thenReturn(50L);

        assertThrows(MemberSeatLimitReachedException.class,
            () -> service.accept(invPid, 200L));
        verify(members, never()).save(any());
    }

    @Test
    void acceptInvitation_copiesRateOntoMember() {
        UUID invPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L, 50);
        RealtyGroupInvitation inv = buildPendingInvite(
            g.getId(), 200L, EnumSet.noneOf(RealtyGroupPermission.class));
        BigDecimal invitationRate = new BigDecimal("0.1250");
        inv.setAgentCommissionRate(invitationRate);

        when(invitations.findByPublicId(invPid)).thenReturn(Optional.of(inv));
        when(groups.findById(g.getId())).thenReturn(Optional.of(g));
        when(members.existsByGroupIdAndUserId(g.getId(), 200L)).thenReturn(false);
        when(members.countByGroupId(g.getId())).thenReturn(0L);
        when(members.save(any(RealtyGroupMember.class))).thenAnswer(i -> i.getArgument(0));
        when(invitations.save(any(RealtyGroupInvitation.class))).thenAnswer(i -> i.getArgument(0));

        RealtyGroupMember saved = service.accept(invPid, 200L);

        assertEquals(0, invitationRate.compareTo(saved.getAgentCommissionRate()),
            "member row should carry the invitation's commission rate verbatim");
    }

    @Test
    void respondedAtTimestampSetOnAccept() {
        UUID invPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L, 50);
        RealtyGroupInvitation inv = buildPendingInvite(g.getId(), 200L,
            EnumSet.of(RealtyGroupPermission.INVITE_AGENTS));
        when(invitations.findByPublicId(invPid)).thenReturn(Optional.of(inv));
        when(groups.findById(g.getId())).thenReturn(Optional.of(g));
        when(members.existsByGroupIdAndUserId(g.getId(), 200L)).thenReturn(false);
        when(members.countByGroupId(g.getId())).thenReturn(0L);
        when(members.save(any(RealtyGroupMember.class))).thenAnswer(i -> i.getArgument(0));
        when(invitations.save(any(RealtyGroupInvitation.class))).thenAnswer(i -> i.getArgument(0));

        service.accept(invPid, 200L);

        ArgumentCaptor<RealtyGroupInvitation> captor = ArgumentCaptor.forClass(RealtyGroupInvitation.class);
        verify(invitations).save(captor.capture());
        assertEquals(InvitationStatus.ACCEPTED, captor.getValue().getStatus());
        // respondedAt should be ~now
        assertEquals(true, captor.getValue().getRespondedAt() != null);
    }
}
