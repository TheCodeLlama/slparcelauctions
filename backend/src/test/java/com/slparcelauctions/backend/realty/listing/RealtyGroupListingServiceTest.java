package com.slparcelauctions.backend.realty.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
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
import com.slparcelauctions.backend.parcel.ParcelLookupService;
import com.slparcelauctions.backend.parcel.dto.ParcelResponse;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupPermissionDeniedException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroupRepository;
import com.slparcelauctions.backend.user.User;

@ExtendWith(MockitoExtension.class)
class RealtyGroupListingServiceTest {

    @Mock RealtyGroupRepository groups;
    @Mock RealtyGroupMemberRepository members;
    @Mock RealtyGroupSlGroupRepository slGroups;
    @Mock RealtyGroupAuthorizer authorizer;
    @Mock AuctionService auctionService;
    @Mock ParcelLookupService parcelLookupService;

    @InjectMocks RealtyGroupListingService service;

    private static final UUID GROUP_PUBLIC_ID = UUID.randomUUID();
    private static final UUID PARCEL_UUID = UUID.randomUUID();
    private static final UUID SL_GROUP_UUID = UUID.randomUUID();
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

    // ─────────────────────── createGroupListing ───────────────────────

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

    // ─────────────────────── findEligibleForParcel ───────────────────────

