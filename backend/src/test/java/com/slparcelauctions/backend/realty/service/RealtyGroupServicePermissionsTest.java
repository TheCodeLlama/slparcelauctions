package com.slparcelauctions.backend.realty.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.exception.GroupDissolvedException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupPermissionDeniedException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.realty.slug.RealtyGroupSlugFactory;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Unit tests for {@link RealtyGroupService#updateMemberPermissions}.
 *
 * <p>Leader-only — delegating this would let an INVITE_AGENTS delegate escalate themselves
 * by re-inviting + editing perms. Targeting the leader's own membership row is rejected
 * (leader perms are all-implicit and the column is unused for that row).
 */
@ExtendWith(MockitoExtension.class)
class RealtyGroupServicePermissionsTest {

    @Mock RealtyGroupRepository groups;
    @Mock RealtyGroupMemberRepository members;
    @Mock RealtyGroupSlugFactory slugFactory;
    @Mock RealtyGroupAuthorizer authorizer;
    @Mock NotificationPublisher notifications;
    @Mock UserRepository users;

    @InjectMocks RealtyGroupService service;

    private static RealtyGroup buildGroup(Long leaderId) {
        return RealtyGroup.builder()
            .name("G").slug("g").leaderId(leaderId)
            .agentFeeRate(new BigDecimal("0.0000"))
            .agentFeeSplit(new BigDecimal("0.5000"))
            .build();
    }

    private static RealtyGroupMember buildMember(Long groupId, Long userId) {
        return RealtyGroupMember.builder()
            .groupId(groupId).userId(userId).joinedAt(OffsetDateTime.now())
            .build();
    }

    @Test
    void leaderCanUpdateAgentPermissions() {
        UUID groupPid = UUID.randomUUID();
        UUID memberPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        RealtyGroupMember agent = buildMember(g.getId(), 200L);

        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));
        when(members.findByPublicId(memberPid)).thenReturn(Optional.of(agent));
        when(members.save(any(RealtyGroupMember.class))).thenAnswer(inv -> inv.getArgument(0));

        Set<RealtyGroupPermission> newPerms = EnumSet.of(
            RealtyGroupPermission.INVITE_AGENTS, RealtyGroupPermission.REMOVE_AGENTS);
        RealtyGroupMember result = service.updateMemberPermissions(
            groupPid, memberPid, newPerms, null, 100L);

        verify(authorizer).assertLeader(100L, g.getId());
        verify(notifications).realtyGroupPermissionsChanged(
            any(RealtyGroup.class), any(RealtyGroupMember.class), any(), any());
        assertEquals(newPerms, result.permissionSet());
    }

    @Test
    void noNotificationWhenPermissionSetUnchanged() {
        UUID groupPid = UUID.randomUUID();
        UUID memberPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        RealtyGroupMember agent = buildMember(g.getId(), 200L);
        agent.setPermissionSet(EnumSet.of(RealtyGroupPermission.INVITE_AGENTS));

        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));
        when(members.findByPublicId(memberPid)).thenReturn(Optional.of(agent));
        when(members.save(any(RealtyGroupMember.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateMemberPermissions(
            groupPid, memberPid, EnumSet.of(RealtyGroupPermission.INVITE_AGENTS), null, 100L);

        verify(notifications, never()).realtyGroupPermissionsChanged(
            any(), any(), any(), any());
    }

    @Test
    void leaderCanClearAllPermissions() {
        UUID groupPid = UUID.randomUUID();
        UUID memberPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        RealtyGroupMember agent = buildMember(g.getId(), 200L);
        agent.setPermissionSet(EnumSet.of(RealtyGroupPermission.INVITE_AGENTS));

        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));
        when(members.findByPublicId(memberPid)).thenReturn(Optional.of(agent));
        when(members.save(any(RealtyGroupMember.class))).thenAnswer(inv -> inv.getArgument(0));

        RealtyGroupMember result = service.updateMemberPermissions(
            groupPid, memberPid, EnumSet.noneOf(RealtyGroupPermission.class), null, 100L);

        assertTrue(result.permissionSet().isEmpty());
    }

    @Test
    void nonLeaderCannotUpdatePermissions() {
        UUID groupPid = UUID.randomUUID();
        UUID memberPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);

        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));
        doThrow(new RealtyGroupPermissionDeniedException("Leader-only action"))
            .when(authorizer).assertLeader(999L, g.getId());

        assertThrows(RealtyGroupPermissionDeniedException.class,
            () -> service.updateMemberPermissions(
                groupPid, memberPid, EnumSet.of(RealtyGroupPermission.INVITE_AGENTS), null, 999L));
        verify(members, never()).save(any());
    }

    @Test
    void cannotUpdateLeadersOwnPermissions() {
        UUID groupPid = UUID.randomUUID();
        UUID memberPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        // The "member" row being targeted is the leader's row (userId == leaderId).
        RealtyGroupMember leaderRow = buildMember(g.getId(), 100L);

        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));
        when(members.findByPublicId(memberPid)).thenReturn(Optional.of(leaderRow));

        RealtyGroupPermissionDeniedException ex = assertThrows(
            RealtyGroupPermissionDeniedException.class,
            () -> service.updateMemberPermissions(
                groupPid, memberPid,
                EnumSet.of(RealtyGroupPermission.INVITE_AGENTS), null, 100L));
        assertTrue(ex.getMessage().toLowerCase().contains("leader"),
            "rejection should mention leader-perm-immutability");
        verify(members, never()).save(any());
    }

    @Test
    void rejectsMemberFromAnotherGroup() {
        UUID groupPid = UUID.randomUUID();
        UUID memberPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        // Build a member with a groupId that does NOT match the loaded group's id.
        RealtyGroupMember strayMember = RealtyGroupMember.builder()
            .groupId(99999L)
            .userId(200L)
            .joinedAt(OffsetDateTime.now())
            .build();

        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));
        when(members.findByPublicId(memberPid)).thenReturn(Optional.of(strayMember));

        assertThrows(RealtyGroupNotFoundException.class,
            () -> service.updateMemberPermissions(
                groupPid, memberPid,
                EnumSet.of(RealtyGroupPermission.INVITE_AGENTS), null, 100L));
        verify(members, never()).save(any());
    }

    @Test
    void rejectsUnknownMember() {
        UUID groupPid = UUID.randomUUID();
        UUID memberPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);

        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));
        when(members.findByPublicId(memberPid)).thenReturn(Optional.empty());

        assertThrows(RealtyGroupNotFoundException.class,
            () -> service.updateMemberPermissions(
                groupPid, memberPid,
                EnumSet.of(RealtyGroupPermission.INVITE_AGENTS), null, 100L));
    }

    @Test
    void rejectsUnknownGroup() {
        UUID groupPid = UUID.randomUUID();
        UUID memberPid = UUID.randomUUID();
        when(groups.findByPublicId(groupPid)).thenReturn(Optional.empty());

        assertThrows(RealtyGroupNotFoundException.class,
            () -> service.updateMemberPermissions(
                groupPid, memberPid,
                EnumSet.of(RealtyGroupPermission.INVITE_AGENTS), null, 100L));
        verify(authorizer, never()).assertLeader(anyLong(), anyLong());
    }

    @Test
    void rejectsDissolvedGroup() {
        UUID groupPid = UUID.randomUUID();
        UUID memberPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        g.setDissolvedAt(OffsetDateTime.now());
        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));

        assertThrows(GroupDissolvedException.class,
            () -> service.updateMemberPermissions(
                groupPid, memberPid,
                EnumSet.of(RealtyGroupPermission.INVITE_AGENTS), null, 100L));
        verify(authorizer, never()).assertLeader(anyLong(), anyLong());
    }

    @Test
    void updatePermissions_updatesCommissionRate() {
        UUID groupPid = UUID.randomUUID();
        UUID memberPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        RealtyGroupMember agent = buildMember(g.getId(), 200L);
        agent.setAgentCommissionRate(BigDecimal.ZERO);

        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));
        when(members.findByPublicId(memberPid)).thenReturn(Optional.of(agent));
        when(members.save(any(RealtyGroupMember.class))).thenAnswer(inv -> inv.getArgument(0));

        BigDecimal newRate = new BigDecimal("0.1500");
        RealtyGroupMember result = service.updateMemberPermissions(
            groupPid, memberPid,
            EnumSet.noneOf(RealtyGroupPermission.class), newRate, 100L);

        assertEquals(0, newRate.compareTo(result.getAgentCommissionRate()),
            "rate on the saved row should match the requested rate");
    }

    @Test
    void updatePermissions_nullRate_leavesRateUnchanged() {
        UUID groupPid = UUID.randomUUID();
        UUID memberPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        RealtyGroupMember agent = buildMember(g.getId(), 200L);
        BigDecimal existingRate = new BigDecimal("0.0500");
        agent.setAgentCommissionRate(existingRate);

        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));
        when(members.findByPublicId(memberPid)).thenReturn(Optional.of(agent));
        when(members.save(any(RealtyGroupMember.class))).thenAnswer(inv -> inv.getArgument(0));

        RealtyGroupMember result = service.updateMemberPermissions(
            groupPid, memberPid,
            EnumSet.of(RealtyGroupPermission.INVITE_AGENTS), null, 100L);

        assertEquals(0, existingRate.compareTo(result.getAgentCommissionRate()),
            "null rate on the request should leave the existing rate untouched");
    }

    @Test
    void nullPermissionsTreatedAsEmpty() {
        UUID groupPid = UUID.randomUUID();
        UUID memberPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        RealtyGroupMember agent = buildMember(g.getId(), 200L);
        agent.setPermissionSet(EnumSet.of(RealtyGroupPermission.INVITE_AGENTS));

        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));
        when(members.findByPublicId(memberPid)).thenReturn(Optional.of(agent));
        when(members.save(any(RealtyGroupMember.class))).thenAnswer(inv -> inv.getArgument(0));

        RealtyGroupMember result = service.updateMemberPermissions(
            groupPid, memberPid, null, null, 100L);
        assertTrue(result.permissionSet().isEmpty());
    }
}
