package com.slparcelauctions.backend.bot;

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
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.sl.SlMapApiClient;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.GridCoordinates;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.user.UserRepository;

import reactor.core.publisher.Mono;

/**
 * End-to-end integration test for {@link DevBotTaskController}, the dev-profile
 * stand-in for the SL bot worker. Confirms the {@code /dev/} route delegates
 * to the same {@link BotTaskService#complete} path as the production
 * endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "auth.cleanup.enabled=false")
@Transactional
class DevBotTaskControllerTest {

    private static final String TRUSTED_OWNER = "00000000-0000-0000-0000-000000000001";
    private static final String ESCROW_UUID = "00000000-0000-0000-0000-000000000099";
    private static final long SENTINEL_PRICE = 999999999L;

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

    @BeforeEach
    void setUp() throws Exception {
        sellerAvatarUuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        sellerAccessToken = registerAndVerifyUser(
                "dev-bot-seller@example.com", "DevBotSeller", sellerAvatarUuid);
        sellerId = userRepository.findByEmail("dev-bot-seller@example.com").orElseThrow().getId();
        sellerParcel = seedParcel();
    }

    @Test
    void devComplete_success_transitionsAuctionToActive() throws Exception {
        Long auctionId = createAndPayAuction();
        Long botTaskId = triggerVerify(auctionId);

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

        Auction updated = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(updated.getVerificationTier()).isEqualTo(VerificationTier.BOT);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Long createAndPayAuction() throws Exception {
        String body = String.format("""
            {
              "parcelId":%d,
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

    private Long triggerVerify(Long auctionId) throws Exception {
        MvcResult res = mockMvc.perform(put("/api/v1/auctions/" + auctionId + "/verify")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"SALE_TO_BOT\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .get("pendingVerification").get("botTaskId").asLong();
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
        when(worldApi.fetchParcel(parcel)).thenReturn(Mono.just(new ParcelMetadata(
                parcel, owner, "agent",
                "Seed Parcel", "Coniston",
                1024, "Seed description", "http://example.com/snap.jpg", "MATURE",
                128.0, 64.0, 22.0)));
        when(mapApi.resolveRegion(any())).thenReturn(Mono.just(new GridCoordinates(260000.0, 254000.0)));

        mockMvc.perform(post("/api/v1/parcels/lookup")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slParcelUuid\":\"" + parcel + "\"}"))
                .andExpect(status().isOk());

        return parcelRepository.findBySlParcelUuid(parcel).orElseThrow();
    }
}
