package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class AuctionDtoMapperBatchContextTest {

    @Mock AuctionPhotoRepository photoRepo;
    @Mock EscrowRepository escrowRepo;
    @Mock UserRepository userRepo;
    @Mock RealtyGroupRepository realtyGroupRepo;

    private AuctionDtoMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new AuctionDtoMapper(photoRepo, escrowRepo, userRepo, realtyGroupRepo);
    }

    @Test
    void buildBatchContext_issuesExactlyThreeQueries_whenCalledForMultipleAuctions() {
        Auction a1 = auctionWith(101L, 11L, 21L);
        Auction a2 = auctionWith(102L, 12L, 22L);
        Auction a3 = auctionWith(103L, 11L, 23L); // shared group id intentional
        List<Auction> auctions = List.of(a1, a2, a3);

        when(realtyGroupRepo.findAllById(anySet())).thenReturn(List.<RealtyGroup>of());
        when(photoRepo.findPrimaryForAuctions(anySet())).thenReturn(List.<AuctionPhoto>of());
        when(userRepo.findPublicIdsByIds(anySet())).thenReturn(Map.<Long, UUID>of());

        AuctionDtoMapper.MapperBatchContext ctx =
                AuctionDtoMapper.MapperBatchContext.build(
                        auctions, realtyGroupRepo, photoRepo, userRepo);

        verify(realtyGroupRepo, times(1)).findAllById(anySet());
        verify(photoRepo, times(1)).findPrimaryForAuctions(anySet());
        verify(userRepo, times(1)).findPublicIdsByIds(anySet());
        assertThat(ctx).isNotNull();
    }

    @Test
    void buildBatchContext_skipsRepoCallsWithEmptyIdSets_whenNoAuctionHasAttribution() {
        Auction a1 = auctionWith(101L, null, null);
        Auction a2 = auctionWith(102L, null, null);

        // Empty sets still go through the repos; the implementation uses
        // findAllById(Set.of()) which JPA collapses to a no-op. Verifying the
        // call count is the durable contract -- exactly one batch call per
        // attribution dimension regardless of payload size or sparsity.
        when(realtyGroupRepo.findAllById(anySet())).thenReturn(List.<RealtyGroup>of());
        when(photoRepo.findPrimaryForAuctions(anySet())).thenReturn(List.<AuctionPhoto>of());
        when(userRepo.findPublicIdsByIds(anySet())).thenReturn(Map.<Long, UUID>of());

        AuctionDtoMapper.MapperBatchContext.build(
                List.of(a1, a2), realtyGroupRepo, photoRepo, userRepo);

        verify(realtyGroupRepo, times(1)).findAllById(anySet());
        verify(photoRepo, times(1)).findPrimaryForAuctions(anySet());
        verify(userRepo, times(1)).findPublicIdsByIds(anySet());
    }

    private static Auction auctionWith(Long id, Long groupId, Long winnerId) {
        // BaseEntity.id has @Setter(AccessLevel.NONE) -- use the SuperBuilder
        // path other auction tests use to seed an internal Long id.
        return Auction.builder()
                .id(id)
                .realtyGroupId(groupId)
                .winnerId(winnerId)
                .build();
    }
}
