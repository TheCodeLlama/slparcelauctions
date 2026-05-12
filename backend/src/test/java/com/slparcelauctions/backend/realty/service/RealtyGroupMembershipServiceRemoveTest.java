package com.slparcelauctions.backend.realty.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupInvitationRepository;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.exception.CannotRemoveLeaderException;
import com.slparcelauctions.backend.realty.exception.GroupDissolvedException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupPermissionDeniedException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Unit tests for {@link RealtyGroupMembershipService#removeMember(UUID, UUID, Long)}.
 *
 * <p>Caller needs {@link RealtyGroupPermission#REMOVE_AGENTS} or to be the leader; cannot
 * target the leader (409 {@link CannotRemoveLeaderException}); cross-group target rejected
 * with 404.
 */
@ExtendWith(MockitoExtension.class)
class RealtyGroupMembershipServiceRemoveTest {

    @Mock RealtyGroupRepository groups;
    @Mock RealtyGroupMemberRepository members;
    @Mock RealtyGroupInvitationRepository invitations;
    @Mock RealtyGroupAuthorizer authorizer;
    @Mock NotificationPublisher notifications;
    @Mock UserRepository users;
    @Mock AuctionRepository auctions;
    @Mock com.slparcelauctions.backend.realty.moderation.RealtyGroupGuard realtyGroupGuard;

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
    void removeMember_groupSuspended_throwsAndDoesNotMutate() {
        UUID groupPid = UUID.randomUUID();
        UUID memberPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));
        doThrow(new com.slparcelauctions.backend.realty.moderation.exception
                    .RealtyGroupSuspendedException(
                    com.slparcelauctions.backend.realty.moderation.exception
                        .RealtyGroupSuspendedException.Status.SUSPENDED,
                    OffsetDateTime.now().plusDays(7), "TOS"))
            .when(realtyGroupGuard).requireGroupCanOperate(g.getId());

        assertThrows(
            com.slparcelauctions.backend.realty.moderation.exception
                .RealtyGroupSuspendedException.class,
            () -> service.removeMember(groupPid, memberPid, 150L));

        verify(authorizer, never()).assertCan(any(), any(), any());
        verify(members, never()).deleteByGroupIdAndUserId(any(), any());
        verify(notifications, never()).realtyGroupMemberRemoved(any(), any());
    }

    @Test
    void removerWithPermissionDeletesAgentAndFiresNotification() {
        UUID groupPid = UUID.randomUUID();
        UUID memberPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        RealtyGroupMember agent = buildMember(g.getId(), 200L);
        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));
        when(members.findByPublicId(memberPid)).thenReturn(Optional.of(agent));
        User u = new User();
        u.setUsername("agent");
        when(users.findById(200L)).thenReturn(Optional.of(u));

        service.removeMember(groupPid, memberPid, 150L);

        verify(authorizer).assertCan(150L, g.getId(), RealtyGroupPermission.REMOVE_AGENTS);
        verify(members).deleteByGroupIdAndUserId(g.getId(), 200L);
        verify(notifications).realtyGroupMemberRemoved(any(RealtyGroup.class), any(User.class));
    }

    @Test
    void callerWithoutPermissionRejected() {
        UUID groupPid = UUID.randomUUID();
        UUID memberPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));
        doThrow(new RealtyGroupPermissionDeniedException(RealtyGroupPermission.REMOVE_AGENTS))
            .when(authorizer).assertCan(150L, g.getId(), RealtyGroupPermission.REMOVE_AGENTS);

        assertThrows(RealtyGroupPermissionDeniedException.class,
            () -> service.removeMember(groupPid, memberPid, 150L));
        verify(members, never()).deleteByGroupIdAndUserId(any(), any());
    }

    @Test
    void cannotRemoveTheLeader() {
        UUID groupPid = UUID.randomUUID();
        UUID memberPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        // Leader's own member row — userId equals leaderId.
        RealtyGroupMember leaderRow = buildMember(g.getId(), 100L);
        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));
        when(members.findByPublicId(memberPid)).thenReturn(Optional.of(leaderRow));

        assertThrows(CannotRemoveLeaderException.class,
            () -> service.removeMember(groupPid, memberPid, 100L));
        verify(members, never()).deleteByGroupIdAndUserId(any(), any());
    }

    @Test
    void rejectsCrossGroupMember() {
        UUID groupPid = UUID.randomUUID();
        UUID memberPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        RealtyGroupMember strayMember = RealtyGroupMember.builder()
            .groupId(99999L).userId(200L).joinedAt(OffsetDateTime.now()).build();
        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));
        when(members.findByPublicId(memberPid)).thenReturn(Optional.of(strayMember));

        assertThrows(RealtyGroupNotFoundException.class,
            () -> service.removeMember(groupPid, memberPid, 150L));
        verify(members, never()).deleteByGroupIdAndUserId(any(), any());
    }

    @Test
    void rejectsUnknownMember() {
        UUID groupPid = UUID.randomUUID();
        UUID memberPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));
        when(members.findByPublicId(memberPid)).thenReturn(Optional.empty());

        assertThrows(RealtyGroupNotFoundException.class,
            () -> service.removeMember(groupPid, memberPid, 150L));
    }

    @Test
    void rejectsDissolvedGroup() {
        UUID groupPid = UUID.randomUUID();
        UUID memberPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        g.setDissolvedAt(OffsetDateTime.now());
        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));

        assertThrows(GroupDissolvedException.class,
            () -> service.removeMember(groupPid, memberPid, 150L));
    }

    @Test
    void removeMember_reassigns_seller_to_leader_on_active_auctions() {
        UUID groupPid = UUID.randomUUID();
        UUID memberPid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        RealtyGroupMember agent = buildMember(g.getId(), 200L);
        when(groups.findByPublicId(groupPid)).thenReturn(Optional.of(g));
        when(members.findByPublicId(memberPid)).thenReturn(Optional.of(agent));
        User u = new User();
        u.setUsername("agent");
        when(users.findById(200L)).thenReturn(Optional.of(u));

        service.removeMember(groupPid, memberPid, 100L);

        // E §10: case-3 reassignment flips seller_id on pre-terminal listings.
        // listing_agent_id is preserved so commission attribution stays with the
        // departing agent.
        verify(auctions).reassignSellerToLeaderForCase3(200L, g.getId(), g.getLeaderId());
    }
}