    private ParcelResponse parcelOwnedBy(String ownerType, UUID ownerUuid) {
        return new ParcelResponse(
                PARCEL_UUID, ownerUuid, ownerType, "Owner Name", "Parcel Name",
                1L, "Coniston", "GENERAL", 1000.0, 2000.0,
                128.0, 64.0, 22.0, 1024, "desc", null, "slurl",
                true, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private RealtyGroupMember memberWithPerms(Long groupId, Long userId,
            Set<RealtyGroupPermission> perms) {
        RealtyGroupMember m = RealtyGroupMember.builder()
                .groupId(groupId)
                .userId(userId)
                .joinedAt(OffsetDateTime.now())
                .build();
        m.setPermissionSet(perms);
        return m;
    }

    @Test
    void findEligibleForParcel_groupOwned_returnsMatchingGroups() {
        // Parcel is owned by an SL group; the realty group has a verified registration
        // for that SL group; the caller is the leader → returned as eligible.
        when(parcelLookupService.lookup(PARCEL_UUID))
                .thenReturn(new ParcelLookupService.ParcelLookupResult(
                        parcelOwnedBy("group", SL_GROUP_UUID), null));
        when(slGroups.findRealtyGroupsForListingCaller(CALLER_USER_ID, SL_GROUP_UUID))
                .thenReturn(List.of(group));
        // Caller is the leader of this group, so no member-row lookup is needed.

        List<ListingEligibleGroupDto> result = service.findEligibleForParcel(CALLER_USER_ID, PARCEL_UUID);

        assertThat(result).hasSize(1);
        ListingEligibleGroupDto dto = result.get(0);
        assertThat(dto.publicId()).isEqualTo(group.getPublicId());
        assertThat(dto.name()).isEqualTo("Mainland Realty Co.");
        assertThat(dto.slug()).isEqualTo("mainland-realty-co");
        assertThat(dto.logoUrl()).isNull();
        // Case-3 → agentFeeRate is null (per-member rate replaces the group-level rate).
        assertThat(dto.agentFeeRate()).isNull();
        verify(members, never()).findByGroupIdAndUserId(group.getId(), CALLER_USER_ID);
    }

    @Test
    void findEligibleForParcel_callerHasCreateListingPermission_returnsGroup() {
        // Caller is not the leader but has CREATE_LISTING → returned as eligible.
        RealtyGroup notLeaderGroup = RealtyGroup.builder()
                .id(GROUP_ID)
                .name("Other Realty")
                .slug("other-realty")
                .leaderId(999L) // someone else
                .agentFeeRate(new BigDecimal("0.0300"))
                .build();
        when(parcelLookupService.lookup(PARCEL_UUID))
                .thenReturn(new ParcelLookupService.ParcelLookupResult(
                        parcelOwnedBy("group", SL_GROUP_UUID), null));
        when(slGroups.findRealtyGroupsForListingCaller(CALLER_USER_ID, SL_GROUP_UUID))
                .thenReturn(List.of(notLeaderGroup));
        when(members.findByGroupIdAndUserId(GROUP_ID, CALLER_USER_ID))
                .thenReturn(Optional.of(memberWithPerms(GROUP_ID, CALLER_USER_ID,
                        EnumSet.of(RealtyGroupPermission.CREATE_LISTING))));

        List<ListingEligibleGroupDto> result = service.findEligibleForParcel(CALLER_USER_ID, PARCEL_UUID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).slug()).isEqualTo("other-realty");
    }

    @Test
    void findEligibleForParcel_agentOwned_returnsEmpty() {
        // Personal land (ownerType == "agent") → no group is eligible, no DB lookup.
        when(parcelLookupService.lookup(PARCEL_UUID))
                .thenReturn(new ParcelLookupService.ParcelLookupResult(
                        parcelOwnedBy("agent", UUID.randomUUID()), null));

        List<ListingEligibleGroupDto> result = service.findEligibleForParcel(CALLER_USER_ID, PARCEL_UUID);

        assertThat(result).isEmpty();
        verify(slGroups, never()).findRealtyGroupsForListingCaller(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void findEligibleForParcel_groupOwned_butNoMatchingRegistration_returnsEmpty() {
        // The parcel is group-owned but no realty group has a verified registration for it
        // (and/or the caller is not a member of any such realty group).
        when(parcelLookupService.lookup(PARCEL_UUID))
                .thenReturn(new ParcelLookupService.ParcelLookupResult(
                        parcelOwnedBy("group", SL_GROUP_UUID), null));
        when(slGroups.findRealtyGroupsForListingCaller(CALLER_USER_ID, SL_GROUP_UUID))
                .thenReturn(List.of());

        List<ListingEligibleGroupDto> result = service.findEligibleForParcel(CALLER_USER_ID, PARCEL_UUID);

        assertThat(result).isEmpty();
    }

    @Test
    void findEligibleForParcel_callerHasNoCreateListing_returnsEmpty() {
        // Caller is a non-leader member without CREATE_LISTING → filtered out in Java.
        RealtyGroup notLeaderGroup = RealtyGroup.builder()
                .id(GROUP_ID)
                .name("Other Realty")
                .slug("other-realty")
                .leaderId(999L) // someone else
                .build();
        when(parcelLookupService.lookup(PARCEL_UUID))
                .thenReturn(new ParcelLookupService.ParcelLookupResult(
                        parcelOwnedBy("group", SL_GROUP_UUID), null));
        when(slGroups.findRealtyGroupsForListingCaller(CALLER_USER_ID, SL_GROUP_UUID))
                .thenReturn(List.of(notLeaderGroup));
        when(members.findByGroupIdAndUserId(GROUP_ID, CALLER_USER_ID))
                .thenReturn(Optional.of(memberWithPerms(GROUP_ID, CALLER_USER_ID,
                        EnumSet.of(RealtyGroupPermission.INVITE_AGENTS))));

        List<ListingEligibleGroupDto> result = service.findEligibleForParcel(CALLER_USER_ID, PARCEL_UUID);

        assertThat(result).isEmpty();
    }

    @Test
    void findEligibleForParcel_ownerUuidNull_returnsEmpty() {
        // Defensive: ownerType=group but ownerUuid is null (SL world API hiccup) → empty.
        when(parcelLookupService.lookup(PARCEL_UUID))
                .thenReturn(new ParcelLookupService.ParcelLookupResult(
                        parcelOwnedBy("group", null), null));

        List<ListingEligibleGroupDto> result = service.findEligibleForParcel(CALLER_USER_ID, PARCEL_UUID);

        assertThat(result).isEmpty();
        verify(slGroups, never()).findRealtyGroupsForListingCaller(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void findEligibleForParcel_caseInsensitiveOwnerTypeMatch() {
        // ownerType comparison is case-insensitive — "Group" should match too.
        when(parcelLookupService.lookup(PARCEL_UUID))
                .thenReturn(new ParcelLookupService.ParcelLookupResult(
                        parcelOwnedBy("Group", SL_GROUP_UUID), null));
        when(slGroups.findRealtyGroupsForListingCaller(CALLER_USER_ID, SL_GROUP_UUID))
                .thenReturn(List.of(group));

        List<ListingEligibleGroupDto> result = service.findEligibleForParcel(CALLER_USER_ID, PARCEL_UUID);

        assertThat(result).hasSize(1);
        // Silence unused-stub warnings if any path skips them.
        lenient().when(members.findByGroupIdAndUserId(group.getId(), CALLER_USER_ID))
                .thenReturn(Optional.empty());
    }
}
