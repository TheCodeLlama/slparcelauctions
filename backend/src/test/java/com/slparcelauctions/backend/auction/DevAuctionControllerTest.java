package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * Full-stack coverage for the dev-profile-only listing-fee payment stub
 * ({@code POST /api/v1/dev/auctions/{id}/pay}).
 *
 * <p>Runs under {@code @ActiveProfiles("dev")} so the {@code @Profile("dev")}
 * bean is wired in. Mirrors {@code AuctionControllerIntegrationTest}'s
 * scaffolding for registering verified users and seeding auctions.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false"
})
@Transactional
class DevAuctionControllerTest {

    private static final String TRUSTED_OWNER = "00000000-0000-0000-0000-000000000001";

    @Autowired MockMvc mockMvc;
    @Autowired ParcelRepository parcelRepository;
    @Autowired AuctionRepository auctionRepository;
    @Autowired UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean SlWorldApiClient worldApi;
    @MockitoBean SlMapApiClient mapApi;

    private String sellerAccessToken;
    private Long sellerId;

    private String otherAccessToken;

    private Parcel sellerParcel;

    @BeforeEach
    void setUp() throws Exception {
        sellerAccessToken = registerAndVerifyUser(
                "dev-pay-seller@example.com", "Seller",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        sellerId = userRepository.findByEmail("dev-pay-seller@example.com").orElseThrow().getId();

        otherAccessToken = registerAndVerifyUser(
                "dev-pay-other@example.com", "Other",
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        sellerParcel = seedParcel();
    }

    @Test
    void pay_onDraft_returns200_andTransitionsToDraftPaid_withDefaults() throws Exception {
        Auction a = seedAuction(AuctionStatus.DRAFT);

        mockMvc.perform(post("/api/v1/dev/auctions/" + a.getId() + "/pay")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT_PAID"))
                .andExpect(jsonPath("$.listingFeePaid").value(true))
                .andExpect(jsonPath("$.listingFeeAmt").value(100))
                .andExpect(jsonPath("$.listingFeeTxn").value(
                        org.hamcrest.Matchers.startsWith("dev-mock-")))
                .andExpect(jsonPath("$.listingFeePaidAt").exists());

        Auction refreshed = auctionRepository.findById(a.getId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(AuctionStatus.DRAFT_PAID);
        assertThat(refreshed.getListingFeePaid()).isTrue();
        assertThat(refreshed.getListingFeeAmt()).isEqualTo(100L);
        assertThat(refreshed.getListingFeeTxn()).startsWith("dev-mock-");
        assertThat(refreshed.getListingFeePaidAt()).isNotNull();
    }

    @Test
    void pay_onDraftPaid_returns409() throws Exception {
        Auction a = seedAuction(AuctionStatus.DRAFT_PAID);

        mockMvc.perform(post("/api/v1/dev/auctions/" + a.getId() + "/pay")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUCTION_INVALID_STATE"))
                .andExpect(jsonPath("$.currentState").value("DRAFT_PAID"))
                .andExpect(jsonPath("$.attemptedAction").value("DEV_PAY"));
    }

    @Test
    void pay_onActive_returns409() throws Exception {
        Auction a = seedAuction(AuctionStatus.ACTIVE);

        mockMvc.perform(post("/api/v1/dev/auctions/" + a.getId() + "/pay")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUCTION_INVALID_STATE"))
                .andExpect(jsonPath("$.currentState").value("ACTIVE"));
    }

    @Test
    void pay_asNonSeller_returns404() throws Exception {
        Auction a = seedAuction(AuctionStatus.DRAFT);

        mockMvc.perform(post("/api/v1/dev/auctions/" + a.getId() + "/pay")
                .header("Authorization", "Bearer " + otherAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUCTION_NOT_FOUND"));
    }

    @Test
    void pay_withCustomBody_persistsExactValues() throws Exception {
        Auction a = seedAuction(AuctionStatus.DRAFT);

        mockMvc.perform(post("/api/v1/dev/auctions/" + a.getId() + "/pay")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":500,\"txnRef\":\"my-ref\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT_PAID"))
                .andExpect(jsonPath("$.listingFeeAmt").value(500))
                .andExpect(jsonPath("$.listingFeeTxn").value("my-ref"));

        Auction refreshed = auctionRepository.findById(a.getId()).orElseThrow();
        assertThat(refreshed.getListingFeeAmt()).isEqualTo(500L);
        assertThat(refreshed.getListingFeeTxn()).isEqualTo("my-ref");
    }

    @Test
    void pay_withoutAuth_returns403() throws Exception {
        // /api/v1/dev/** is permitAll at the security layer (the @Profile("dev") gate is the
        // real trust boundary), so the JWT filter does not return 401 here. The controller
        // throws AccessDeniedException on a null principal, which the global handler maps to
        // 403 with code=ACCESS_DENIED.
        Auction a = seedAuction(AuctionStatus.DRAFT);

        mockMvc.perform(post("/api/v1/dev/auctions/" + a.getId() + "/pay")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    // ------------------------------------------------------------------
    // Helpers (mirrors AuctionControllerIntegrationTest)
    // ------------------------------------------------------------------

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
        UUID parcelUuid = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID ownerUuid = UUID.fromString("44444444-4444-4444-4444-444444444444");
        when(worldApi.fetchParcel(parcelUuid)).thenReturn(Mono.just(new ParcelMetadata(
                parcelUuid, ownerUuid, "agent",
                "Seed Parcel", "Coniston",
                1024, "Seed description", "http://example.com/snap.jpg", "MODERATE",
                128.0, 64.0, 22.0)));
        when(mapApi.resolveRegion(any())).thenReturn(Mono.just(new GridCoordinates(260000.0, 254000.0)));

        mockMvc.perform(post("/api/v1/parcels/lookup")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slParcelUuid\":\"" + parcelUuid + "\"}"))
                .andExpect(status().isOk());

        return parcelRepository.findBySlParcelUuid(parcelUuid).orElseThrow();
    }

    private Auction seedAuction(AuctionStatus status) {
        User seller = userRepository.findById(sellerId).orElseThrow();
        Auction a = Auction.builder()
                .title("Test listing")
                .parcel(sellerParcel)
                .seller(seller)
                .status(status)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .startingBid(1000L)
                .durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(status == AuctionStatus.DRAFT_PAID)
                .currentBid(0L)
                .bidCount(0)
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
}
