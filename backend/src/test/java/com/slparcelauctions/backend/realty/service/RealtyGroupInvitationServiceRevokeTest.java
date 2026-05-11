package com.slparcelauctions.backend.realty.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import com.slparcelauctions.backend.realty.exception.GroupDissolvedException;
import com.slparcelauctions.backend.realty.exception.InvitationExpiredException;
import com.slparcelauctions.backend.realty.exception.InvitationNotFoundException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupPermissionDeniedException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Unit tests for {@link RealtyGroupInvitationService#revoke(UUID, UUID, Long)}.
 *
 * <p>INVITE_AGENTS-gated (or leader). PENDING-only. <b>No notification fires</b> per spec
 * §8 — revoke is silent; the invitee sees the pending invite disappear.
 */
@ExtendWith(MockitoExtension.class)
class RealtyGroupInvitationServiceRevokeTest {

    @Mock RealtyGroupRepository groups;
    @Mock RealtyGroupMemberRepository members;
    @Mock RealtyGroupInvitationRepository invitations;
    @Mock RealtyGroupAuthorizer authorizer;
    @Mock NotificationPublisher notifications;
    @Mock UserRepository users;

    @InjectMocks RealtyGroupInvitationService service;

    private static RealtyGroup buildGroup(Long leaderId) {
        return RealtyGroup.builder()
            .name("G").slug("g").leaderId(leaderId)
            .agentFeeRate(new BigDecimal("0.0000"))
            .agentFeeSplit(new BigDecimal("0.5000"))
            .build();
    }

    private static RealtyGroupInvitation buildPendingInvite(Long groupId) {
        RealtyGroupInvitation inv = RealtyGroupInvitation.builder()
            .groupId(groupId)
            .invitedUserId(200L)
            .invitedById(100L)
            .status(InvitationStatus.PENDING)
            .expiresAt(OffsetDateTime.now().plusDays(6))
            .build();
        inv.setPermissionSet(EnumSet.noneOf(RealtyGroupPermission.class));
        return inv;
    }

    @Test
    void revokeFlipsStatusAndDoesNotNotify() {
        UUID groupPid = UUID.randomUUID();
        UUID invPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        RealtyGroupInvitation inv = buildPendingInvite(g.getId());
        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));
        when(invitations.findByPublicId(invPid)).thenReturn(Optional.of(inv));
        when(invitations.save(any(RealtyGroupInvitation.class))).thenAnswer(i -> i.getArgument(0));

        RealtyGroupInvitation saved = service.revoke(groupPid, invPid, 100L);

        verify(authorizer).assertCan(100L, g.getId(), RealtyGroupPermission.INVITE_AGENTS);

        ArgumentCaptor<RealtyGroupInvitation> captor = ArgumentCaptor.forClass(RealtyGroupInvitation.class);
        verify(invitations).save(captor.capture());
        assertEquals(InvitationStatus.REVOKED, captor.getValue().getStatus());
        assertNotNull(captor.getValue().getRespondedAt());

        // Revoke is silent per spec §8 — no notification fan-out.
        verify(notifications, never()).realtyGroupInvitationDeclined(any());
        verify(notifications, never()).realtyGroupInvitationAccepted(any());
        verify(notifications, never()).realtyGroupInvitationExpired(any());
        verify(notifications, never()).realtyGroupInvitationSent(any());

        assertNotNull(saved);
    }

    @Test
    void callerWithoutInviteAgentsRejected() {
        UUID groupPid = UUID.randomUUID();
        UUID invPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));
        doThrow(new RealtyGroupPermissionDeniedException(RealtyGroupPermission.INVITE_AGENTS))
            .when(authorizer).assertCan(150L, g.getId(), RealtyGroupPermission.INVITE_AGENTS);

        assertThrows(RealtyGroupPermissionDeniedException.class,
            () -> service.revoke(groupPid, invPid, 150L));
        verify(invitations, never()).save(any());
    }

    @Test
    void rejectsCrossGroupInvitation() {
        UUID groupPid = UUID.randomUUID();
        UUID invPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        // Invitation belongs to a different group id.
        RealtyGroupInvitation inv = buildPendingInvite(99999L);
        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));
        when(invitations.findByPublicId(invPid)).thenReturn(Optional.of(inv));

        assertThrows(InvitationNotFoundException.class,
            () -> service.revoke(groupPid, invPid, 100L));
        verify(invitations, never()).save(any());
    }

    @Test
    void rejectsAlreadyResolvedInvitation() {
        UUID groupPid = UUID.randomUUID();
        UUID invPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        RealtyGroupInvitation inv = buildPendingInvite(g.getId());
        inv.setStatus(InvitationStatus.ACCEPTED);
        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));
        when(invitations.findByPublicId(invPid)).thenReturn(Optional.of(inv));

        assertThrows(InvitationExpiredException.class,
            () -> service.revoke(groupPid, invPid, 100L));
        verify(invitations, never()).save(any());
    }

    @Test
    void rejectsUnknownInvitation() {
        UUID groupPid = UUID.randomUUID();
        UUID invPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));
        when(invitations.findByPublicId(invPid)).thenReturn(Optional.empty());

        assertThrows(InvitationNotFoundException.class,
            () -> service.revoke(groupPid, invPid, 100L));
    }

    @Test
    void rejectsDissolvedGroup() {
        UUID groupPid = UUID.randomUUID();
        UUID invPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        g.setDissolvedAt(OffsetDateTime.now());
        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));

        assertThrows(GroupDissolvedException.class,
            () -> service.revoke(groupPid, invPid, 100L));
        verify(authorizer, never()).assertCan(anyLong(), anyLong(), any());
    }
}
