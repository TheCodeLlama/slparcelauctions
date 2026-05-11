package com.slparcelauctions.backend.realty.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
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
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.dto.CreateInvitationRequest;
import com.slparcelauctions.backend.realty.exception.AlreadyMemberException;
import com.slparcelauctions.backend.realty.exception.GroupDissolvedException;
import com.slparcelauctions.backend.realty.exception.InvitationAlreadyPendingException;
import com.slparcelauctions.backend.realty.exception.MemberSeatLimitReachedException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupPermissionDeniedException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Unit tests for {@link RealtyGroupInvitationService#invite(UUID, CreateInvitationRequest, Long)}.
 *
 * <p>Caller must hold {@link RealtyGroupPermission#INVITE_AGENTS} (or be the leader, which
 * the authorizer treats as implicit-all). Validates: invitee exists, isn't already a member,
 * has no live PENDING invite, seat limit not yet hit. On success, persists a PENDING
 * invitation with {@code expires_at = NOW + 7 days} and fires
 * {@link NotificationPublisher#realtyGroupInvitationSent}.
 */
@ExtendWith(MockitoExtension.class)
class RealtyGroupInvitationServiceInviteTest {

    @Mock RealtyGroupRepository groups;
    @Mock RealtyGroupMemberRepository members;
    @Mock RealtyGroupInvitationRepository invitations;
    @Mock RealtyGroupAuthorizer authorizer;
    @Mock NotificationPublisher notifications;
    @Mock UserRepository users;

    @InjectMocks RealtyGroupInvitationService service;

    private static RealtyGroup buildGroup(Long leaderId, int seatLimit) {
        RealtyGroup g = RealtyGroup.builder()
            .name("G").slug("g").leaderId(leaderId)
            .memberSeatLimit(seatLimit)
            .agentFeeRate(new BigDecimal("0.0000"))
            .agentFeeSplit(new BigDecimal("0.5000"))
            .build();
        return g;
    }

    private static User buildUser(String username) {
        User u = new User();
        u.setUsername(username);
        return u;
    }

    @Test
    void leaderCanInviteUser() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L, 50);
        User invitee = buildUser("agent");
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        when(users.findByUsername("agent")).thenReturn(Optional.of(invitee));
        when(members.existsByGroupIdAndUserId(any(), any())).thenReturn(false);
        when(invitations.findByGroupIdAndInvitedUserIdAndStatus(
                any(), any(), any())).thenReturn(Optional.empty());
        when(members.countByGroupId(any())).thenReturn(5L);
        when(invitations.save(any(RealtyGroupInvitation.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        Set<RealtyGroupPermission> perms = EnumSet.of(RealtyGroupPermission.INVITE_AGENTS);
        CreateInvitationRequest req = new CreateInvitationRequest("agent", perms);

        RealtyGroupInvitation result = service.invite(pid, req, 100L);

        verify(authorizer).assertCan(100L, g.getId(), RealtyGroupPermission.INVITE_AGENTS);

        ArgumentCaptor<RealtyGroupInvitation> captor = ArgumentCaptor.forClass(RealtyGroupInvitation.class);
        verify(invitations).save(captor.capture());
        RealtyGroupInvitation saved = captor.getValue();
        assertEquals(InvitationStatus.PENDING, saved.getStatus());
        assertEquals(perms, saved.permissionSet());
        assertEquals(100L, saved.getInvitedById());
        assertNotNull(saved.getExpiresAt());
        // ExpiresAt is roughly 7 days from now.
        assertTrue(saved.getExpiresAt().isAfter(OffsetDateTime.now().plusDays(6)));
        assertTrue(saved.getExpiresAt().isBefore(OffsetDateTime.now().plusDays(8)));

        verify(notifications).realtyGroupInvitationSent(any(RealtyGroupInvitation.class));
        assertNotNull(result);
    }

    @Test
    void callerWithoutInviteAgentsRejected() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L, 50);
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        doThrow(new RealtyGroupPermissionDeniedException(RealtyGroupPermission.INVITE_AGENTS))
            .when(authorizer).assertCan(150L, g.getId(), RealtyGroupPermission.INVITE_AGENTS);

        assertThrows(RealtyGroupPermissionDeniedException.class,
            () -> service.invite(pid, new CreateInvitationRequest("agent", EnumSet.noneOf(RealtyGroupPermission.class)), 150L));
        verify(invitations, never()).save(any());
    }

    @Test
    void rejectsAlreadyMember() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L, 50);
        User invitee = buildUser("agent");
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        when(users.findByUsername("agent")).thenReturn(Optional.of(invitee));
        when(members.existsByGroupIdAndUserId(any(), any())).thenReturn(true);

        assertThrows(AlreadyMemberException.class,
            () -> service.invite(pid, new CreateInvitationRequest("agent", EnumSet.noneOf(RealtyGroupPermission.class)), 100L));
        verify(invitations, never()).save(any());
        verify(notifications, never()).realtyGroupInvitationSent(any());
    }

    @Test
    void rejectsAlreadyPendingInvitation() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L, 50);
        User invitee = buildUser("agent");
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        when(users.findByUsername("agent")).thenReturn(Optional.of(invitee));
        when(members.existsByGroupIdAndUserId(any(), any())).thenReturn(false);
        RealtyGroupInvitation existing = RealtyGroupInvitation.builder()
            .groupId(g.getId()).invitedUserId(invitee.getId()).invitedById(100L)
            .status(InvitationStatus.PENDING)
            .expiresAt(OffsetDateTime.now().plusDays(7))
            .build();
        when(invitations.findByGroupIdAndInvitedUserIdAndStatus(
                any(), any(), any())).thenReturn(Optional.of(existing));

        assertThrows(InvitationAlreadyPendingException.class,
            () -> service.invite(pid, new CreateInvitationRequest("agent", EnumSet.noneOf(RealtyGroupPermission.class)), 100L));
        verify(invitations, never()).save(any());
    }

    @Test
    void rejectsWhenSeatLimitReached() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L, 50);
        User invitee = buildUser("agent");
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        when(users.findByUsername("agent")).thenReturn(Optional.of(invitee));
        when(members.existsByGroupIdAndUserId(any(), any())).thenReturn(false);
        when(invitations.findByGroupIdAndInvitedUserIdAndStatus(
                any(), any(), any())).thenReturn(Optional.empty());
        when(members.countByGroupId(any())).thenReturn(50L);

        assertThrows(MemberSeatLimitReachedException.class,
            () -> service.invite(pid, new CreateInvitationRequest("agent", EnumSet.noneOf(RealtyGroupPermission.class)), 100L));
        verify(invitations, never()).save(any());
    }

    @Test
    void rejectsUnknownUsername() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L, 50);
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class,
            () -> service.invite(pid, new CreateInvitationRequest("ghost", EnumSet.noneOf(RealtyGroupPermission.class)), 100L));
        verify(invitations, never()).save(any());
    }

    @Test
    void rejectsDissolvedGroup() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L, 50);
        g.setDissolvedAt(OffsetDateTime.now());
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));

        assertThrows(GroupDissolvedException.class,
            () -> service.invite(pid, new CreateInvitationRequest("agent", EnumSet.noneOf(RealtyGroupPermission.class)), 100L));
        verify(authorizer, never()).assertCan(anyLong(), anyLong(), any());
    }
}
