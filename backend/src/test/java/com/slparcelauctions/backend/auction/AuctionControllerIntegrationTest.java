package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.auction.dto.AuctionCancelRequest;
import com.slparcelauctions.backend.auction.dto.AuctionCreateRequest;
import com.slparcelauctions.backend.auction.dto.AuctionUpdateRequest;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.sl.SlMapApiClient;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.GridCoordinates;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import reactor.core.publisher.Mono;

/**
 * Full-stack coverage for {@code /api/v1/auctions*} endpoints.
 *
 * <p>Uses {@code @SpringBootTest} so the full Spring Security filter chain,
 * JWT auth filter, {@code AuctionExceptionHandler}, and
 * {@code GlobalExceptionHandler} all run. External SL HTTP clients are mocked
 * via {@code @MockitoBean} so registering a parcel via the parcel lookup
 * endpoint stays offline.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "auth.cleanup.enabled=false")
@Transactional
class AuctionControllerIntegrationTest {

    private static final String TRUSTED_OWNER = "00000000-0000-0000-0000-000000000001";

    @Autowired MockMvc mockMvc;
    @Autowired ParcelRepository parcelRepository;
    @Autowired AuctionRepository auctionRepository;
    @Autowired UserRepository userRepository;
    @Autowired ListingFeeRefundRepository refundRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean SlWorldApiClient worldApi;
    @MockitoBean SlMapApiClient mapApi;

    /** Verified seller. */
    private String sellerAccessToken;
    private Long sellerId;

    /** Another verified user (for non-seller paths). */
    private String otherAccessToken;

    /** Unverified user (for 403 on write paths). */
    private String unverifiedAccessToken;

    /** A parcel owned by {@code sellerId}. */
    private Parcel sellerParcel;

    @BeforeEach
    void setUp() throws Exception {
        sellerAccessToken = registerAndVerifyUser(
                "auction-seller@example.com", "Seller",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        sellerId = userRepository.findByEmail("auction-seller@example.com").orElseThrow().getId();

        otherAccessToken = registerAndVerifyUser(
                "auction-other@example.com", "Other",
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        unverifiedAccessToken = registerUser("auction-unverified@example.com", "Unverified");

        sellerParcel = seedParcel();
    }

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    @Test
    void create_validRequest_returns201AndDraft() throws Exception {
        AuctionCreateRequest req = new AuctionCreateRequest(
                sellerParcel.getId(), 1000L, null, null,
                168, false, null, "Nice parcel", null);

        mockMvc.perform(post("/api/v1/auctions")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.sellerId").value(sellerId))
                .andExpect(jsonPath("$.startingBid").value(1000))
                .andExpect(jsonPath("$.listingFeePaid").value(false));
    }

    @Test
    void create_asUnverifiedUser_returns403() throws Exception {
        AuctionCreateRequest req = new AuctionCreateRequest(
                sellerParcel.getId(), 1000L, null, null,
                168, false, null, null, null);

        mockMvc.perform(post("/api/v1/auctions")
                .header("Authorization", "Bearer " + unverifiedAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void create_nonExistentParcel_returns400() throws Exception {
        AuctionCreateRequest req = new AuctionCreateRequest(
                999999L, 1000L, null, null,
                168, false, null, null, null);

        mockMvc.perform(post("/api/v1/auctions")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // -------------------------------------------------------------------------
    // GET /auctions/{id}
    // -------------------------------------------------------------------------

    @Test
    void get_asSeller_returnsSellerView() throws Exception {
        Auction a = seedAuction(AuctionStatus.DRAFT, false, 0);

        mockMvc.perform(get("/api/v1/auctions/" + a.getId())
                .header("Authorization", "Bearer " + sellerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.listingFeePaid").exists())
                .andExpect(jsonPath("$.commissionRate").exists());
    }

    @Test
    void get_preActiveAsNonSeller_returns404() throws Exception {
        Auction a = seedAuction(AuctionStatus.DRAFT, false, 0);

        mockMvc.perform(get("/api/v1/auctions/" + a.getId())
                .header("Authorization", "Bearer " + otherAccessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUCTION_NOT_FOUND"));
    }

    @Test
    void get_activeAsNonSeller_returnsPublicView() throws Exception {
        Auction a = seedAuction(AuctionStatus.ACTIVE, false, 0);

        mockMvc.perform(get("/api/v1/auctions/" + a.getId())
                .header("Authorization", "Bearer " + otherAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.hasReserve").exists())
                .andExpect(jsonPath("$.reserveMet").exists())
                // No seller-only fields leak
                .andExpect(jsonPath("$.listingFeePaid").doesNotExist())
                .andExpect(jsonPath("$.commissionRate").doesNotExist())
                .andExpect(jsonPath("$.winnerId").doesNotExist())
                .andExpect(jsonPath("$.verificationNotes").doesNotExist());
    }

    @Test
    void get_cancelledAsNonSeller_returnsEndedWithoutWinnerOrFeeFields() throws Exception {
        Auction a = seedAuction(AuctionStatus.CANCELLED, true, 0);
        a.setWinnerId(null);
        a.setListingFeeAmt(100L);
        a.setCommissionAmt(0L);
        auctionRepository.save(a);

        mockMvc.perform(get("/api/v1/auctions/" + a.getId())
                .header("Authorization", "Bearer " + otherAccessToken))
                .andExpect(status().isOk())
                // CANCELLED collapses to ENDED in public view — privacy guarantee
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.winnerId").doesNotExist())
                .andExpect(jsonPath("$.listingFeeAmt").doesNotExist())
                .andExpect(jsonPath("$.listingFeePaid").doesNotExist());
    }

    @Test
    void get_suspendedAuction_viewedByNonSeller_returns404() throws Exception {
        // Spec §6.4 — suspended listings are hidden from public browse, and
        // direct URL access must match (404, not a public view collapsed to ENDED)
        // so that a bookmarked URL stops resolving once ownership monitoring
        // suspends the listing.
        Auction a = seedAuction(AuctionStatus.ACTIVE, false, 0);
        a.setStatus(AuctionStatus.SUSPENDED);
        auctionRepository.save(a);

        mockMvc.perform(get("/api/v1/auctions/" + a.getId())
                .header("Authorization", "Bearer " + otherAccessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUCTION_NOT_FOUND"));
    }

    // -------------------------------------------------------------------------
    // GET /users/me/auctions
    // -------------------------------------------------------------------------

    @Test
    void listMine_returnsSellerAuctions() throws Exception {
        seedAuction(AuctionStatus.DRAFT, false, 0);
        seedAuction(AuctionStatus.ACTIVE, false, 0);

        mockMvc.perform(get("/api/v1/users/me/auctions")
                .header("Authorization", "Bearer " + sellerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].sellerId").value(sellerId));
    }

    // -------------------------------------------------------------------------
    // PUT /auctions/{id}
    // -------------------------------------------------------------------------

    @Test
    void update_onDraft_returns200() throws Exception {
        Auction a = seedAuction(AuctionStatus.DRAFT, false, 0);
        AuctionUpdateRequest req = new AuctionUpdateRequest(
                2500L, null, null, null, null, null, "updated description", null);

        mockMvc.perform(put("/api/v1/auctions/" + a.getId())
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startingBid").value(2500))
                .andExpect(jsonPath("$.sellerDesc").value("updated description"));
    }

    @Test
    void update_onActive_returns409() throws Exception {
        Auction a = seedAuction(AuctionStatus.ACTIVE, false, 0);
        AuctionUpdateRequest req = new AuctionUpdateRequest(
                2500L, null, null, null, null, null, null, null);

        mockMvc.perform(put("/api/v1/auctions/" + a.getId())
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUCTION_INVALID_STATE"))
                .andExpect(jsonPath("$.currentState").value("ACTIVE"));
    }

    // -------------------------------------------------------------------------
    // PUT /auctions/{id}/verify — group-land gate (sub-spec 2 §7.2)
    // -------------------------------------------------------------------------

    @Test
    void verify_groupOwnedParcel_withNonSaleToBotMethod_returns422ProblemDetail() throws Exception {
        // Seed a group-owned parcel for the seller and a DRAFT_PAID auction
        // over it. The verify trigger must reject any method other than
        // SALE_TO_BOT with a 422 ProblemDetail carrying the agreed code/title.
        Parcel groupParcel = seedGroupOwnedParcel();
        Auction a = seedAuctionFor(groupParcel, AuctionStatus.DRAFT_PAID, true);

        mockMvc.perform(put("/api/v1/auctions/" + a.getId() + "/verify")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"UUID_ENTRY\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.title").value("Group-owned land requires Sale-to-bot"))
                .andExpect(jsonPath("$.code").value("GROUP_LAND_REQUIRES_SALE_TO_BOT"))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    // -------------------------------------------------------------------------
    // PUT /auctions/{id}/cancel
    // -------------------------------------------------------------------------

    @Test
    void cancel_onDraftPaid_createsRefundRow() throws Exception {
        Auction a = seedAuction(AuctionStatus.DRAFT_PAID, true, 0);
        a.setListingFeeAmt(100L);
        auctionRepository.save(a);

        mockMvc.perform(put("/api/v1/auctions/" + a.getId() + "/cancel")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new AuctionCancelRequest("no longer available"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        List<ListingFeeRefund> refunds = refundRepository.findAll();
        assertThat(refunds).anyMatch(r -> r.getAuction().getId().equals(a.getId())
                && r.getStatus() == RefundStatus.PENDING
                && r.getAmount() == 100L);
    }

    @Test
    void cancel_onCompleted_returns409() throws Exception {
        Auction a = seedAuction(AuctionStatus.COMPLETED, false, 0);

        mockMvc.perform(put("/api/v1/auctions/" + a.getId() + "/cancel")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new AuctionCancelRequest(null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUCTION_INVALID_STATE"));
    }

    // -------------------------------------------------------------------------
    // GET /auctions/{id}/preview
    // -------------------------------------------------------------------------

    @Test
    void preview_asSeller_returns200() throws Exception {
        Auction a = seedAuction(AuctionStatus.DRAFT, false, 0);

        mockMvc.perform(get("/api/v1/auctions/" + a.getId() + "/preview")
                .header("Authorization", "Bearer " + sellerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(a.getId()))
                .andExpect(jsonPath("$.sellerId").value(sellerId));
    }

    @Test
    void preview_asNonSeller_returns404() throws Exception {
        Auction a = seedAuction(AuctionStatus.DRAFT, false, 0);

        mockMvc.perform(get("/api/v1/auctions/" + a.getId() + "/preview")
                .header("Authorization", "Bearer " + otherAccessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUCTION_NOT_FOUND"));
    }

    // -------------------------------------------------------------------------
    // GET /users/{userId}/auctions — public active-listings (spec §14)
    // -------------------------------------------------------------------------

    @Test
    void getUserAuctions_returnsOnlyActiveForSeller() throws Exception {
        seedAuctionFor(seedExtraParcel(0x71), AuctionStatus.ACTIVE, false, 0,
                VerificationMethod.UUID_ENTRY);
        seedAuctionFor(seedExtraParcel(0x72), AuctionStatus.ACTIVE, false, 0,
                VerificationMethod.UUID_ENTRY);
        seedAuctionFor(seedExtraParcel(0x73), AuctionStatus.DRAFT, false, 0,
                VerificationMethod.UUID_ENTRY);
        seedAuctionFor(seedExtraParcel(0x74), AuctionStatus.SUSPENDED, false, 0,
                VerificationMethod.UUID_ENTRY);

        mockMvc.perform(get("/api/v1/users/" + sellerId + "/auctions")
                .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.content[1].status").value("ACTIVE"))
                // PublicAuctionResponse shape — no seller-only fields leak
                .andExpect(jsonPath("$.content[0].listingFeePaid").doesNotExist())
                .andExpect(jsonPath("$.content[0].commissionRate").doesNotExist())
                .andExpect(jsonPath("$.content[0].winnerId").doesNotExist())
                .andExpect(jsonPath("$.content[0].verificationNotes").doesNotExist());
    }

    @Test
    void getUserAuctions_suspendedAlwaysExcluded() throws Exception {
        seedAuctionFor(seedExtraParcel(0x81), AuctionStatus.SUSPENDED, false, 0,
                VerificationMethod.UUID_ENTRY);
        seedAuctionFor(seedExtraParcel(0x82), AuctionStatus.SUSPENDED, false, 0,
                VerificationMethod.UUID_ENTRY);

        mockMvc.perform(get("/api/v1/users/" + sellerId + "/auctions")
                .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void getUserAuctions_emptyWhenNoActive() throws Exception {
        // Seller has only DRAFT listings — no ACTIVE — so the page is empty.
        seedAuctionFor(seedExtraParcel(0x91), AuctionStatus.DRAFT, false, 0,
                VerificationMethod.UUID_ENTRY);

        mockMvc.perform(get("/api/v1/users/" + sellerId + "/auctions")
                .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void getUserAuctions_pagination() throws Exception {
        seedAuctionFor(seedExtraParcel(0xA1), AuctionStatus.ACTIVE, false, 0,
                VerificationMethod.UUID_ENTRY);
        seedAuctionFor(seedExtraParcel(0xA2), AuctionStatus.ACTIVE, false, 0,
                VerificationMethod.UUID_ENTRY);
        seedAuctionFor(seedExtraParcel(0xA3), AuctionStatus.ACTIVE, false, 0,
                VerificationMethod.UUID_ENTRY);

        mockMvc.perform(get("/api/v1/users/" + sellerId + "/auctions")
                .param("status", "ACTIVE")
                .param("page", "0")
                .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(2));
    }

    @Test
    void getUserAuctions_anonymousAccessAllowed() throws Exception {
        seedAuctionFor(seedExtraParcel(0xB1), AuctionStatus.ACTIVE, false, 0,
                VerificationMethod.UUID_ENTRY);

        // No Authorization header — spec §14 marks this endpoint public.
        mockMvc.perform(get("/api/v1/users/" + sellerId + "/auctions")
                .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void getUserAuctions_rejectsNonActiveStatus() throws Exception {
        mockMvc.perform(get("/api/v1/users/" + sellerId + "/auctions")
                .param("status", "ENDED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String registerUser(String email, String displayName) throws Exception {
        String body = String.format(
                "{\"email\":\"%s\",\"password\":\"hunter22abc\",\"displayName\":\"%s\"}",
                email, displayName);
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated()).andReturn();
        JsonNode json = objectMapper.readTree(reg.getResponse().getContentAsString());
        return json.get("accessToken").asText();
    }

    private String registerAndVerifyUser(String email, String displayName, String avatarUuid)
            throws Exception {
        String token = registerUser(email, displayName);
        MvcResult gen = mockMvc.perform(post("/api/v1/verification/generate")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        String code = objectMapper.readTree(gen.getResponse().getContentAsString())
                .get("code").asText();
        String body = String.format("""
            {
              "verificationCode":"%s",
              "avatarUuid":"%s",
              "avatarName":"%s",
              "displayName":"%s",
              "username":"test.resident",
              "bornDate":"2012-05-15",
              "payInfo":3
            }
            """, code, avatarUuid, displayName, displayName);
        mockMvc.perform(post("/api/v1/sl/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-SecondLife-Shard", "Production")
                .header("X-SecondLife-Owner-Key", TRUSTED_OWNER))
                .andExpect(status().isOk());
        return token;
    }

    /**
     * Seeds a parcel by going through the full /parcels/lookup flow once so the parcel row
     * is persisted and {@code verified=true}. We stub the SL HTTP clients to avoid network.
     */
    private Parcel seedParcel() throws Exception {
        UUID parcelUuid = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID ownerUuid = UUID.fromString("44444444-4444-4444-4444-444444444444");
        when(worldApi.fetchParcel(parcelUuid)).thenReturn(Mono.just(new ParcelMetadata(
                parcelUuid, ownerUuid, "agent",
                "Seed Parcel", "Coniston",
                1024, "Seed description", "http://example.com/snap.jpg", "MATURE",
                128.0, 64.0, 22.0)));
        when(mapApi.resolveRegion(any())).thenReturn(Mono.just(new GridCoordinates(260000.0, 254000.0)));

        mockMvc.perform(post("/api/v1/parcels/lookup")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slParcelUuid\":\"" + parcelUuid + "\"}"))
                .andExpect(status().isOk());

        return parcelRepository.findBySlParcelUuid(parcelUuid).orElseThrow();
    }

    private Auction seedAuction(AuctionStatus status, boolean listingFeePaid, int bidCount) {
        return seedAuctionFor(sellerParcel, status, listingFeePaid, bidCount,
                VerificationMethod.UUID_ENTRY);
    }

    private Auction seedAuctionFor(Parcel parcel, AuctionStatus status, boolean listingFeePaid) {
        // Sub-spec 2 §7.1 — verificationMethod is null until the seller picks
        // one at the verify trigger. Group-owned parcel tests rely on this.
        return seedAuctionFor(parcel, status, listingFeePaid, 0, null);
    }

    private Auction seedAuctionFor(Parcel parcel, AuctionStatus status,
            boolean listingFeePaid, int bidCount, VerificationMethod method) {
        User seller = userRepository.findById(sellerId).orElseThrow();
        Auction a = Auction.builder()
                .parcel(parcel)
                .seller(seller)
                .status(status)
                .verificationMethod(method)
                .startingBid(1000L)
                .durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(listingFeePaid)
                .currentBid(0L)
                .bidCount(bidCount)
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .build();
        if (status == AuctionStatus.ACTIVE) {
            OffsetDateTime now = OffsetDateTime.now();
            a.setStartsAt(now.minusHours(1));
            a.setEndsAt(now.plusDays(1));
            a.setOriginalEndsAt(now.plusDays(1));
        }
        return auctionRepository.save(a);
    }

    /**
     * Seeds an agent-owned verified parcel directly via the repository. Used
     * by tests that need multiple parcels (e.g. multi-auction scenarios) so
     * they don't trip the parcel-locking partial unique index.
     */
    private Parcel seedExtraParcel(int seed) {
        UUID parcelUuid = new UUID(0L, 0x10000000L + seed);
        UUID ownerUuid = new UUID(0L, 0x20000000L + seed);
        Parcel p = Parcel.builder()
                .slParcelUuid(parcelUuid)
                .ownerUuid(ownerUuid)
                .ownerType("agent")
                .regionName("Coniston")
                .continentName("Sansara")
                .areaSqm(1024)
                .verified(true)
                .build();
        return parcelRepository.save(p);
    }

    /**
     * Seeds a group-owned parcel directly via the repository (the /parcels/lookup
     * flow stubs an agent-owner, so bypass it). Group-owned parcels trip the
     * group-land gate on PUT /auctions/{id}/verify for any method other than
     * SALE_TO_BOT — sub-spec 2 §7.2.
     */
    private Parcel seedGroupOwnedParcel() {
        UUID parcelUuid = UUID.fromString("55555555-5555-5555-5555-555555555555");
        UUID groupUuid = UUID.fromString("66666666-6666-6666-6666-666666666666");
        Parcel p = Parcel.builder()
                .slParcelUuid(parcelUuid)
                .ownerUuid(groupUuid)
                .ownerType("group")
                .regionName("Coniston")
                .continentName("Sansara")
                .areaSqm(2048)
                .verified(true)
                .build();
        return parcelRepository.save(p);
    }
}
