package com.slparcelauctions.backend.realty.slgroup;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.common.BaseEntity;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupPermissionDeniedException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.realty.slgroup.exception.RegisteredSlGroupHasListingsException;
import com.slparcelauctions.backend.sl.SlWorldApiClient;

@ExtendWith(MockitoExtension.class)
class RealtyGroupSlGroupServiceListDeleteRecheckTest {

    @Mock RealtyGroupSlGroupRepository repo;
    @Mock RealtyGroupRepository groupRepo;
    @Mock RealtyGroupAuthorizer authorizer;
    @Mock com.slparcelauctions.backend.realty.moderation.RealtyGroupGuard realtyGroupGuard;
    @Mock SlWorldApiClient worldApi;
    @Mock SlGroupVerificationCodeGenerator codeGen;
    @Mock AuctionRepository auctionRepo;

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-05-12T12:00:00Z"), ZoneOffset.UTC);

    private RealtyGroupSlGroupService newService() {
        return new RealtyGroupSlGroupService(
                repo, groupRepo, authorizer, realtyGroupGuard, worldApi, codeGen,
                auctionRepo, clock);
    }

    private RealtyGroup groupWithId(Long id, UUID publicId) {
        RealtyGroup group = RealtyGroup.builder().build();
        setBaseFields(group, id, publicId);
        return group;
    }

    private RealtyGroupSlGroup rowWithId(Long id, UUID publicId, Long realtyGroupId, boolean verified) {
        RealtyGroupSlGroup row = RealtyGroupSlGroup.builder()
                .realtyGroupId(realtyGroupId)
                .slGroupUuid(UUID.randomUUID())
                .verified(verified)
                .build();
        setBaseFields(row, id, publicId);
        return row;
    }

