package com.slparcelauctions.backend.realty.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionService;
import com.slparcelauctions.backend.auction.dto.AuctionCreateRequest;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupPermissionDeniedException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.user.User;

@ExtendWith(MockitoExtension.class)
class RealtyGroupListingServiceTest {

    @Mock RealtyGroupRepository groups;
    @Mock RealtyGroupAuthorizer authorizer;
    @Mock AuctionService auctionService;

    @InjectMocks RealtyGroupListingService service;

    private static final UUID GROUP_PUBLIC_ID = UUID.randomUUID();
    private static final UUID PARCEL_UUID = UUID.randomUUID();
    private static final Long CALLER_USER_ID = 42L;
    private static final Long GROUP_ID = 99L;

    private RealtyGroup group;
    private AuctionCreateRequest req;

    @BeforeEach
    void setUp() {
        group = RealtyGroup.builder()
                .id(GROUP_ID)
                .name("Mainland Realty Co.")
                .slug("mainland-realty-co")
                .leaderId(CALLER_USER_ID)
                .agentFeeRate(new BigDecimal("0.0300"))
                .agentFeeSplit(new BigDecimal("0.6000"))
                .build();

        req = new AuctionCreateRequest(
                PARCEL_UUID, "Test listing", 1000L, null, null,
                168, false, null, null, Set.of(), GROUP_PUBLIC_ID);
    }

    @Test
    void create_group_listing_snapshots_rate_and_split() {
        User seller = new User();
        seller.setUsername("testseller");

        Auction created = Auction.builder()
                .seller(seller)
                .agentFeeRate(BigDecimal.ZERO)
                .build();

        when(groups.findByPublicIdAndDissolvedAtIsNull(GROUP_PUBLIC_ID))
                .thenReturn(Optional.of(group));
        when(auctionService.create(CALLER_USER_ID, req, "127.0.0.1"))
                .thenReturn(created);

        Auction result = service.createGroupListing(CALLER_USER_ID, req, "127.0.0.1");

        assertThat(result.getRealtyGroupId()).isEqualTo(GROUP_ID);
        assertThat(result.getAgentFeeRate()).isEqualByComparingTo(new BigDecimal("0.0300"));
        assertThat(result.getAgentFeeSplit()).isEqualByComparingTo(new BigDecimal("0.6000"));
        assertThat(result.getListingAgent()).isSameAs(seller);
    }

    @Test
    void create_group_listing_404_when_group_absent() {
        when(groups.findByPublicIdAndDissolvedAtIsNull(GROUP_PUBLIC_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createGroupListing(CALLER_USER_ID, req, "127.0.0.1"))
                .isInstanceOf(RealtyGroupNotFoundException.class);
    }

    @Test
    void create_group_listing_403_when_no_permission() {
        when(groups.findByPublicIdAndDissolvedAtIsNull(GROUP_PUBLIC_ID))
                .thenReturn(Optional.of(group));
        doThrow(new RealtyGroupPermissionDeniedException(RealtyGroupPermission.CREATE_LISTING))
                .when(authorizer).assertCan(CALLER_USER_ID, GROUP_ID, RealtyGroupPermission.CREATE_LISTING);

        assertThatThrownBy(() -> service.createGroupListing(CALLER_USER_ID, req, "127.0.0.1"))
                .isInstanceOf(RealtyGroupPermissionDeniedException.class);
    }
}
