package com.slparcelauctions.backend.realty.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
import com.slparcelauctions.backend.realty.exception.GroupDissolvedException;
import com.slparcelauctions.backend.realty.exception.LeaderCannotLeaveException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Unit tests for {@link RealtyGroupMembershipService#leave(UUID, Long)}.
 *
 * <p>Caller deletes their own membership row. Leader cannot leave (must transfer or
 * dissolve). Dissolved group rejected.
 */
@ExtendWith(MockitoExtension.class)
class RealtyGroupMembershipServiceLeaveTest {

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

    @Test
    void agentCanLeaveDeletesMembershipAndFiresNotification() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        RealtyGroupMember agent = RealtyGroupMember.builder()
            .groupId(g.getId()).userId(200L).joinedAt(OffsetDateTime.now()).build();
        when(members.findByGroupIdAndUserId(g.getId(), 200L)).thenReturn(Optional.of(agent));
        User u = new User();
        u.setUsername("agent");
        when(users.findById(200L)).thenReturn(Optional.of(u));

        service.leave(pid, 200L);

        verify(members).deleteByGroupIdAndUserId(g.getId(), 200L);
        verify(notifications).realtyGroupMemberLeft(any(RealtyGroup.class), any(User.class));
    }

    @Test
    void leaderCannotLeave() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));

        assertThrows(LeaderCannotLeaveException.class, () -> service.leave(pid, 100L));
        verify(members, never()).deleteByGroupIdAndUserId(eq(g.getId()), any());
        verify(notifications, never()).realtyGroupMemberLeft(any(), any());
    }

    @Test
    void rejectsDissolvedGroup() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        g.setDissolvedAt(OffsetDateTime.now());
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));

        assertThrows(GroupDissolvedException.class, () -> service.leave(pid, 200L));
        verify(members, never()).deleteByGroupIdAndUserId(any(), any());
    }

    @Test
    void rejectsUnknownGroup() {
        UUID pid = UUID.randomUUID();
        when(groups.findByPublicId(pid)).thenReturn(Optional.empty());

        assertThrows(RealtyGroupNotFoundException.class, () -> service.leave(pid, 200L));
    }

    @Test
    void rejectsNonMember() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        when(members.findByGroupIdAndUserId(g.getId(), 999L)).thenReturn(Optional.empty());

        assertThrows(RealtyGroupNotFoundException.class, () -> service.leave(pid, 999L));
        verify(members, never()).deleteByGroupIdAndUserId(any(), any());
    }

    @Test
    void leave_reassigns_listing_agent_to_leader_on_active_auctions() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        RealtyGroupMember agent = RealtyGroupMember.builder()
            .groupId(g.getId()).userId(200L).joinedAt(OffsetDateTime.now()).build();
        when(members.findByGroupIdAndUserId(g.getId(), 200L)).thenReturn(Optional.of(agent));
        User u = new User();
        u.setUsername("agent");
        when(users.findById(200L)).thenReturn(Optional.of(u));

        service.leave(pid, 200L);

        // E §10: both reassignment queries fire side-by-side. The repo's WHERE-clause
        // filters (realty_group_sl_group_id IS NOT NULL vs IS NULL) decide which rows
        // each query actually touches at the DB layer; the service just calls both.
        verify(auctions).reassignSellerToLeaderForCase3(200L, g.getId(), g.getLeaderId());
        verify(auctions).reassignListingAgentToLeaderForCase1(200L, g.getId(), g.getLeaderId());
    }

    @Test
    void leave_case3Listings_seller_idReassignedToLeader_listingAgent_idStable() {
        // Service-layer contract: the case-3 query is invoked with the departing user as
        // the old user and the leader as the new seller. The repo's
        // realty_group_sl_group_id IS NOT NULL predicate restricts the update to case-3
        // rows — listing_agent_id is untouched by this query, so commission attribution
        // for the departing member is preserved.
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        RealtyGroupMember agent = RealtyGroupMember.builder()
            .groupId(g.getId()).userId(200L).joinedAt(OffsetDateTime.now()).build();
        when(members.findByGroupIdAndUserId(g.getId(), 200L)).thenReturn(Optional.of(agent));
        User u = new User();
        u.setUsername("agent");
        when(users.findById(200L)).thenReturn(Optional.of(u));

        service.leave(pid, 200L);

        verify(auctions).reassignSellerToLeaderForCase3(200L, g.getId(), g.getLeaderId());
    }

    @Test
    void leave_case1LegacyListings_listingAgent_idReassignedToLeader_seller_idUnchanged() {
        // Service-layer contract: the case-1 query is invoked with the departing user as
        // the old listing agent and the leader as the new listing agent. The repo's
        // realty_group_sl_group_id IS NULL predicate restricts the update to case-1
        // legacy rows — seller_id is untouched by this query.
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        RealtyGroupMember agent = RealtyGroupMember.builder()
            .groupId(g.getId()).userId(200L).joinedAt(OffsetDateTime.now()).build();
        when(members.findByGroupIdAndUserId(g.getId(), 200L)).thenReturn(Optional.of(agent));
        User u = new User();
        u.setUsername("agent");
        when(users.findById(200L)).thenReturn(Optional.of(u));

        service.leave(pid, 200L);

        verify(auctions).reassignListingAgentToLeaderForCase1(200L, g.getId(), g.getLeaderId());
    }

    @Test
    void leave_individualListings_unaffected() {
        // Service-layer contract: both queries are scoped by realty_group_id = :groupId.
        // Auctions with realty_group_id IS NULL fall outside both predicates, so they're
        // untouched regardless of what the service does. The service can't know which
        // individual rows exist — the predicate enforces it. We verify the only writes
        // the service issues are the two scoped reassignments (no broad UPDATE).
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup(100L);
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        RealtyGroupMember agent = RealtyGroupMember.builder()
            .groupId(g.getId()).userId(200L).joinedAt(OffsetDateTime.now()).build();
        when(members.findByGroupIdAndUserId(g.getId(), 200L)).thenReturn(Optional.of(agent));
        User u = new User();
        u.setUsername("agent");
        when(users.findById(200L)).thenReturn(Optional.of(u));

        service.leave(pid, 200L);

        // Both reassignment queries pass groupId — neither can touch realty_group_id IS NULL
        // rows. No other AuctionRepository writes fire from the leave path.
        verify(auctions).reassignSellerToLeaderForCase3(200L, g.getId(), g.getLeaderId());
        verify(auctions).reassignListingAgentToLeaderForCase1(200L, g.getId(), g.getLeaderId());
        verifyNoMoreInteractions(auctions);
    }
}
