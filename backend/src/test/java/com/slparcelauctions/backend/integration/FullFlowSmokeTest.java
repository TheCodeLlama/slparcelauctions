package com.slparcelauctions.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.ListingFeeRefund;
import com.slparcelauctions.backend.auction.ListingFeeRefundRepository;
import com.slparcelauctions.backend.auction.RefundStatus;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.region.dto.RegionPageData;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.dto.ParcelPageData;
import com.slparcelauctions.backend.user.UserRepository;

import reactor.core.publisher.Mono;

/**
 * End-to-end smoke test for Epic 03 sub-spec 1. Exercises the full stack
 * via MockMvc — register/login, SL verify, parcel lookup, auction CRUD,
 * the three verification methods (A synchronous / B REZZABLE / C SALE_TO_BOT),
 * cancellation + refund row creation, and the seller-vs-public DTO collapse.
 *
 * <p>Only the external SL World/Map HTTP clients are mocked via
 * {@code @MockitoBean}. Postgres, Redis, and MinIO are real (the existing
 * dev-profile services must be running — same prerequisites as the other
 * integration tests).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
@Transactional
class FullFlowSmokeTest {

    private static final String TRUSTED_OWNER = "00000000-0000-0000-0000-000000000001";
    private static final String ESCROW_UUID = "00000000-0000-0000-0000-000000000099";
    private static final long SENTINEL_PRICE = 999999999L;

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AuctionRepository auctionRepository;
    @Autowired ListingFeeRefundRepository refundRepository;

    @MockitoBean SlWorldApiClient worldApi;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String sellerAccessToken;
    private Long sellerId;
    private UUID sellerPublicId;
    private String sellerAvatarUuid;
    private UUID parcelUuid;

    @BeforeEach
    void setUp() throws Exception {
        sellerAvatarUuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        sellerAccessToken = registerAndVerifyUser(
                "full-flow-seller@example.com", "FullFlowSeller", sellerAvatarUuid);
        var seller = userRepository.findByEmail("full-flow-seller@example.com").orElseThrow();
        sellerId = seller.getId();
        sellerPublicId = seller.getPublicId();
        parcelUuid = seedParcelViaLookup();
    }

    // -------------------------------------------------------------------------
    // Scenario 1: Method A (UUID_ENTRY) end-to-end happy path
    // -------------------------------------------------------------------------

    @Test
    void methodA_fullFlow_registerVerifyLookupCreatePayVerify_reachesActive() throws Exception {
        // Create a UUID_ENTRY auction
        UUID auctionPublicId = createAuction();

        // Pay the listing fee via the dev stub
        mockMvc.perform(post("/api/v1/dev/auctions/" + auctionPublicId + "/pay")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT_PAID"))
                .andExpect(jsonPath("$.listingFeePaid").value(true));

        // Trigger verification — World API mock already returns matching ownership
        mockMvc.perform(put("/api/v1/auctions/" + auctionPublicId + "/verify")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"UUID_ENTRY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.verificationTier").value("SCRIPT"))
                .andExpect(jsonPath("$.startsAt").exists())
                .andExpect(jsonPath("$.endsAt").exists())
                .andExpect(jsonPath("$.originalEndsAt").exists());

        Auction a = auctionRepository.findByPublicId(auctionPublicId).orElseThrow();
        assertThat(a.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(a.getVerificationTier()).isEqualTo(VerificationTier.SCRIPT);
        assertThat(a.getStartsAt()).isNotNull();
        assertThat(a.getEndsAt()).isEqualTo(a.getStartsAt().plusHours(168));
        assertThat(a.getOriginalEndsAt()).isEqualTo(a.getEndsAt());
        assertThat(a.getVerifiedAt()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Scenario 2: Method B (REZZABLE) end-to-end — /verify → /sl/parcel/verify
    // -------------------------------------------------------------------------

    @Test
    void methodB_fullFlow_lslCallbackCompletesVerification() throws Exception {
        UUID auctionPublicId = createAuction();

        mockMvc.perform(post("/api/v1/dev/auctions/" + auctionPublicId + "/pay")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isOk());

        MvcResult verifyRes = mockMvc.perform(put("/api/v1/auctions/" + auctionPublicId + "/verify")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"REZZABLE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFICATION_PENDING"))
                .andExpect(jsonPath("$.pendingVerification.method").value("REZZABLE"))
                .andExpect(jsonPath("$.pendingVerification.code").exists())
                .andExpect(jsonPath("$.pendingVerification.codeExpiresAt").exists())
                .andReturn();
        String code = objectMapper.readTree(verifyRes.getResponse().getContentAsString())
                .get("pendingVerification").get("code").asText();
        assertThat(code).matches("^[0-9]{6}$");

        // LSL callback
        String body = String.format("""
            {
              "verificationCode":"%s",
              "parcelUuid":"%s",
              "ownerUuid":"%s",
              "parcelName":"Test Parcel",
              "areaSqm":2048,
              "description":"Refreshed by in-world object",
              "primCapacity":468,
              "regionPosX":128.0,
              "regionPosY":64.0,
              "regionPosZ":22.5
            }
            """, code, parcelUuid, sellerAvatarUuid);

        mockMvc.perform(post("/api/v1/sl/parcel/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-SecondLife-Shard", "Production")
                .header("X-SecondLife-Owner-Key", TRUSTED_OWNER))
                .andExpect(status().isNoContent());

        Auction a = auctionRepository.findByPublicId(auctionPublicId).orElseThrow();
        assertThat(a.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(a.getVerificationTier()).isEqualTo(VerificationTier.SCRIPT);
        assertThat(a.getVerifiedAt()).isNotNull();
        assertThat(a.getStartsAt()).isNotNull();
        assertThat(a.getEndsAt()).isEqualTo(a.getStartsAt().plusHours(168));
    }

    // -------------------------------------------------------------------------
    // Scenario 3: Method C (SALE_TO_BOT) end-to-end — /verify → dev bot complete
    // -------------------------------------------------------------------------

    @Test
    void methodC_fullFlow_devBotCompleteReachesActive() throws Exception {
        UUID auctionPublicId = createAuction();

        mockMvc.perform(post("/api/v1/dev/auctions/" + auctionPublicId + "/pay")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isOk());

        MvcResult verifyRes = mockMvc.perform(put("/api/v1/auctions/" + auctionPublicId + "/verify")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"SALE_TO_BOT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFICATION_PENDING"))
                .andExpect(jsonPath("$.pendingVerification.method").value("SALE_TO_BOT"))
                .andExpect(jsonPath("$.pendingVerification.botTaskId").exists())
                .andExpect(jsonPath("$.pendingVerification.instructions").exists())
                .andReturn();
        Long botTaskId = objectMapper.readTree(verifyRes.getResponse().getContentAsString())
                .get("pendingVerification").get("botTaskId").asLong();

        String body = String.format("""
            {
              "result":"SUCCESS",
              "authBuyerId":"%s",
              "salePrice":%d,
              "parcelOwner":"%s",
              "parcelName":"Test Parcel",
              "areaSqm":2048,
              "regionName":"Coniston",
              "positionX":128.0,
              "positionY":64.0,
              "positionZ":22.0
            }
            """, ESCROW_UUID, SENTINEL_PRICE, sellerAvatarUuid);

        mockMvc.perform(post("/api/v1/dev/bot/tasks/" + botTaskId + "/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        Auction a = auctionRepository.findByPublicId(auctionPublicId).orElseThrow();
        assertThat(a.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(a.getVerificationTier()).isEqualTo(VerificationTier.BOT);
        assertThat(a.getVerifiedAt()).isNotNull();
        assertThat(a.getStartsAt()).isNotNull();
        assertThat(a.getEndsAt()).isEqualTo(a.getStartsAt().plusHours(168));
    }

    // -------------------------------------------------------------------------
    // Scenario 4: Cancellation on DRAFT_PAID creates a PENDING refund row
    // -------------------------------------------------------------------------

    @Test
    void cancel_onDraftPaid_createsPendingRefundRow() throws Exception {
        UUID auctionPublicId = createAuction();

        mockMvc.perform(post("/api/v1/dev/auctions/" + auctionPublicId + "/pay")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT_PAID"))
                .andExpect(jsonPath("$.listingFeeAmt").value(100));

        mockMvc.perform(put("/api/v1/auctions/" + auctionPublicId + "/cancel")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"changed my mind\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        final UUID expectedAuctionPublicId = auctionPublicId;
        List<ListingFeeRefund> refunds = refundRepository.findAll();
        assertThat(refunds)
                .as("cancelling a DRAFT_PAID auction must create a PENDING refund row")
                .anyMatch(r -> r.getAuction().getPublicId().equals(expectedAuctionPublicId)
                        && r.getStatus() == RefundStatus.PENDING
                        && r.getAmount() == 100L);
    }

    // -------------------------------------------------------------------------
    // Scenario 5: Public vs seller visibility + CANCELLED collapses to ENDED
    // -------------------------------------------------------------------------

    @Test
    void visibility_sellerGetsFullView_publicGetsCollapsedView() throws Exception {
        // A second verified user fetches the auction as a "non-seller" (endpoint
        // is authenticated; we can't fetch truly anonymously).
        String otherToken = registerAndVerifyUser(
                "full-flow-other@example.com", "FullFlowOther",
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        UUID auctionPublicId = createAuction();
        mockMvc.perform(post("/api/v1/dev/auctions/" + auctionPublicId + "/pay")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/auctions/" + auctionPublicId + "/verify")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"UUID_ENTRY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Seller view — full internal state
        mockMvc.perform(get("/api/v1/auctions/" + auctionPublicId)
                .header("Authorization", "Bearer " + sellerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.sellerPublicId").value(sellerPublicId.toString()))
                .andExpect(jsonPath("$.verificationMethod").value("UUID_ENTRY"))
                .andExpect(jsonPath("$.listingFeePaid").value(true))
                .andExpect(jsonPath("$.listingFeeAmt").exists())
                .andExpect(jsonPath("$.commissionRate").exists());

        // Non-seller public view — no listing fee, no winnerId, no verificationMethod,
        // no verificationNotes
        mockMvc.perform(get("/api/v1/auctions/" + auctionPublicId)
                .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.hasReserve").exists())
                .andExpect(jsonPath("$.reserveMet").exists())
                .andExpect(jsonPath("$.listingFeePaid").doesNotExist())
                .andExpect(jsonPath("$.listingFeeAmt").doesNotExist())
                .andExpect(jsonPath("$.commissionRate").doesNotExist())
                .andExpect(jsonPath("$.winnerId").doesNotExist())
                .andExpect(jsonPath("$.verificationNotes").doesNotExist())
                .andExpect(jsonPath("$.verificationMethod").doesNotExist())
                .andExpect(jsonPath("$.pendingVerification").doesNotExist());

        // Cancel the auction
        mockMvc.perform(put("/api/v1/auctions/" + auctionPublicId + "/cancel")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"no longer available\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // Non-seller sees ENDED (the privacy-boundary status collapse)
        mockMvc.perform(get("/api/v1/auctions/" + auctionPublicId)
                .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.winnerId").doesNotExist())
                .andExpect(jsonPath("$.listingFeeAmt").doesNotExist())
                .andExpect(jsonPath("$.listingFeePaid").doesNotExist());

        // Seller still sees the true CANCELLED status
        mockMvc.perform(get("/api/v1/auctions/" + auctionPublicId)
                .header("Authorization", "Bearer " + sellerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Creates a DRAFT auction via POST /api/v1/auctions. Per sub-spec 2 §7.1,
     * verificationMethod is not set at create time — it is chosen on the
     * verify trigger (each scenario calls PUT /auctions/{id}/verify with the
     * method it is exercising).
     */
    private UUID createAuction() throws Exception {
        String body = String.format("""
            {
              "slParcelUuid":"%s",
              "title":"Test listing",
              "startingBid":1000,
              "durationHours":168,
              "snipeProtect":false,
              "sellerDesc":"Nice parcel"
            }
            """, parcelUuid);
        MvcResult res = mockMvc.perform(post("/api/v1/auctions")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString())
                .get("publicId").asText());
    }

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

    private UUID seedParcelViaLookup() throws Exception {
        UUID regionUuid = UUID.randomUUID();
        UUID parcel = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID owner = UUID.fromString(sellerAvatarUuid);
        when(worldApi.fetchParcelPage(parcel)).thenReturn(
                Mono.just(new ParcelPageData(new ParcelMetadata(
                        parcel,
                owner,
                "agent",
                null,
                "Seed Parcel",
                "Coniston",
                1024,
                "Seed description",
                "http://example.com/snap.jpg",
                null,
                128.0,
                64.0,
                22.0), regionUuid)));
        // Coniston sits inside the Sansara Mainland bounding box (coords sourced
        // from ContinentDetector); the MainlandContinents check passes.
        when(worldApi.fetchRegionPage(regionUuid)).thenReturn(
                Mono.just(new RegionPageData(regionUuid, "Coniston", 1014.0, 1014.0, "M_NOT")));

        mockMvc.perform(post("/api/v1/parcels/lookup")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slParcelUuid\":\"" + parcel + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slParcelUuid").value(parcel.toString()));

        return parcel;
    }
}
