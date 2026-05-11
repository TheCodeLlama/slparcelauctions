package com.slparcelauctions.backend.realty.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.slparcelauctions.backend.realty.OldLeaderAction;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupInvitationRepository;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.dto.TransferLeadershipRequest;
import com.slparcelauctions.backend.realty.exception.LeaderTransferTargetNotMemberException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupPermissionDeniedException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Unit tests for {@link RealtyGroupMembershipService#transferLeadership}.
 *
 * <p>Leader-only. STAY: old leader's row keeps all four permission flags. LEAVE: old
 * leader's row is deleted. Target must already be a member of this group.
 */
@ExtendWith(MockitoExtension.class)
class RealtyGroupMembershipServiceTransferTest {

    @Mock RealtyGroupRepository groups;
    @Mock RealtyGroupMemberRepository members;
    @Mock RealtyGroupInvitationRepository invitations;
    @Mock RealtyGroupAuthorizer authorizer;
    @Mock NotificationPublisher notifications;
    @Mock UserRepository users;

    @InjectMocks RealtyGroupMembershipService service;

    private static RealtyGroup buildGroup(Long leaderId) {
        return RealtyGroup.builder()
            .name("G").slug("g").leaderId(leaderId)
            .agentFeeRate(new BigDecimal("0.0000"))
            .agentFeeSplit(new BigDecimal("0.5000"))
            .build();
    }

    private static RealtyGroupMember buildMember(Long groupId, Long userId) {
        return RealtyGroupMember.builder()
            .groupId(groupId).userId(userId).joinedAt(OffsetDateTime.now()).build();
    }

    @Test
    void stayElevatesOldLeaderToFullPermissions() {
        UUID groupPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        RealtyGroupMember newLeaderMember = buildMember(g.getId(), 200L);
        UUID newLeaderPid = newLeaderMember.getPublicId();
        RealtyGroupMember oldLeaderRow = buildMember(g.getId(), 100L);

        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));
        when(members.findByPublicId(newLeaderPid)).thenReturn(Optional.of(newLeaderMember));
        when(members.findByGroupIdAndUserId(g.getId(), 100L)).thenReturn(Optional.of(oldLeaderRow));
        when(groups.save(any(RealtyGroup.class))).thenAnswer(inv -> inv.getArgument(0));
        when(members.save(any(RealtyGroupMember.class))).thenAnswer(inv -> inv.getArgument(0));
        User oldU = new User(); oldU.setUsername("old");
        User newU = new User(); newU.setUsername("new");
        when(users.findById(100L)).thenReturn(Optional.of(oldU));
        when(users.findById(200L)).thenReturn(Optional.of(newU));

        service.transferLeadership(
            groupPid,
            new TransferLeadershipRequest(newLeaderPid, OldLeaderAction.STAY),
            100L);

        verify(authorizer).assertLeader(100L, g.getId());

        // The group row was updated to point at the new leader.
        assertEquals(200L, g.getLeaderId());

        // The old leader's member row now holds all four flags. Capture the save call to
        // verify the persisted shape rather than rely on the in-memory mutation order.
        ArgumentCaptor<RealtyGroupMember> captor = ArgumentCaptor.forClass(RealtyGroupMember.class);
        verify(members).save(captor.capture());
        assertEquals(EnumSet.allOf(RealtyGroupPermission.class),
            captor.getValue().permissionSet());

        // No delete on the old leader's row in STAY mode.
        verify(members, never()).deleteByGroupIdAndUserId(eq(g.getId()), eq(100L));

        verify(notifications).realtyGroupLeadershipTransferred(
            any(RealtyGroup.class), eq(oldU), eq(newU), eq(true));
    }

    @Test
    void leaveDeletesOldLeaderRow() {
        UUID groupPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        RealtyGroupMember newLeaderMember = buildMember(g.getId(), 200L);
        UUID newLeaderPid = newLeaderMember.getPublicId();
        RealtyGroupMember oldLeaderRow = buildMember(g.getId(), 100L);

        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));
        when(members.findByPublicId(newLeaderPid)).thenReturn(Optional.of(newLeaderMember));
        when(members.findByGroupIdAndUserId(g.getId(), 100L)).thenReturn(Optional.of(oldLeaderRow));
        when(groups.save(any(RealtyGroup.class))).thenAnswer(inv -> inv.getArgument(0));
        User oldU = new User(); oldU.setUsername("old");
        User newU = new User(); newU.setUsername("new");
        when(users.findById(100L)).thenReturn(Optional.of(oldU));
        when(users.findById(200L)).thenReturn(Optional.of(newU));

        service.transferLeadership(
            groupPid,
            new TransferLeadershipRequest(newLeaderPid, OldLeaderAction.LEAVE),
            100L);

        assertEquals(200L, g.getLeaderId());
        verify(members).deleteByGroupIdAndUserId(g.getId(), 100L);
        // No row save for the old leader in LEAVE mode (no perms to elevate).
        verify(members, never()).save(any(RealtyGroupMember.class));
        verify(notifications).realtyGroupLeadershipTransferred(
            any(RealtyGroup.class), eq(oldU), eq(newU), eq(false));
    }

    @Test
    void rejectsTargetWhoIsNotAMember() {
        UUID groupPid = UUID.randomUUID();
        UUID newLeaderPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));
        when(members.findByPublicId(newLeaderPid)).thenReturn(Optional.empty());

        assertThrows(LeaderTransferTargetNotMemberException.class,
            () -> service.transferLeadership(
                groupPid, new TransferLeadershipRequest(newLeaderPid, OldLeaderAction.STAY), 100L));
        verify(groups, never()).save(any());
    }

    @Test
    void rejectsCrossGroupTarget() {
        UUID groupPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        RealtyGroupMember strayMember = RealtyGroupMember.builder()
            .groupId(99999L).userId(200L).joinedAt(OffsetDateTime.now()).build();
        UUID newLeaderPid = strayMember.getPublicId();
        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));
        when(members.findByPublicId(newLeaderPid)).thenReturn(Optional.of(strayMember));

        assertThrows(LeaderTransferTargetNotMemberException.class,
            () -> service.transferLeadership(
                groupPid, new TransferLeadershipRequest(newLeaderPid, OldLeaderAction.STAY), 100L));
        verify(groups, never()).save(any());
    }

    @Test
    void rejectsNonLeaderCaller() {
        UUID groupPid = UUID.randomUUID();
        UUID newLeaderPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));
        doThrow(new RealtyGroupPermissionDeniedException("Leader-only action"))
            .when(authorizer).assertLeader(999L, g.getId());

        assertThrows(RealtyGroupPermissionDeniedException.class,
            () -> service.transferLeadership(
                groupPid, new TransferLeadershipRequest(newLeaderPid, OldLeaderAction.STAY), 999L));
        verify(groups, never()).save(any());
        verify(members, never()).save(any());
    }

    @Test
    void rejectsSelfTransfer() {
        UUID groupPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        // The "new leader" row IS the current leader — disallow.
        RealtyGroupMember selfRow = buildMember(g.getId(), 100L);
        UUID newLeaderPid = selfRow.getPublicId();
        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));
        when(members.findByPublicId(newLeaderPid)).thenReturn(Optional.of(selfRow));

        assertThrows(LeaderTransferTargetNotMemberException.class,
            () -> service.transferLeadership(
                groupPid, new TransferLeadershipRequest(newLeaderPid, OldLeaderAction.STAY), 100L));
        verify(groups, never()).save(any());
    }
}
