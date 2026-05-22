package com.slparcelauctions.backend.realty.slgroup;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.common.BaseEntity;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.moderation.RealtyGroupGuard;
import com.slparcelauctions.backend.realty.moderation.exception.RealtyGroupSuspendedException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.realty.slgroup.exception.SlGroupAlreadyRegisteredException;
import com.slparcelauctions.backend.realty.slgroup.exception.SlGroupRegisteredToSuspendedGroupException;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.GroupPageData;

import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class RealtyGroupSlGroupServiceRegisterTest {

    @Mock RealtyGroupSlGroupRepository repo;
    @Mock RealtyGroupRepository groupRepo;
    @Mock RealtyGroupAuthorizer authorizer;
    @Mock RealtyGroupGuard realtyGroupGuard;
    @Mock SlWorldApiClient worldApi;
    @Mock SlGroupVerificationCodeGenerator codeGen;
    @Mock com.slparcelauctions.backend.auction.AuctionRepository auctionRepo;

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-05-12T12:00:00Z"), ZoneOffset.UTC);

    private RealtyGroup groupWithId(Long id, UUID publicId) {
        RealtyGroup group = RealtyGroup.builder().build();
        try {
            var idField = BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(group, id);
            var publicIdField = BaseEntity.class.getDeclaredField("publicId");
            publicIdField.setAccessible(true);
            publicIdField.set(group, publicId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return group;
    }

    @Test
    void register_happyPath_persistsPendingRow() {
        Long callerId = 7L;
        UUID groupPublic = UUID.randomUUID();
        UUID slGroupUuid = UUID.randomUUID();
        RealtyGroup group = groupWithId(42L, groupPublic);

        when(groupRepo.findByPublicIdAndDissolvedAtIsNull(groupPublic))
                .thenReturn(Optional.of(group));
        when(repo.findBySlGroupUuid(slGroupUuid)).thenReturn(Optional.empty());
        when(worldApi.fetchGroupPage(slGroupUuid)).thenReturn(Mono.just(
                new GroupPageData(slGroupUuid, "Sunset Realty", "About text", UUID.randomUUID())));
        when(codeGen.generate()).thenReturn("SLPA-ABCDEFGHJKMN");
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RealtyGroupSlGroupService svc = new RealtyGroupSlGroupService(
                repo, groupRepo, authorizer, realtyGroupGuard, worldApi, codeGen,
                auctionRepo,
                new com.slparcelauctions.backend.realty.moderation
                        .RealtyGroupModerationProperties(),
                clock);

        RealtyGroupSlGroup result = svc.register(callerId, groupPublic, slGroupUuid);

        verify(authorizer).assertCan(callerId, 42L, RealtyGroupPermission.REGISTER_SL_GROUP);
        assertThat(result.getVerificationCode()).isEqualTo("SLPA-ABCDEFGHJKMN");
        assertThat(result.getSlGroupName()).isEqualTo("Sunset Realty");
        assertThat(result.isVerified()).isFalse();
        assertThat(result.getRealtyGroupId()).isEqualTo(42L);
    }

    @Test
    void register_groupSuspended_throwsAndDoesNotPersist() {
        UUID groupPublic = UUID.randomUUID();
        UUID slGroupUuid = UUID.randomUUID();
        RealtyGroup group = groupWithId(42L, groupPublic);

        when(groupRepo.findByPublicIdAndDissolvedAtIsNull(groupPublic))
                .thenReturn(Optional.of(group));
        doThrow(new RealtyGroupSuspendedException(
                    RealtyGroupSuspendedException.Status.SUSPENDED,
                    java.time.OffsetDateTime.now(clock).plusDays(7), "TOS"))
            .when(realtyGroupGuard).requireGroupCanOperate(42L);

        RealtyGroupSlGroupService svc = new RealtyGroupSlGroupService(
                repo, groupRepo, authorizer, realtyGroupGuard, worldApi, codeGen,
                auctionRepo,
                new com.slparcelauctions.backend.realty.moderation
                        .RealtyGroupModerationProperties(),
                clock);

        assertThatThrownBy(() -> svc.register(7L, groupPublic, slGroupUuid))
                .isInstanceOf(RealtyGroupSuspendedException.class);

        verify(authorizer, never()).assertCan(anyLong(), anyLong(), any());
        verify(repo, never()).save(any());
        verify(repo, never()).findBySlGroupUuid(any());
    }

    @Test
    void register_alreadyRegistered_throws() {
        UUID groupPublic = UUID.randomUUID();
        UUID slGroupUuid = UUID.randomUUID();
        RealtyGroup group = groupWithId(42L, groupPublic);

        when(groupRepo.findByPublicIdAndDissolvedAtIsNull(groupPublic))
                .thenReturn(Optional.of(group));
        when(repo.existsForSuspendedRealtyGroup(slGroupUuid)).thenReturn(false);
        when(repo.findBySlGroupUuid(slGroupUuid))
                .thenReturn(Optional.of(RealtyGroupSlGroup.builder().build()));

        RealtyGroupSlGroupService svc = new RealtyGroupSlGroupService(
                repo, groupRepo, authorizer, realtyGroupGuard, worldApi, codeGen,
                auctionRepo,
                new com.slparcelauctions.backend.realty.moderation
                        .RealtyGroupModerationProperties(),
                clock);

        assertThatThrownBy(() -> svc.register(7L, groupPublic, slGroupUuid))
                .isInstanceOf(SlGroupAlreadyRegisteredException.class);
    }

    /**
     * Sub-project G §14 — reverse-search ban-evasion gate. When the SL group
     * UUID is currently registered to a realty group with an active (unlifted)
     * suspension row, registration is hard-blocked with the new code. The
     * suspension gate fires before the existing uniqueness check, so this
     * raises {@link SlGroupRegisteredToSuspendedGroupException} (the stronger
     * constraint), not {@link SlGroupAlreadyRegisteredException}.
     */
    @Test
    void register_slGroupOnSuspendedRealtyGroup_throwsReverseSearchException() {
        UUID groupPublic = UUID.randomUUID();
        UUID slGroupUuid = UUID.randomUUID();
        RealtyGroup group = groupWithId(42L, groupPublic);

        when(groupRepo.findByPublicIdAndDissolvedAtIsNull(groupPublic))
                .thenReturn(Optional.of(group));
        when(repo.existsForSuspendedRealtyGroup(slGroupUuid)).thenReturn(true);

        RealtyGroupSlGroupService svc = new RealtyGroupSlGroupService(
                repo, groupRepo, authorizer, realtyGroupGuard, worldApi, codeGen,
                auctionRepo,
                new com.slparcelauctions.backend.realty.moderation
                        .RealtyGroupModerationProperties(),
                clock);

        assertThatThrownBy(() -> svc.register(7L, groupPublic, slGroupUuid))
                .isInstanceOf(SlGroupRegisteredToSuspendedGroupException.class);

        verify(repo, never()).findBySlGroupUuid(any());
        verify(repo, never()).save(any());
    }
}
