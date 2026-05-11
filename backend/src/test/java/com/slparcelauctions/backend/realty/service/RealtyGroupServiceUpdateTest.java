package com.slparcelauctions.backend.realty.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.dto.UpdateRealtyGroupRequest;
import com.slparcelauctions.backend.realty.exception.GroupDissolvedException;
import com.slparcelauctions.backend.realty.exception.InvalidWebsiteUrlException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNameTakenException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupPermissionDeniedException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupRenameCooldownException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.realty.slug.RealtyGroupSlugFactory;

@ExtendWith(MockitoExtension.class)
class RealtyGroupServiceUpdateTest {

    @Mock RealtyGroupRepository groups;
    @Mock RealtyGroupMemberRepository members;
    @Mock RealtyGroupSlugFactory slugFactory;
    @Mock RealtyGroupAuthorizer authorizer;

    @InjectMocks RealtyGroupService service;

    private static RealtyGroup buildGroup(String name, String slug, Long leaderId) {
        return RealtyGroup.builder()
            .name(name).slug(slug).leaderId(leaderId)
            .agentFeeRate(new BigDecimal("0.0000"))
            .agentFeeSplit(new BigDecimal("0.5000"))
            .build();
    }

    // ─────────────────── non-admin updateGroup ───────────────────

    @Test
    void updateGroupChangesDescriptionAndWebsiteWhenEditProfileGranted() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup("G", "g", 100L);
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        when(groups.save(any(RealtyGroup.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateRealtyGroupRequest req = new UpdateRealtyGroupRequest(
            null, "Updated description.", "https://new.example.com", null, null);
        RealtyGroup result = service.updateGroup(pid, req, 200L);

        assertEquals("Updated description.", result.getDescription());
        assertEquals("https://new.example.com", result.getWebsite());
        verify(authorizer).assertCan(200L, g.getId(), RealtyGroupPermission.EDIT_GROUP_PROFILE);
    }

    @Test
    void updateGroupRequiresEditProfileForProfileFields() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup("G", "g", 100L);
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        doThrow(new RealtyGroupPermissionDeniedException(RealtyGroupPermission.EDIT_GROUP_PROFILE))
            .when(authorizer).assertCan(200L, g.getId(), RealtyGroupPermission.EDIT_GROUP_PROFILE);

        UpdateRealtyGroupRequest req = new UpdateRealtyGroupRequest(
            "New Name", null, null, null, null);
        assertThrows(RealtyGroupPermissionDeniedException.class,
            () -> service.updateGroup(pid, req, 200L));
        verify(groups, never()).save(any());
    }

    @Test
    void updateGroupRequiresConfigureFeesForFeeFields() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup("G", "g", 100L);
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        doThrow(new RealtyGroupPermissionDeniedException(RealtyGroupPermission.CONFIGURE_FEES))
            .when(authorizer).assertCan(200L, g.getId(), RealtyGroupPermission.CONFIGURE_FEES);

        UpdateRealtyGroupRequest req = new UpdateRealtyGroupRequest(
            null, null, null, new BigDecimal("0.1000"), null);
        assertThrows(RealtyGroupPermissionDeniedException.class,
            () -> service.updateGroup(pid, req, 200L));
        verify(groups, never()).save(any());
    }

