package com.slparcelauctions.backend.realty.listing;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroup;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroupRepository;
import com.slparcelauctions.backend.region.dto.RegionPageData;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.dto.ParcelPageData;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import reactor.core.publisher.Mono;

/**
 * Integration tests for {@link RealtyGroupListingController}:
 * <ul>
 *   <li>{@code GET /api/v1/realty/me/listing-eligible-groups?slParcelUuid=...} â€”
 *       parcel-aware (sub-project E Â§5.3)</li>
 *   <li>{@code GET /api/v1/realty/groups/{publicId}/listings}</li>
 * </ul>
 *
 * <p>Uses a full {@code @SpringBootTest} context with a real Postgres DB.
 * All mutations are wrapped in a class-level {@code @Transactional} so every
 * test rolls back cleanly and does not affect the shared DB state.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.realty.invitation-expiry.enabled=false"
})
@Transactional
class RealtyGroupListingControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;
    @Autowired RealtyGroupRepository groupRepository;
    @Autowired RealtyGroupMemberRepository memberRepository;
    @Autowired RealtyGroupSlGroupRepository slGroupRepository;
    @Autowired AuctionRepository auctionRepository;

    @MockitoBean SlWorldApiClient worldApi;

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ Shared fixtures â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private User caller;
    private String callerJwt;

    @BeforeEach
    void seedCaller() {
        caller = userRepository.save(User.builder()
            .username("rl-" + UUID.randomUUID().toString().substring(0, 8))
            .email("rl-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x").displayName("Caller").build());
        callerJwt = jwtService.issueAccessToken(
            new AuthPrincipal(caller.getId(), caller.getPublicId(), caller.getEmail(), 0L, Role.USER));
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private RealtyGroup saveGroup(String prefix, Long leaderId) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return groupRepository.save(RealtyGroup.builder()
            .name("Group " + slug)
            .slug(slug)
            .leaderId(leaderId)
            .build());
    }

    private RealtyGroup saveDissolvedGroup(String prefix, Long leaderId) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return groupRepository.save(RealtyGroup.builder()
            .name("Dissolved " + slug)
            .slug(slug)
            .leaderId(leaderId)
            .dissolvedAt(OffsetDateTime.now().minusDays(1))
            .build());
    }

    private RealtyGroupMember saveMemberRow(Long groupId, Long userId,
            java.util.Set<RealtyGroupPermission> perms) {
        RealtyGroupMember m = RealtyGroupMember.builder()
            .groupId(groupId)
            .userId(userId)
            .joinedAt(OffsetDateTime.now())
            .build();
        m.setPermissionSet(perms);
        return memberRepository.save(m);
    }

    /**
     * Seeds a verified {@link RealtyGroupSlGroup} row binding the given realty group
     * to the given SL group UUID. Used by the parcel-aware eligibility tests.
     */
    private RealtyGroupSlGroup saveVerifiedSlGroupRegistration(Long realtyGroupId, UUID slGroupUuid) {
        return slGroupRepository.save(RealtyGroupSlGroup.builder()
            .realtyGroupId(realtyGroupId)
            .slGroupUuid(slGroupUuid)
            .slGroupName("SL Group " + slGroupUuid.toString().substring(0, 6))
            .verified(true)
            .verifiedAt(OffsetDateTime.now())
            .build());
    }

    /**
     * Stubs the SL world API to make the given parcel UUID look like it is owned
     * by an SL group with the given ownerUuid (or by an agent when {@code ownerType ==
     * "agent"}). {@link com.slparcelauctions.backend.parcel.ParcelLookupService#lookup}
     * fans out to two world-API calls; both must be stubbed.
     */
    private void stubParcelOwnedBy(UUID parcelUuid, String ownerType, UUID ownerUuid) {
        UUID regionUuid = UUID.randomUUID();
        when(worldApi.fetchParcelPage(parcelUuid)).thenReturn(
                Mono.just(new ParcelPageData(new ParcelMetadata(
                        parcelUuid,
                        ownerUuid,
                        ownerType,
                        ownerType.equalsIgnoreCase("agent") ? "Owner Avatar" : null,
                        "Seed Parcel",
                        "Coniston",
                        1024,
                        "Seed description",
                        "http://example.com/snap.jpg",
                        null,
                        128.0,
                        64.0,
                        22.0), regionUuid)));
        when(worldApi.fetchRegionPage(regionUuid)).thenReturn(
                Mono.just(new RegionPageData(regionUuid, "Coniston", 1014.0, 1014.0, "M_NOT")));
    }

    private Auction saveAuction(User seller, RealtyGroup group, AuctionStatus status) {
        UUID parcelUuid = UUID.randomUUID();
        Auction a = Auction.builder()
            .title("Test listing")
            .slParcelUuid(parcelUuid)
            .seller(seller)
            .status(status)
            .startingBid(1000L)
            .durationHours(168)
            .snipeProtect(false)
            .listingFeePaid(false)
            .currentBid(0L)
            .bidCount(0)
            .consecutiveWorldApiFailures(0)
            .commissionRate(new BigDecimal("0.0500"))
            .realtyGroupId(group == null ? null : group.getId())
            .build();
        if (status == AuctionStatus.ACTIVE) {
            OffsetDateTime now = OffsetDateTime.now();
            a.setStartsAt(now.minusHours(1));
            a.setEndsAt(now.plusDays(1));
            a.setOriginalEndsAt(now.plusDays(1));
        }
        a = auctionRepository.save(a);
        a.setParcelSnapshot(AuctionParcelSnapshot.builder()
            .slParcelUuid(parcelUuid)
            .ownerUuid(UUID.randomUUID())
            .ownerType("agent")
            .parcelName("Test Parcel")
            .regionName("Coniston")
            .regionMaturityRating("GENERAL")
            .areaSqm(1024)
            .positionX(128.0).positionY(64.0).positionZ(22.0)
            .build());
        return auctionRepository.save(a);
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // GET /api/v1/realty/me/listing-eligible-groups?slParcelUuid=...
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    void getListingEligibleGroups_missingSlParcelUuid_400() throws Exception {
        mvc.perform(get("/api/v1/realty/me/listing-eligible-groups")
                .header("Authorization", "Bearer " + callerJwt))
            .andExpect(status().isBadRequest());
    }

    @Test
    void getListingEligibleGroups_validRequest_passesThroughParcelUuid() throws Exception {
        // Parcel is agent-owned â†’ endpoint returns empty array but with 200; this proves
        // the parameter is parsed and routed.
        UUID parcelUuid = UUID.randomUUID();
        stubParcelOwnedBy(parcelUuid, "agent", UUID.randomUUID());

        mvc.perform(get("/api/v1/realty/me/listing-eligible-groups")
                .header("Authorization", "Bearer " + callerJwt)
                .param("slParcelUuid", parcelUuid.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listing_eligible_groups_returns_leader_implicit_group() throws Exception {
        // Caller is leader of group A; group A has a verified registration for the SL
        // group that owns the parcel â†’ group A appears.
        RealtyGroup groupA = saveGroup("leader", caller.getId());
        saveMemberRow(groupA.getId(), caller.getId(),
                java.util.EnumSet.noneOf(RealtyGroupPermission.class));
        UUID slGroupUuid = UUID.randomUUID();
        saveVerifiedSlGroupRegistration(groupA.getId(), slGroupUuid);
        UUID parcelUuid = UUID.randomUUID();
        stubParcelOwnedBy(parcelUuid, "group", slGroupUuid);

        mvc.perform(get("/api/v1/realty/me/listing-eligible-groups")
                .header("Authorization", "Bearer " + callerJwt)
                .param("slParcelUuid", parcelUuid.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.publicId == '" + groupA.getPublicId() + "')].slug")
                .value(groupA.getSlug()));
    }

    @Test
    void listing_eligible_groups_excludes_member_without_create_listing_permission() throws Exception {
        // Caller is a member of group B with only INVITE_AGENTS â€” must NOT appear even
        // though group B has a verified registration for the parcel's owner SL group.
        User groupLeader = userRepository.save(User.builder()
            .username("bl-" + UUID.randomUUID().toString().substring(0, 8))
            .email("bl-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x").displayName("BLeader").build());
        RealtyGroup groupB = saveGroup("noperm", groupLeader.getId());
        saveMemberRow(groupB.getId(), caller.getId(),
            java.util.EnumSet.of(RealtyGroupPermission.INVITE_AGENTS));
        UUID slGroupUuid = UUID.randomUUID();
        saveVerifiedSlGroupRegistration(groupB.getId(), slGroupUuid);
        UUID parcelUuid = UUID.randomUUID();
        stubParcelOwnedBy(parcelUuid, "group", slGroupUuid);

        mvc.perform(get("/api/v1/realty/me/listing-eligible-groups")
                .header("Authorization", "Bearer " + callerJwt)
                .param("slParcelUuid", parcelUuid.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.publicId == '" + groupB.getPublicId() + "')]").isEmpty());
    }

    @Test
    void listing_eligible_groups_includes_member_with_create_listing_permission() throws Exception {
        // Caller is a member of group C with CREATE_LISTING; group C has a verified
        // registration for the parcel's owner SL group â†’ group C appears.
        User groupLeader = userRepository.save(User.builder()
            .username("cl-" + UUID.randomUUID().toString().substring(0, 8))
            .email("cl-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x").displayName("CLeader").build());
        RealtyGroup groupC = saveGroup("hasperm", groupLeader.getId());
        saveMemberRow(groupC.getId(), caller.getId(),
            java.util.EnumSet.of(RealtyGroupPermission.CREATE_LISTING));
        UUID slGroupUuid = UUID.randomUUID();
        saveVerifiedSlGroupRegistration(groupC.getId(), slGroupUuid);
        UUID parcelUuid = UUID.randomUUID();
        stubParcelOwnedBy(parcelUuid, "group", slGroupUuid);

        mvc.perform(get("/api/v1/realty/me/listing-eligible-groups")
                .header("Authorization", "Bearer " + callerJwt)
                .param("slParcelUuid", parcelUuid.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.publicId == '" + groupC.getPublicId() + "')].name")
                .value(groupC.getName()))
            // Case-3 â†’ agentFeeRate is null (per-member rate replaces it).
            .andExpect(jsonPath("$[?(@.publicId == '" + groupC.getPublicId() + "')].agentFeeRate")
                .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())));
    }

    @Test
    void listing_eligible_groups_excludes_dissolved_group() throws Exception {
        // Caller is leader of dissolved group D â€” even with a verified registration, the
        // dissolved group must NOT appear.
        RealtyGroup groupD = saveDissolvedGroup("dissolved", caller.getId());
        saveMemberRow(groupD.getId(), caller.getId(),
                java.util.EnumSet.noneOf(RealtyGroupPermission.class));
        UUID slGroupUuid = UUID.randomUUID();
        saveVerifiedSlGroupRegistration(groupD.getId(), slGroupUuid);
        UUID parcelUuid = UUID.randomUUID();
        stubParcelOwnedBy(parcelUuid, "group", slGroupUuid);

        mvc.perform(get("/api/v1/realty/me/listing-eligible-groups")
                .header("Authorization", "Bearer " + callerJwt)
                .param("slParcelUuid", parcelUuid.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.publicId == '" + groupD.getPublicId() + "')]").isEmpty());
    }

    @Test
    void listing_eligible_groups_returns_empty_for_agent_owned_parcel() throws Exception {
        // Even though caller is leader of group E with a verified registration, parcel is
        // agent-owned â†’ no group is eligible (personal-list path applies).
        RealtyGroup groupE = saveGroup("agentparcel", caller.getId());
        saveMemberRow(groupE.getId(), caller.getId(),
                java.util.EnumSet.noneOf(RealtyGroupPermission.class));
        UUID slGroupUuid = UUID.randomUUID();
        saveVerifiedSlGroupRegistration(groupE.getId(), slGroupUuid);
        UUID parcelUuid = UUID.randomUUID();
        stubParcelOwnedBy(parcelUuid, "agent", UUID.randomUUID());

        mvc.perform(get("/api/v1/realty/me/listing-eligible-groups")
                .header("Authorization", "Bearer " + callerJwt)
                .param("slParcelUuid", parcelUuid.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.publicId == '" + groupE.getPublicId() + "')]").isEmpty());
    }

    @Test
    void listing_eligible_groups_returns_empty_when_no_verified_registration() throws Exception {
        // Caller is leader of group F, but no realty group has a verified registration
        // for the parcel's owner SL group â†’ empty.
        RealtyGroup groupF = saveGroup("nogeoreg", caller.getId());
        saveMemberRow(groupF.getId(), caller.getId(),
                java.util.EnumSet.noneOf(RealtyGroupPermission.class));
        UUID parcelUuid = UUID.randomUUID();
        // Stub the parcel as owned by an SL group that has NO verified registration.
        stubParcelOwnedBy(parcelUuid, "group", UUID.randomUUID());

        mvc.perform(get("/api/v1/realty/me/listing-eligible-groups")
                .header("Authorization", "Bearer " + callerJwt)
                .param("slParcelUuid", parcelUuid.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.publicId == '" + groupF.getPublicId() + "')]").isEmpty());
    }

    @Test
    void listing_eligible_groups_requires_auth() throws Exception {
        UUID parcelUuid = UUID.randomUUID();
        mvc.perform(get("/api/v1/realty/me/listing-eligible-groups")
                .param("slParcelUuid", parcelUuid.toString()))
            .andExpect(status().isUnauthorized());
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // GET /api/v1/realty/groups/{publicId}/listings
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    void group_listings_returns_active_auctions_by_default() throws Exception {
        RealtyGroup group = saveGroup("listings", caller.getId());
        saveMemberRow(group.getId(), caller.getId(), java.util.EnumSet.noneOf(RealtyGroupPermission.class));

        saveAuction(caller, group, AuctionStatus.ACTIVE);
        saveAuction(caller, group, AuctionStatus.ACTIVE);
        saveAuction(caller, group, AuctionStatus.COMPLETED);

        mvc.perform(get("/api/v1/realty/groups/" + group.getPublicId() + "/listings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.content[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$.content[1].status").value("ACTIVE"));
    }

    @Test
    void group_listings_accepts_csv_status_filter() throws Exception {
        RealtyGroup group = saveGroup("csv", caller.getId());
        saveMemberRow(group.getId(), caller.getId(), java.util.EnumSet.noneOf(RealtyGroupPermission.class));

        saveAuction(caller, group, AuctionStatus.ACTIVE);
        saveAuction(caller, group, AuctionStatus.ACTIVE);
        saveAuction(caller, group, AuctionStatus.COMPLETED);

        mvc.perform(get("/api/v1/realty/groups/" + group.getPublicId() + "/listings")
                .param("status", "ACTIVE,COMPLETED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(3));
    }

    @Test
    void group_listings_404_when_group_absent() throws Exception {
        mvc.perform(get("/api/v1/realty/groups/" + UUID.randomUUID() + "/listings"))
            .andExpect(status().isNotFound());
    }

    @Test
    void group_listings_is_public_no_auth_required() throws Exception {
        RealtyGroup group = saveGroup("public", caller.getId());
        saveMemberRow(group.getId(), caller.getId(), java.util.EnumSet.noneOf(RealtyGroupPermission.class));

        mvc.perform(get("/api/v1/realty/groups/" + group.getPublicId() + "/listings"))
            .andExpect(status().isOk());
    }

}
