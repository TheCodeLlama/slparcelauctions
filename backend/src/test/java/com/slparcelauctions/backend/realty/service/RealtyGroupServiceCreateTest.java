package com.slparcelauctions.backend.realty.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.dto.CreateRealtyGroupRequest;
import com.slparcelauctions.backend.realty.exception.InvalidWebsiteUrlException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNameTakenException;
import com.slparcelauctions.backend.realty.slug.RealtyGroupSlugFactory;

@ExtendWith(MockitoExtension.class)
class RealtyGroupServiceCreateTest {

    @Mock RealtyGroupRepository groups;
    @Mock RealtyGroupMemberRepository members;
    @Mock RealtyGroupSlugFactory slugFactory;
    @Mock com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer authorizer;
    @Mock com.slparcelauctions.backend.notification.NotificationPublisher notifications;
    @Mock com.slparcelauctions.backend.user.UserRepository users;

    @InjectMocks RealtyGroupService service;

    @Test
    void createGroupHappyPathPersistsGroupAndLeaderMember() {
        CreateRealtyGroupRequest req = new CreateRealtyGroupRequest(
            "Mainland Realty Co.",
            "We sell mainland.",
            "https://example.com"
        );
        when(groups.findByNameIgnoreCaseActive("Mainland Realty Co.")).thenReturn(Optional.empty());
        when(slugFactory.derive("Mainland Realty Co.", null)).thenReturn("mainland-realty-co");
        when(groups.save(any(RealtyGroup.class))).thenAnswer(inv -> inv.getArgument(0));
        when(members.save(any(RealtyGroupMember.class))).thenAnswer(inv -> inv.getArgument(0));

        RealtyGroup created = service.createGroup(req, 42L);

        assertNotNull(created);
        assertEquals("Mainland Realty Co.", created.getName());
        assertEquals("mainland-realty-co", created.getSlug());
        assertEquals(42L, created.getLeaderId());
        assertEquals("We sell mainland.", created.getDescription());
        assertEquals("https://example.com", created.getWebsite());

        ArgumentCaptor<RealtyGroupMember> memberCap = ArgumentCaptor.forClass(RealtyGroupMember.class);
        verify(members).save(memberCap.capture());
        RealtyGroupMember leaderRow = memberCap.getValue();
        assertEquals(42L, leaderRow.getUserId());
        assertEquals(0, leaderRow.getPermissions().length, "leader row carries empty permissions");
        assertNotNull(leaderRow.getJoinedAt());
    }

    @Test
    void createGroupCaseInsensitiveNameConflictThrows409() {
        CreateRealtyGroupRequest req = new CreateRealtyGroupRequest("Mainland Realty", null, null);
        RealtyGroup existing = RealtyGroup.builder()
            .name("MAINLAND REALTY").slug("mainland-realty").leaderId(1L).build();
        when(groups.findByNameIgnoreCaseActive("Mainland Realty")).thenReturn(Optional.of(existing));

        assertThrows(RealtyGroupNameTakenException.class, () -> service.createGroup(req, 42L));

        verify(groups, never()).save(any());
        verify(members, never()).save(any());
    }

    @Test
    void createGroupInvalidWebsiteThrows400() {
        CreateRealtyGroupRequest req = new CreateRealtyGroupRequest(
            "Group A", null, "not a url"
        );
        when(groups.findByNameIgnoreCaseActive("Group A")).thenReturn(Optional.empty());

        assertThrows(InvalidWebsiteUrlException.class, () -> service.createGroup(req, 42L));

        verify(groups, never()).save(any());
    }

    @Test
    void createGroupNonHttpSchemeRejected() {
        CreateRealtyGroupRequest req = new CreateRealtyGroupRequest(
            "Group A", null, "ftp://example.com"
        );
        when(groups.findByNameIgnoreCaseActive("Group A")).thenReturn(Optional.empty());

        assertThrows(InvalidWebsiteUrlException.class, () -> service.createGroup(req, 42L));
    }

    @Test
    void createGroupBlankWebsiteTreatedAsNull() {
        CreateRealtyGroupRequest req = new CreateRealtyGroupRequest("Group A", null, "   ");
        when(groups.findByNameIgnoreCaseActive("Group A")).thenReturn(Optional.empty());
        when(slugFactory.derive("Group A", null)).thenReturn("group-a");
        when(groups.save(any(RealtyGroup.class))).thenAnswer(inv -> inv.getArgument(0));
        when(members.save(any(RealtyGroupMember.class))).thenAnswer(inv -> inv.getArgument(0));

        RealtyGroup created = service.createGroup(req, 1L);
        org.junit.jupiter.api.Assertions.assertNull(created.getWebsite());
    }

    @Test
    void createGroupPatchesSlugWhenFactoryReturnsGroupPlaceholder() {
        // Non-ASCII name → factory returns the placeholder "group". Service should patch
        // to "group-<first 8 of publicId>" after the entity has been persisted (so its
        // publicId is settled).
        CreateRealtyGroupRequest req = new CreateRealtyGroupRequest("中文名", null, null);
        when(groups.findByNameIgnoreCaseActive("中文名")).thenReturn(Optional.empty());
        when(slugFactory.derive("中文名", null)).thenReturn("group");
        when(groups.save(any(RealtyGroup.class))).thenAnswer(inv -> inv.getArgument(0));
        when(members.save(any(RealtyGroupMember.class))).thenAnswer(inv -> inv.getArgument(0));

        RealtyGroup created = service.createGroup(req, 7L);

        // Slug should be "group-" + first 8 chars of publicId (no dashes mid-uuid).
        String slug = created.getSlug();
        UUID pid = created.getPublicId();
        String expected = "group-" + pid.toString().substring(0, 8);
        assertEquals(expected, slug, "slug should be patched to group-<first 8 of publicId>");
        // Two save calls on the group: initial insert + slug-patch update.
        verify(groups, times(2)).save(any(RealtyGroup.class));
    }

    @Test
    void createGroupSetsJoinedAtOnLeaderRowToNowish() {
        CreateRealtyGroupRequest req = new CreateRealtyGroupRequest("X", null, null);
        when(groups.findByNameIgnoreCaseActive("X")).thenReturn(Optional.empty());
        when(slugFactory.derive("X", null)).thenReturn("x");
        when(groups.save(any(RealtyGroup.class))).thenAnswer(inv -> inv.getArgument(0));
        when(members.save(any(RealtyGroupMember.class))).thenAnswer(inv -> inv.getArgument(0));

        OffsetDateTime before = OffsetDateTime.now().minusSeconds(2);
        service.createGroup(req, 9L);
        OffsetDateTime after = OffsetDateTime.now().plusSeconds(2);

        ArgumentCaptor<RealtyGroupMember> cap = ArgumentCaptor.forClass(RealtyGroupMember.class);
        verify(members).save(cap.capture());
        OffsetDateTime joined = cap.getValue().getJoinedAt();
        org.junit.jupiter.api.Assertions.assertTrue(
            !joined.isBefore(before) && !joined.isAfter(after),
            "joinedAt should be ~now");
    }
}
