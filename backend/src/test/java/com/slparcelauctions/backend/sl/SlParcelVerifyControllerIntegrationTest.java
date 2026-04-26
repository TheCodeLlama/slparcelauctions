package com.slparcelauctions.backend.sl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.sl.dto.GridCoordinates;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import reactor.core.publisher.Mono;

/**
 * End-to-end integration test for Method B (REZZABLE): seller verifies,
 * LSL callback transitions auction to ACTIVE. Exercises the full Spring
 * Security filter chain so the {@code permitAll} config on
 * {@code /api/v1/sl/parcel/verify} is also validated.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false"
})
@Transactional
class SlParcelVerifyControllerIntegrationTest {

    private static final String TRUSTED_OWNER = "00000000-0000-0000-0000-000000000001";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired ParcelRepository parcelRepository;
    @Autowired AuctionRepository auctionRepository;

    @MockitoBean SlWorldApiClient worldApi;
    @MockitoBean SlMapApiClient mapApi;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String sellerAccessToken;
    private Long sellerId;
    private String sellerAvatarUuid;
    private Parcel sellerParcel;
    private UUID parcelUuid;

    @BeforeEach
    void setUp() throws Exception {
        sellerAvatarUuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        sellerAccessToken = registerAndVerifyUser(
                "method-b-seller@example.com", "MethodBSeller", sellerAvatarUuid);
        sellerId = userRepository.findByEmail("method-b-seller@example.com").orElseThrow().getId();
        sellerParcel = seedParcel();
        parcelUuid = sellerParcel.getSlParcelUuid();
    }

    @Test
    void fullFlow_verifyThenLslCallback_transitionsToActive() throws Exception {
        // Seller creates an auction, pays listing fee, then verifies with REZZABLE.
        Long auctionId = createAndPayAuction();
        MvcResult verifyRes = mockMvc.perform(put("/api/v1/auctions/" + auctionId + "/verify")
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

        // LSL callback posts the code + parcel + owner data.
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

        Auction updated = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(updated.getVerifiedAt()).isNotNull();
        assertThat(updated.getStartsAt()).isNotNull();
        assertThat(updated.getEndsAt()).isEqualTo(updated.getStartsAt().plusHours(168));
    }

    @Test
    void callbackWithMissingHeaders_returns403() throws Exception {
        Long auctionId = createAndPayAuction();
        MvcResult verifyRes = mockMvc.perform(put("/api/v1/auctions/" + auctionId + "/verify")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"REZZABLE\"}"))
                .andExpect(status().isOk()).andReturn();
        String code = objectMapper.readTree(verifyRes.getResponse().getContentAsString())
                .get("pendingVerification").get("code").asText();

        String body = String.format("""
            {"verificationCode":"%s","parcelUuid":"%s","ownerUuid":"%s"}
            """, code, parcelUuid, sellerAvatarUuid);

        mockMvc.perform(post("/api/v1/sl/parcel/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SL_INVALID_HEADERS"));
    }

    @Test
    void callbackWithWrongOwnerUuid_returns400AndAuctionStaysPending() throws Exception {
        Long auctionId = createAndPayAuction();
        MvcResult verifyRes = mockMvc.perform(put("/api/v1/auctions/" + auctionId + "/verify")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"REZZABLE\"}"))
                .andExpect(status().isOk()).andReturn();
        String code = objectMapper.readTree(verifyRes.getResponse().getContentAsString())
                .get("pendingVerification").get("code").asText();

        String body = String.format("""
            {"verificationCode":"%s","parcelUuid":"%s","ownerUuid":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"}
            """, code, parcelUuid);

        mockMvc.perform(post("/api/v1/sl/parcel/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-SecondLife-Shard", "Production")
                .header("X-SecondLife-Owner-Key", TRUSTED_OWNER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SL_INVALID_REQUEST"));

        Auction unchanged = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(AuctionStatus.VERIFICATION_PENDING);
    }

    @Test
    void callbackWithUnknownCode_returns400() throws Exception {
        // Don't even trigger /verify; just post a made-up code.
        String body = String.format("""
            {"verificationCode":"000000","parcelUuid":"%s","ownerUuid":"%s"}
            """, parcelUuid, sellerAvatarUuid);

        mockMvc.perform(post("/api/v1/sl/parcel/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-SecondLife-Shard", "Production")
                .header("X-SecondLife-Owner-Key", TRUSTED_OWNER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SL_CODE_NOT_FOUND"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Long createAndPayAuction() throws Exception {
        String body = String.format("""
            {
              "parcelId":%d,
              "title":"Test listing",
              "startingBid":1000,
              "durationHours":168,
              "snipeProtect":false,
              "sellerDesc":"Nice parcel"
            }
            """, sellerParcel.getId());
        MvcResult res = mockMvc.perform(post("/api/v1/auctions")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated()).andReturn();
        Long auctionId = objectMapper.readTree(res.getResponse().getContentAsString())
                .get("id").asLong();

        // Mark the listing fee paid directly — DevAuctionController /pay requires
        // specific pricing validations to be in place. Using the repository
        // keeps this integration test focused on the Method B flow.
        Auction a = auctionRepository.findById(auctionId).orElseThrow();
        a.setStatus(AuctionStatus.DRAFT_PAID);
        a.setListingFeePaid(true);
        a.setListingFeeAmt(100L);
        a.setListingFeeTxn("test-txn");
        a.setListingFeePaidAt(OffsetDateTime.now());
        a.setCommissionRate(new BigDecimal("0.05"));
        a.setAgentFeeRate(BigDecimal.ZERO);
        auctionRepository.save(a);
        return auctionId;
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

    private Parcel seedParcel() throws Exception {
        UUID parcel = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID owner = UUID.fromString(sellerAvatarUuid);
        User seller = userRepository.findById(sellerId).orElseThrow();
        when(worldApi.fetchParcel(parcel)).thenReturn(Mono.just(new ParcelMetadata(
                parcel, owner, "agent",
                "Seed Parcel", "Coniston",
                1024, "Seed description", "http://example.com/snap.jpg", "MODERATE",
                128.0, 64.0, 22.0)));
        when(mapApi.resolveRegion(any())).thenReturn(Mono.just(new GridCoordinates(260000.0, 254000.0)));

        mockMvc.perform(post("/api/v1/parcels/lookup")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slParcelUuid\":\"" + parcel + "\"}"))
                .andExpect(status().isOk());

        // Ensure the seller avatar is linked before the verify path runs.
        assertThat(seller.getSlAvatarUuid()).isNotNull();
        return parcelRepository.findBySlParcelUuid(parcel).orElseThrow();
    }
}
