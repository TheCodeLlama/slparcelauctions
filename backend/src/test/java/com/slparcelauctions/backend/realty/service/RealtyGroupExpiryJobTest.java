package com.slparcelauctions.backend.realty.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;

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

/**
 * Unit tests for {@link RealtyGroupExpiryJob}.
 *
 * <p>Repository is mocked; the job's responsibility is to flip status + set
 * {@code respondedAt} + publish a notification per overdue row. The repository's
 * "find overdue" query is exercised in the integration suite.
 */
@ExtendWith(MockitoExtension.class)
class RealtyGroupExpiryJobTest {

    @Mock RealtyGroupInvitationRepository invitations;
    @Mock NotificationPublisher notifications;

    @InjectMocks RealtyGroupExpiryJob job;

    private static RealtyGroupInvitation overdue(long id) {
        RealtyGroupInvitation inv = RealtyGroupInvitation.builder()
            .groupId(10L)
            .invitedUserId(200L + id)
            .invitedById(100L)
            .status(InvitationStatus.PENDING)
            .expiresAt(OffsetDateTime.now().minusHours(1))
            .build();
        return inv;
    }

    @Test
    void expiresOverduePendingInvitations() {
        RealtyGroupInvitation inv = overdue(1);
        when(invitations.findOverdueInvitations()).thenReturn(List.of(inv));

        job.expirePendingInvitations();

        assertEquals(InvitationStatus.EXPIRED, inv.getStatus());
        assertNotNull(inv.getRespondedAt());
        verify(invitations).save(inv);
        verify(notifications).realtyGroupInvitationExpired(inv);
    }

    @Test
    void doesNothingWhenNoOverdueInvitations() {
        when(invitations.findOverdueInvitations()).thenReturn(List.of());

        job.expirePendingInvitations();

        verify(invitations, never()).save(any());
        verify(notifications, never()).realtyGroupInvitationExpired(any());
    }

    @Test
    void doesNotTouchFreshInvitations() {
        // Fresh invite (PENDING, expires_at in the future) is not returned by findOverdueInvitations,
        // so the job must not touch it. Verifies the job leans on the repo query (not its own filter).
        RealtyGroupInvitation fresh = RealtyGroupInvitation.builder()
            .groupId(10L).invitedUserId(201L).invitedById(100L)
            .status(InvitationStatus.PENDING)
            .expiresAt(OffsetDateTime.now().plusDays(6))
            .build();
        when(invitations.findOverdueInvitations()).thenReturn(List.of());  // repo filters it out

        job.expirePendingInvitations();

        assertEquals(InvitationStatus.PENDING, fresh.getStatus());
        verify(invitations, never()).save(any());
        verify(notifications, never()).realtyGroupInvitationExpired(any());
    }

    @Test
    void firesNotificationOncePerOverdueInvitation() {
        RealtyGroupInvitation a = overdue(1);
        RealtyGroupInvitation b = overdue(2);
        RealtyGroupInvitation c = overdue(3);
        when(invitations.findOverdueInvitations()).thenReturn(List.of(a, b, c));

        job.expirePendingInvitations();

        verify(notifications, times(3)).realtyGroupInvitationExpired(any(RealtyGroupInvitation.class));
        ArgumentCaptor<RealtyGroupInvitation> captor = ArgumentCaptor.forClass(RealtyGroupInvitation.class);
        verify(invitations, times(3)).save(captor.capture());
        for (RealtyGroupInvitation saved : captor.getAllValues()) {
            assertEquals(InvitationStatus.EXPIRED, saved.getStatus());
            assertNotNull(saved.getRespondedAt());
        }
    }
}