    private static void setBaseFields(Object entity, Long id, UUID publicId) {
        try {
            var idField = BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
            var publicIdField = BaseEntity.class.getDeclaredField("publicId");
            publicIdField.setAccessible(true);
            publicIdField.set(entity, publicId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void listForGroup_returnsRowsForMember() {
        Long callerId = 7L;
        UUID groupPublic = UUID.randomUUID();
        RealtyGroup group = groupWithId(42L, groupPublic);

        RealtyGroupSlGroup r1 = rowWithId(100L, UUID.randomUUID(), 42L, true);
        RealtyGroupSlGroup r2 = rowWithId(101L, UUID.randomUUID(), 42L, false);

        when(groupRepo.findByPublicIdAndDissolvedAtIsNull(groupPublic))
                .thenReturn(Optional.of(group));
        when(authorizer.isMember(callerId, 42L)).thenReturn(true);
        when(repo.findByRealtyGroupIdOrderByCreatedAtDesc(42L)).thenReturn(List.of(r1, r2));

        List<RealtyGroupSlGroup> result = newService().listForGroup(callerId, groupPublic);

        assertThat(result).containsExactly(r1, r2);
    }

    @Test
    void listForGroup_nonMember_throwsPermissionDenied() {
        Long callerId = 99L;
        UUID groupPublic = UUID.randomUUID();
        RealtyGroup group = groupWithId(42L, groupPublic);

        when(groupRepo.findByPublicIdAndDissolvedAtIsNull(groupPublic))
                .thenReturn(Optional.of(group));
        when(authorizer.isMember(callerId, 42L)).thenReturn(false);

        RealtyGroupSlGroupService svc = newService();
        assertThatThrownBy(() -> svc.listForGroup(callerId, groupPublic))
                .isInstanceOf(RealtyGroupPermissionDeniedException.class);
        verify(repo, never()).findByRealtyGroupIdOrderByCreatedAtDesc(anyLong());
    }

    @Test
    void listForGroup_groupNotFound_throws() {
        UUID groupPublic = UUID.randomUUID();
        when(groupRepo.findByPublicIdAndDissolvedAtIsNull(groupPublic))
                .thenReturn(Optional.empty());

        RealtyGroupSlGroupService svc = newService();
        assertThatThrownBy(() -> svc.listForGroup(7L, groupPublic))
                .isInstanceOf(RealtyGroupNotFoundException.class);
    }

    @Test
    void unregister_happyPath_deletesRow() {
        Long callerId = 7L;
        UUID groupPublic = UUID.randomUUID();
        UUID slGroupPublic = UUID.randomUUID();
        RealtyGroup group = groupWithId(42L, groupPublic);
        RealtyGroupSlGroup row = rowWithId(100L, slGroupPublic, 42L, true);

        when(groupRepo.findByPublicIdAndDissolvedAtIsNull(groupPublic))
                .thenReturn(Optional.of(group));
        when(repo.findByPublicId(slGroupPublic)).thenReturn(Optional.of(row));
        when(auctionRepo.existsCase3ForSlGroup(100L)).thenReturn(false);

        newService().unregister(callerId, groupPublic, slGroupPublic);

        verify(authorizer).assertCan(callerId, 42L, RealtyGroupPermission.REGISTER_SL_GROUP);
        verify(repo).delete(row);
    }

    @Test
    void unregister_blocksWhenActiveListingsExist() {
        Long callerId = 7L;
        UUID groupPublic = UUID.randomUUID();
        UUID slGroupPublic = UUID.randomUUID();
        RealtyGroup group = groupWithId(42L, groupPublic);
        RealtyGroupSlGroup row = rowWithId(100L, slGroupPublic, 42L, true);

        when(groupRepo.findByPublicIdAndDissolvedAtIsNull(groupPublic))
                .thenReturn(Optional.of(group));
        when(repo.findByPublicId(slGroupPublic)).thenReturn(Optional.of(row));
        when(auctionRepo.existsCase3ForSlGroup(100L)).thenReturn(true);

        RealtyGroupSlGroupService svc = newService();
        assertThatThrownBy(() -> svc.unregister(callerId, groupPublic, slGroupPublic))
                .isInstanceOf(RegisteredSlGroupHasListingsException.class);
        verify(repo, never()).delete(any(RealtyGroupSlGroup.class));
    }

    @Test
    void unregister_otherGroupsRow_throwsNotFound() {
        Long callerId = 7L;
        UUID groupPublic = UUID.randomUUID();
        UUID slGroupPublic = UUID.randomUUID();
        RealtyGroup group = groupWithId(42L, groupPublic);
        // Row belongs to a DIFFERENT realty group (id 999), not 42.
        RealtyGroupSlGroup row = rowWithId(100L, slGroupPublic, 999L, true);

        when(groupRepo.findByPublicIdAndDissolvedAtIsNull(groupPublic))
                .thenReturn(Optional.of(group));
        when(repo.findByPublicId(slGroupPublic)).thenReturn(Optional.of(row));

        RealtyGroupSlGroupService svc = newService();
        assertThatThrownBy(() -> svc.unregister(callerId, groupPublic, slGroupPublic))
                .isInstanceOf(RealtyGroupNotFoundException.class);
        verify(repo, never()).delete(any(RealtyGroupSlGroup.class));
        verify(auctionRepo, never()).existsCase3ForSlGroup(anyLong());
    }

    @Test
    void unregister_rowNotFound_throwsNotFound() {
        UUID groupPublic = UUID.randomUUID();
        UUID slGroupPublic = UUID.randomUUID();
        RealtyGroup group = groupWithId(42L, groupPublic);

        when(groupRepo.findByPublicIdAndDissolvedAtIsNull(groupPublic))
                .thenReturn(Optional.of(group));
        when(repo.findByPublicId(slGroupPublic)).thenReturn(Optional.empty());

        RealtyGroupSlGroupService svc = newService();
        assertThatThrownBy(() -> svc.unregister(7L, groupPublic, slGroupPublic))
                .isInstanceOf(RealtyGroupNotFoundException.class);
    }

    @Test
    void recheck_alreadyVerified_isNoop() {
        Long callerId = 7L;
        UUID groupPublic = UUID.randomUUID();
        UUID slGroupPublic = UUID.randomUUID();
        RealtyGroup group = groupWithId(42L, groupPublic);
        RealtyGroupSlGroup row = rowWithId(100L, slGroupPublic, 42L, true);

        when(groupRepo.findByPublicIdAndDissolvedAtIsNull(groupPublic))
                .thenReturn(Optional.of(group));
        when(repo.findByPublicId(slGroupPublic)).thenReturn(Optional.of(row));

        RealtyGroupSlGroup result = newService().recheck(callerId, groupPublic, slGroupPublic);

        assertThat(result).isSameAs(row);
        assertThat(result.isVerified()).isTrue();
        // Sub-project F retired the about-text poll; recheck is a no-op now.
        verify(repo, never()).save(any(RealtyGroupSlGroup.class));
    }

    @Test
    void recheck_pendingRow_isNoop() {
        Long callerId = 7L;
        UUID groupPublic = UUID.randomUUID();
        UUID slGroupPublic = UUID.randomUUID();
        RealtyGroup group = groupWithId(42L, groupPublic);
        RealtyGroupSlGroup row = rowWithId(100L, slGroupPublic, 42L, false);

        when(groupRepo.findByPublicIdAndDissolvedAtIsNull(groupPublic))
                .thenReturn(Optional.of(group));
        when(repo.findByPublicId(slGroupPublic)).thenReturn(Optional.of(row));

        RealtyGroupSlGroup result = newService().recheck(callerId, groupPublic, slGroupPublic);

        // Sub-project F retired the about-text poll; recheck no longer mutates
        // pending rows -- FOUNDER_TERMINAL is the only verification path and is
        // driven by the in-world LSL callback, not by recheck.
        assertThat(result).isSameAs(row);
        assertThat(result.isVerified()).isFalse();
        verify(repo, never()).save(any(RealtyGroupSlGroup.class));
    }

    @Test
    void recheck_otherGroupsRow_isNoop() {
        Long callerId = 7L;
        UUID groupPublic = UUID.randomUUID();
        UUID slGroupPublic = UUID.randomUUID();
        RealtyGroup group = groupWithId(42L, groupPublic);
        // Different group id (999) — recheck returns the row without surfacing existence.
        RealtyGroupSlGroup row = rowWithId(100L, slGroupPublic, 999L, false);

        when(groupRepo.findByPublicIdAndDissolvedAtIsNull(groupPublic))
                .thenReturn(Optional.of(group));
        when(repo.findByPublicId(slGroupPublic)).thenReturn(Optional.of(row));

        RealtyGroupSlGroup result = newService().recheck(callerId, groupPublic, slGroupPublic);

        assertThat(result).isSameAs(row);
        verify(repo, never()).save(any(RealtyGroupSlGroup.class));
    }
}
