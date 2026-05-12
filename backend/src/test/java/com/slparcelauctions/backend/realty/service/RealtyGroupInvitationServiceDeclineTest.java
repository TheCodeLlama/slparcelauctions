package com.slparcelauctions.backend.realty.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.slparcelauctions.backend.realty.RealtyGroupInvitation;
import com.slparcelauctions.backend.realty.RealtyGroupInvitationRepository;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.exception.InvitationExpiredException;
import com.slparcelauctions.backend.realty.exception.InvitationNotFoundException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupPermissionDeniedException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Unit tests for {@link RealtyGroupInvitationService#decline(UUID, Long)}.
 */
@ExtendWith(MockitoExtension.class)
class RealtyGroupInvitationServiceDeclineTest {

    @Mock RealtyGroupRepository groups;
    @Mock RealtyGroupMemberRepository members;
    @Mock RealtyGroupInvitationRepository invitations;
    @Mock RealtyGroupAuthorizer authorizer;
    @Mock NotificationPublisher notifications;
    @Mock UserRepository users;
    @Mock com.slparcelauctions.backend.realty.moderation.RealtyGroupGuard realtyGroupGuard;

    @InjectMocks RealtyGroupInvitationService service;

    private static RealtyGroupInvitation buildPendingInvite(Long groupId, Long invitedUserId) {
        RealtyGroupInvitation inv = RealtyGroupInvitation.builder()
            .groupId(groupId)
            .invitedUserId(invitedUserId)
            .invitedById(100L)
            .status(InvitationStatus.PENDING)
            .expiresAt(OffsetDateTime.now().plusDays(6))
            .build();
        inv.setPermissionSet(EnumSet.noneOf(RealtyGroupPermission.class));
        return inv;
    }

    @Test
    void declineFlipsStatusAndFiresNotification() {
        UUID invPid = UUID.randomUUID();
        RealtyGroupInvitation inv = buildPendingInvite(10L, 200L);
        when(invitations.findByPublicId(invPid)).thenReturn(Optional.of(inv));
        when(invitations.save(any(RealtyGroupInvitation.class))).thenAnswer(i -> i.getArgument(0));

        RealtyGroupInvitation saved = service.decline(invPid, 200L);

        ArgumentCaptor<RealtyGroupInvitation> captor = ArgumentCaptor.forClass(RealtyGroupInvitation.class);
        verify(invitations).save(captor.capture());
        assertEquals(InvitationStatus.DECLINED, captor.getValue().getStatus());
        assertNotNull(captor.getValue().getRespondedAt());
        verify(notifications).realtyGroupInvitationDeclined(any(RealtyGroupInvitation.class));
        assertNotNull(saved);
    }

    @Test
    void rejectsForeignInvitee() {
        UUID invPid = UUID.randomUUID();
        RealtyGroupInvitation inv = buildPendingInvite(10L, 200L);
        when(invitations.findByPublicId(invPid)).thenReturn(Optional.of(inv));

        assertThrows(RealtyGroupPermissionDeniedException.class,
            () -> service.decline(invPid, 999L));
        verify(invitations, never()).save(any());
        verify(notifications, never()).realtyGroupInvitationDeclined(any());
    }

    @Test
    void rejectsAlreadyResolved() {
        UUID invPid = UUID.randomUUID();
        RealtyGroupInvitation inv = buildPendingInvite(10L, 200L);
        inv.setStatus(InvitationStatus.ACCEPTED);
        when(invitations.findByPublicId(invPid)).thenReturn(Optional.of(inv));

        assertThrows(InvitationExpiredException.class,
            () -> service.decline(invPid, 200L));
    }

    @Test
    void rejectsUnknownInvitation() {
        UUID invPid = UUID.randomUUID();
        when(invitations.findByPublicId(invPid)).thenReturn(Optional.empty());

        assertThrows(InvitationNotFoundException.class,
            () -> service.decline(invPid, 200L));
    }
}
