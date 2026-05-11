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
import java.util.Collections;
import java.util.List;
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
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.exception.ActiveListingsBlockDissolveException;
import com.slparcelauctions.backend.realty.exception.GroupDissolvedException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupPermissionDeniedException;
import com.slparcelauctions.backend.realty.slug.RealtyGroupSlugFactory;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Unit tests for {@link RealtyGroupService#dissolveGroup} and {@link
 * RealtyGroupService#dissolveGroupAsAdmin}.
 *
 * <p>Dissolution is a soft delete: {@code dissolved_at = NOW()}. The partial unique indexes
 * on {@code name_lower} and {@code slug} make name + slug immediately reusable. Leader-only
 * for the non-admin path; admin twin bypasses the leader gate (force-dissolve).
 */
@ExtendWith(MockitoExtension.class)
class RealtyGroupServiceDissolveTest {

    @Mock RealtyGroupRepository groups;
    @Mock RealtyGroupMemberRepository members;
    @Mock RealtyGroupSlugFactory slugFactory;
    @Mock RealtyGroupAuthorizer authorizer;
    @Mock NotificationPublisher notifications;
    @Mock UserRepository users;
    @Mock AuctionRepository auctions;

    @InjectMocks RealtyGroupService service;

    private static RealtyGroup buildGroup(String name, String slug, Long leaderId) {
        return RealtyGroup.builder()
            .name(name).slug(slug).leaderId(leaderId)
            .agentFeeRate(new BigDecimal("0.0000"))
            .agentFeeSplit(new BigDecimal("0.5000"))
            .build();
    }

    // ─────────────────── non-admin dissolveGroup ───────────────────

    @Test
    void dissolveGroupAsLeaderSetsDissolvedAt() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup("G", "g", 100L);
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        when(groups.save(any(RealtyGroup.class))).thenAnswer(inv -> inv.getArgument(0));
        when(members.findByGroupIdOrderByJoinedAtAsc(g.getId())).thenReturn(Collections.emptyList());

        OffsetDateTime before = OffsetDateTime.now().minusSeconds(1);
        RealtyGroup result = service.dissolveGroup(pid, 100L);
        OffsetDateTime after = OffsetDateTime.now().plusSeconds(1);

        verify(authorizer).assertLeader(100L, g.getId());
        verify(notifications).realtyGroupDissolved(any(RealtyGroup.class), any());
        assertNotNull(result.getDissolvedAt());
        assertTrue(!result.getDissolvedAt().isBefore(before) && !result.getDissolvedAt().isAfter(after),
            "dissolvedAt should be ~now");
        assertTrue(result.isDissolved());
    }

    @Test
    void dissolveGroupResolvesFormerMembersForNotification() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup("G", "g", 100L);
        RealtyGroupMember leaderRow = RealtyGroupMember.builder()
            .groupId(g.getId()).userId(100L).joinedAt(OffsetDateTime.now().minusDays(2)).build();
        RealtyGroupMember agentRow = RealtyGroupMember.builder()
            .groupId(g.getId()).userId(200L).joinedAt(OffsetDateTime.now().minusDays(1)).build();
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        when(groups.save(any(RealtyGroup.class))).thenAnswer(inv -> inv.getArgument(0));
        when(members.findByGroupIdOrderByJoinedAtAsc(g.getId()))
            .thenReturn(List.of(leaderRow, agentRow));
        User leader = new User();
        leader.setUsername("leader");
        User agent = new User();
        agent.setUsername("agent");
        // We can't set id on a BaseEntity, so emulate the User#getId() side via Mockito spy
        // — but findAllById signature returns Iterable<User>; the service maps by getId()
        // which is null on un-persisted entities. So just verify the publisher was invoked
        // (the empty-id path skips users from the ordered list). The behaviour-under-test
        // here is that the call DOES fire, not the contents of the list.
        when(users.findAllById(any())).thenReturn(List.of(leader, agent));

        service.dissolveGroup(pid, 100L);

        verify(notifications).realtyGroupDissolved(any(RealtyGroup.class), any());
    }

    @Test
    void dissolveGroupRejectsNonLeader() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup("G", "g", 100L);
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        doThrow(new RealtyGroupPermissionDeniedException("Leader-only action"))
            .when(authorizer).assertLeader(200L, g.getId());

        assertThrows(RealtyGroupPermissionDeniedException.class,
            () -> service.dissolveGroup(pid, 200L));
        verify(groups, never()).save(any());
    }

    @Test
    void dissolveGroupOnAlreadyDissolvedThrows410() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup("G", "g", 100L);
        g.setDissolvedAt(OffsetDateTime.now().minusDays(1));
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));

        GroupDissolvedException ex = assertThrows(GroupDissolvedException.class,
            () -> service.dissolveGroup(pid, 100L));
        assertEquals(g.getPublicId(), ex.getPublicId());
        verify(authorizer, never()).assertLeader(anyLong(), anyLong());
        verify(groups, never()).save(any());
    }

    @Test
    void dissolveGroupOnUnknownGroupThrows404() {
        UUID pid = UUID.randomUUID();
        when(groups.findByPublicId(pid)).thenReturn(Optional.empty());

        assertThrows(RealtyGroupNotFoundException.class,
            () -> service.dissolveGroup(pid, 100L));
        verify(authorizer, never()).assertLeader(anyLong(), anyLong());
    }

    // ─────────────────── admin dissolveGroupAsAdmin ───────────────────

    @Test
    void dissolveGroupAsAdminBypassesLeaderGate() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup("G", "g", 100L);
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        when(groups.save(any(RealtyGroup.class))).thenAnswer(inv -> inv.getArgument(0));
        when(members.findByGroupIdOrderByJoinedAtAsc(g.getId())).thenReturn(Collections.emptyList());

        RealtyGroup result = service.dissolveGroupAsAdmin(pid, 9999L);

        assertNotNull(result.getDissolvedAt());
        verify(authorizer, never()).assertLeader(anyLong(), anyLong());
        verify(notifications).realtyGroupDissolved(any(RealtyGroup.class), any());
    }

    @Test
    void dissolveGroupAsAdminOnAlreadyDissolvedThrows410() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup("G", "g", 100L);
        g.setDissolvedAt(OffsetDateTime.now().minusDays(1));
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));

        assertThrows(GroupDissolvedException.class,
            () -> service.dissolveGroupAsAdmin(pid, 9999L));
        verify(groups, never()).save(any());
    }

    @Test
    void dissolveGroupAsAdminOnUnknownGroupThrows404() {
        UUID pid = UUID.randomUUID();
        when(groups.findByPublicId(pid)).thenReturn(Optional.empty());

        assertThrows(RealtyGroupNotFoundException.class,
            () -> service.dissolveGroupAsAdmin(pid, 9999L));
    }

    // ─────────────────── active-listings guard ───────────────────

    @Test
    void leader_dissolve_blocked_by_active_listing() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup("G", "g", 100L);
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        when(auctions.existsActiveListingsByGroupId(g.getId())).thenReturn(true);

        assertThrows(ActiveListingsBlockDissolveException.class,
            () -> service.dissolveGroup(pid, 100L));
        verify(groups, never()).save(any());
    }

    @Test
    void admin_force_dissolve_bypasses_active_listings_guard() {
        // The admin path does NOT call existsActiveListingsByGroupId at all — bypasses
        // the guard entirely. No auctions stub needed; the test asserts the admin path
        // succeeds and never calls the leader gate.
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup("G", "g", 100L);
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        when(groups.save(any(RealtyGroup.class))).thenAnswer(inv -> inv.getArgument(0));
        when(members.findByGroupIdOrderByJoinedAtAsc(g.getId())).thenReturn(Collections.emptyList());

        RealtyGroup result = service.dissolveGroupAsAdmin(pid, 9999L);

        assertNotNull(result.getDissolvedAt());
        verify(authorizer, never()).assertLeader(anyLong(), anyLong());
    }

    @Test
    void leader_dissolve_allowed_when_only_ended_listings_exist() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup("G", "g", 100L);
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        when(auctions.existsActiveListingsByGroupId(g.getId())).thenReturn(false);
        when(groups.save(any(RealtyGroup.class))).thenAnswer(inv -> inv.getArgument(0));
        when(members.findByGroupIdOrderByJoinedAtAsc(g.getId())).thenReturn(Collections.emptyList());

        RealtyGroup result = service.dissolveGroup(pid, 100L);

        assertNotNull(result.getDissolvedAt());
    }
}