    @Test
    void updateGroupRenameWithinCooldownThrows409() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup("Old", "old", 100L);
        g.setLastRenamedAt(OffsetDateTime.now().minusDays(5));
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));

        UpdateRealtyGroupRequest req = new UpdateRealtyGroupRequest(
            "Brand New", null, null, null, null);

        RealtyGroupRenameCooldownException ex = assertThrows(
            RealtyGroupRenameCooldownException.class,
            () -> service.updateGroup(pid, req, 200L));
        // cooldownEndsAt = lastRenamedAt + 30d → about 25 days from now
        assertNotNull(ex.getCooldownEndsAt());
        verify(groups, never()).save(any());
    }

    @Test
    void updateGroupRenameOutsideCooldownSucceedsAndBumpsLastRenamedAt() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup("Old", "old", 100L);
        g.setLastRenamedAt(OffsetDateTime.now().minusDays(40));
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        when(groups.findByNameIgnoreCaseActive("Brand New")).thenReturn(Optional.empty());
        when(slugFactory.derive("Brand New", g.getId())).thenReturn("brand-new");
        when(groups.save(any(RealtyGroup.class))).thenAnswer(inv -> inv.getArgument(0));

        OffsetDateTime before = OffsetDateTime.now().minusSeconds(1);
        UpdateRealtyGroupRequest req = new UpdateRealtyGroupRequest(
            "Brand New", null, null, null, null);
        RealtyGroup result = service.updateGroup(pid, req, 200L);
        OffsetDateTime after = OffsetDateTime.now().plusSeconds(1);

        assertEquals("Brand New", result.getName());
        assertEquals("brand-new", result.getSlug());
        assertNotNull(result.getLastRenamedAt());
        org.junit.jupiter.api.Assertions.assertTrue(
            !result.getLastRenamedAt().isBefore(before) && !result.getLastRenamedAt().isAfter(after),
            "lastRenamedAt should be ~now");
    }

    @Test
    void updateGroupRenameFirstTimeSucceedsWhenLastRenamedAtIsNull() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup("Old", "old", 100L);
        // lastRenamedAt is null (never renamed)
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        when(groups.findByNameIgnoreCaseActive("Brand New")).thenReturn(Optional.empty());
        when(slugFactory.derive("Brand New", g.getId())).thenReturn("brand-new");
        when(groups.save(any(RealtyGroup.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateRealtyGroupRequest req = new UpdateRealtyGroupRequest(
            "Brand New", null, null, null, null);
        RealtyGroup result = service.updateGroup(pid, req, 200L);

        assertEquals("Brand New", result.getName());
        assertEquals("brand-new", result.getSlug());
        assertNotNull(result.getLastRenamedAt());
    }

    @Test
    void updateGroupRenameSameNameIsNoopAndDoesNotBumpCooldown() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup("Same", "same", 100L);
        g.setLastRenamedAt(OffsetDateTime.now().minusDays(5));
        OffsetDateTime originalRenamedAt = g.getLastRenamedAt();
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        when(groups.save(any(RealtyGroup.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateRealtyGroupRequest req = new UpdateRealtyGroupRequest(
            "Same", null, null, null, null);
        RealtyGroup result = service.updateGroup(pid, req, 200L);

        assertEquals(originalRenamedAt, result.getLastRenamedAt(),
            "no-op rename should not bump lastRenamedAt or trigger cooldown");
    }

    @Test
    void updateGroupRenameToTakenNameThrows409() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup("Old", "old", 100L);
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        // Another group already has that name (case-insensitive collision).
        RealtyGroup other = buildGroup("BRAND NEW", "brand-new", 999L);
        when(groups.findByNameIgnoreCaseActive("Brand New")).thenReturn(Optional.of(other));

        UpdateRealtyGroupRequest req = new UpdateRealtyGroupRequest(
            "Brand New", null, null, null, null);
        assertThrows(RealtyGroupNameTakenException.class,
            () -> service.updateGroup(pid, req, 200L));
        verify(groups, never()).save(any());
    }

    @Test
    void updateGroupRejectsInvalidWebsite() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup("G", "g", 100L);
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));

        UpdateRealtyGroupRequest req = new UpdateRealtyGroupRequest(
            null, null, "not-a-url", null, null);
        assertThrows(InvalidWebsiteUrlException.class,
            () -> service.updateGroup(pid, req, 200L));
    }

    @Test
    void updateGroupClearsDescriptionWhenBlankProvided() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup("G", "g", 100L);
        g.setDescription("old");
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        when(groups.save(any(RealtyGroup.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateRealtyGroupRequest req = new UpdateRealtyGroupRequest(
            null, "   ", null, null, null);
        RealtyGroup result = service.updateGroup(pid, req, 200L);
        assertNull(result.getDescription());
    }

    @Test
    void updateGroupSetsFeesWhenConfigureFeesGranted() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup("G", "g", 100L);
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        when(groups.save(any(RealtyGroup.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateRealtyGroupRequest req = new UpdateRealtyGroupRequest(
            null, null, null,
            new BigDecimal("0.1500"), new BigDecimal("0.6000"));
        RealtyGroup result = service.updateGroup(pid, req, 200L);

        assertEquals(new BigDecimal("0.1500"), result.getAgentFeeRate());
        assertEquals(new BigDecimal("0.6000"), result.getAgentFeeSplit());
        verify(authorizer).assertCan(200L, g.getId(), RealtyGroupPermission.CONFIGURE_FEES);
    }

    @Test
    void updateGroupOnDissolvedGroupThrows410() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup("G", "g", 100L);
        g.setDissolvedAt(OffsetDateTime.now());
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));

        UpdateRealtyGroupRequest req = new UpdateRealtyGroupRequest(
            null, "x", null, null, null);
        assertThrows(GroupDissolvedException.class,
            () -> service.updateGroup(pid, req, 200L));
    }

    @Test
    void updateGroupOnUnknownGroupThrows404() {
        UUID pid = UUID.randomUUID();
        when(groups.findByPublicId(pid)).thenReturn(Optional.empty());

        UpdateRealtyGroupRequest req = new UpdateRealtyGroupRequest(
            null, "x", null, null, null);
        assertThrows(RealtyGroupNotFoundException.class,
            () -> service.updateGroup(pid, req, 200L));
    }

    // ─────────────────── admin updateGroupAsAdmin ───────────────────

    @Test
    void updateGroupAsAdminBypassesPermissionChecks() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup("G", "g", 100L);
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        when(groups.save(any(RealtyGroup.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateRealtyGroupRequest req = new UpdateRealtyGroupRequest(
            null, "admin-set", null, new BigDecimal("0.2000"), null);
        service.updateGroupAsAdmin(pid, req, 9999L);

        verify(authorizer, never()).assertCan(anyLong(), any(), any());
    }

    @Test
    void updateGroupAsAdminBypassesRenameCooldown() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup("Old", "old", 100L);
        g.setLastRenamedAt(OffsetDateTime.now().minusDays(1)); // well inside cooldown
        OffsetDateTime originalRenamedAt = g.getLastRenamedAt();
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        when(groups.findByNameIgnoreCaseActive("Forced")).thenReturn(Optional.empty());
        when(slugFactory.derive("Forced", g.getId())).thenReturn("forced");
        when(groups.save(any(RealtyGroup.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateRealtyGroupRequest req = new UpdateRealtyGroupRequest(
            "Forced", null, null, null, null);
        RealtyGroup result = service.updateGroupAsAdmin(pid, req, 9999L);

        assertEquals("Forced", result.getName());
        assertEquals("forced", result.getSlug());
        // Admin rename must NOT bump lastRenamedAt — the leader's cooldown ledger is preserved.
        assertEquals(originalRenamedAt, result.getLastRenamedAt());
    }

    @Test
    void updateGroupAsAdminStillRejectsTakenName() {
        UUID pid = UUID.randomUUID();
        RealtyGroup g = buildGroup("Old", "old", 100L);
        when(groups.findByPublicId(pid)).thenReturn(Optional.of(g));
        RealtyGroup other = buildGroup("Taken", "taken", 999L);
        when(groups.findByNameIgnoreCaseActive("Taken")).thenReturn(Optional.of(other));

        UpdateRealtyGroupRequest req = new UpdateRealtyGroupRequest(
            "Taken", null, null, null, null);
        assertThrows(RealtyGroupNameTakenException.class,
            () -> service.updateGroupAsAdmin(pid, req, 9999L));
    }
}
