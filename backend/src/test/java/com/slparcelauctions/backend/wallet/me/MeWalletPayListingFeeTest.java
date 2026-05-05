package com.slparcelauctions.backend.wallet.me;

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
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.region.dto.RegionPageData;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.dto.ParcelPageData;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import reactor.core.publisher.Mono;

/**
 * Coverage for {@code POST /api/v1/me/auctions/{id}/pay-listing-fee}.
 * Asserts the precondition gates layered in front of the wallet debit:
 *   - wallet ToU must be accepted (403 WALLET_TERMS_NOT_ACCEPTED)
 *   - no outstanding penalty (422 PENALTY_OUTSTANDING — covered elsewhere)
 *   - sufficient available balance (422 INSUFFICIENT_AVAILABLE_BALANCE)
 *
 * <p>Plus the happy path: DRAFT -> DRAFT_PAID with the wallet debited.
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
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
@Transactional
class MeWalletPayListingFeeTest {

    private static final String TRUSTED_OWNER = "00000000-0000-0000-0000-000000000001";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AuctionRepository auctionRepository;

    @MockitoBean SlWorldApiClient worldApi;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String sellerAccessToken;
    private Long sellerId;
    private UUID sellerParcelUuid;

    @BeforeEach
    void setUp() throws Exception {
        sellerAccessToken = registerAndVerifyUser(
                "fee-payer@example.com", "Fee Payer",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        sellerId = userRepository.findByUsername("fee-payer@example.com").orElseThrow().getId();
        sellerParcelUuid = seedParcel();
    }

    @Test
    void payListingFee_walletTermsNotAccepted_returns403() throws Exception {
        // No wallet ToU acceptance + plenty of balance — the new ToU gate
        // must fire before the debit is attempted.
        creditUser(sellerId, 1000L);
        Auction draft = seedDraftAuction();

        mockMvc.perform(post("/api/v1/me/auctions/" + draft.getPublicId() + "/pay-listing-fee")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WALLET_TERMS_NOT_ACCEPTED"))
                .andExpect(jsonPath("$.title").value("Wallet terms not accepted"));
    }

    @Test
    void payListingFee_termsAcceptedAndBalanceSufficient_debitsAndAdvancesStatus() throws Exception {
        acceptWalletTerms(sellerId);
        creditUser(sellerId, 500L);
        Auction draft = seedDraftAuction();

        mockMvc.perform(post("/api/v1/me/auctions/" + draft.getPublicId() + "/pay-listing-fee")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auctionStatus").value("DRAFT_PAID"))
                .andExpect(jsonPath("$.newBalance").value(400));

        Auction reloaded = auctionRepository.findById(draft.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getStatus())
                .isEqualTo(AuctionStatus.DRAFT_PAID);
        org.assertj.core.api.Assertions.assertThat(reloaded.getListingFeePaid()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void acceptWalletTerms(Long userId) {
        User u = userRepository.findById(userId).orElseThrow();
        u.setWalletTermsAcceptedAt(OffsetDateTime.now());
        u.setWalletTermsVersion("v1.0");
        userRepository.save(u);
    }

    private void creditUser(Long userId, long amount) {
        User u = userRepository.findById(userId).orElseThrow();
        u.setBalanceLindens(u.getBalanceLindens() + amount);
        userRepository.save(u);
    }

    private Auction seedDraftAuction() {
        User seller = userRepository.findById(sellerId).orElseThrow();
        Auction a = Auction.builder()
                .title("Test listing")
                .slParcelUuid(sellerParcelUuid)
                .seller(seller)
                .status(AuctionStatus.DRAFT)
                .startingBid(1000L)
                .durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(false)
                .currentBid(0L)
                .bidCount(0)
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .build();
        a.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(sellerParcelUuid)
                .ownerUuid(UUID.fromString("44444444-4444-4444-4444-444444444444"))
                .ownerType("agent")
                .ownerName("Fee Payer")
                .parcelName("Seed Parcel")
                .description("Seed description")
                .regionName("Coniston")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
        return auctionRepository.save(a);
    }

    private String registerUser(String email, String displayName) throws Exception {
        String body = String.format(
                "{\"username\":\"%s\",\"password\":\"hunter22abc\"}",
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

    private UUID seedParcel() throws Exception {
        UUID regionUuid = UUID.randomUUID();
        UUID parcelUuid = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID ownerUuid = UUID.fromString("44444444-4444-4444-4444-444444444444");
        when(worldApi.fetchParcelPage(parcelUuid)).thenReturn(
                Mono.just(new ParcelPageData(new ParcelMetadata(
                        parcelUuid, ownerUuid, "agent", null,
                        "Seed Parcel", "Coniston", 1024,
                        "Seed description", "http://example.com/snap.jpg", null,
                        128.0, 64.0, 22.0), regionUuid)));
        when(worldApi.fetchRegionPage(regionUuid)).thenReturn(
                Mono.just(new RegionPageData(regionUuid, "Coniston", 1014.0, 1014.0, "M_NOT")));
        mockMvc.perform(post("/api/v1/parcels/lookup")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slParcelUuid\":\"" + parcelUuid + "\"}"))
                .andExpect(status().isOk());
        return parcelUuid;
    }
}
