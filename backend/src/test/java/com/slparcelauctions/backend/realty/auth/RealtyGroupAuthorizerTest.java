package com.slparcelauctions.backend.realty.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.exception.GroupDissolvedException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupPermissionDeniedException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;

@ExtendWith(MockitoExtension.class)
class RealtyGroupAuthorizerTest {

    @Mock RealtyGroupRepository groups;
    @Mock RealtyGroupMemberRepository members;
    @InjectMocks RealtyGroupAuthorizer authorizer;

    private static RealtyGroup activeGroup(Long leaderId) {
        return RealtyGroup.builder()
            .leaderId(leaderId)
            .name("G")
            .slug("g")
            .build();
    }

    private static RealtyGroup dissolvedGroup(Long leaderId) {
        RealtyGroup g = activeGroup(leaderId);
        g.setDissolvedAt(OffsetDateTime.now());
        return g;
    }

    private static RealtyGroupMember memberWith(Long groupId, Long userId,
                                                RealtyGroupPermission... perms) {
        RealtyGroupMember m = RealtyGroupMember.builder()
            .groupId(groupId).userId(userId)
            .joinedAt(OffsetDateTime.now())
            .build();
        m.setPermissionSet(EnumSet.copyOf(java.util.Arrays.asList(perms)));
        return m;
    }

    @Test
    void leaderHoldsEveryPermissionImplicitly() {
        when(groups.findById(1L)).thenReturn(Optional.of(activeGroup(100L)));
        for (RealtyGroupPermission p : RealtyGroupPermission.values()) {
            assertTrue(authorizer.canDo(100L, 1L, p), p.name());
        }
    }

    @Test
    void agentWithExplicitFlagSucceeds() {
        when(groups.findById(1L)).thenReturn(Optional.of(activeGroup(100L)));
        when(members.findByGroupIdAndUserId(1L, 200L))
            .thenReturn(Optional.of(memberWith(1L, 200L, RealtyGroupPermission.INVITE_AGENTS)));
        assertTrue(authorizer.canDo(200L, 1L, RealtyGroupPermission.INVITE_AGENTS));
        assertFalse(authorizer.canDo(200L, 1L, RealtyGroupPermission.REMOVE_AGENTS));
    }

    @Test
    void nonMemberReturnsFalse() {
        when(groups.findById(1L)).thenReturn(Optional.of(activeGroup(100L)));
        when(members.findByGroupIdAndUserId(1L, 999L)).thenReturn(Optional.empty());
        assertFalse(authorizer.canDo(999L, 1L, RealtyGroupPermission.INVITE_AGENTS));
    }

    @Test
    void dissolvedGroupRejectsLeaderToo() {
        when(groups.findById(1L)).thenReturn(Optional.of(dissolvedGroup(100L)));
        assertThrows(GroupDissolvedException.class,
            () -> authorizer.canDo(100L, 1L, RealtyGroupPermission.INVITE_AGENTS));
    }

    @Test
    void unknownGroupThrowsNotFound() {
        when(groups.findById(99L)).thenReturn(Optional.empty());
        assertThrows(RealtyGroupNotFoundException.class,
            () -> authorizer.canDo(1L, 99L, RealtyGroupPermission.INVITE_AGENTS));
    }

    @Test
    void assertCanThrowsOnDeny() {
        when(groups.findById(1L)).thenReturn(Optional.of(activeGroup(100L)));
        when(members.findByGroupIdAndUserId(1L, 200L)).thenReturn(Optional.empty());
        RealtyGroupPermissionDeniedException ex = assertThrows(RealtyGroupPermissionDeniedException.class,
            () -> authorizer.assertCan(200L, 1L, RealtyGroupPermission.INVITE_AGENTS));
        assertTrue(ex.getMissingPermission() == RealtyGroupPermission.INVITE_AGENTS);
    }

    @Test
    void isLeaderTrueForLeader() {
        when(groups.findById(1L)).thenReturn(Optional.of(activeGroup(100L)));
        assertTrue(authorizer.isLeader(100L, 1L));
        assertFalse(authorizer.isLeader(200L, 1L));
    }
}
