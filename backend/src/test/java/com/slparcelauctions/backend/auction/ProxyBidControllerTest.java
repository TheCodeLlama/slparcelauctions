package com.slparcelauctions.backend.auction;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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
import com.slparcelauctions.backend.auction.broadcast.AuctionBroadcastPublisher;
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
 * HTTP-facing tests for {@link ProxyBidController}. Uses {@code @SpringBootTest}
 * so JWT auth, validation, and the global exception handler are all live.
 *
 * <p>Covers every route / status combination in the spec §4 table plus the
 * auth negatives. Live resolution edge cases (resurrection, tie-flip, counter
 * emission) are pinned in the sibling {@code ProxyBid*IntegrationTest} /
 * {@code BidVsProxyCounterIntegrationTest} classes; here we just assert the
 * HTTP surface behaves as the route table promises.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false"
})
@Transactional
class ProxyBidControllerTest {

    private static final String TRUSTED_OWNER = "00000000-0000-0000-0000-000000000001";

    @Autowired MockMvc mockMvc;
    @Autowired ParcelRepository parcelRepository;
    @Autowired AuctionRepository auctionRepository;
    @Autowired ProxyBidRepository proxyBidRepository;
    @Autowired UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean SlWorldApiClient worldApi;
    @MockitoBean SlMapApiClient mapApi;
    @MockitoBean AuctionBroadcastPublisher broadcastPublisher;

    private String sellerAccessToken;
    private Long sellerId;
    private String bidderAccessToken;
    private Long bidderId;
    private String otherBidderAccessToken;
    private String unverifiedAccessToken;
    private Parcel sellerParcel;

    @BeforeEach
    void setUp() throws Exception {
        sellerAccessToken = registerAndVerifyUser(
                "proxy-seller@example.com", "ProxySeller",
                "11111111-aaaa-bbbb-cccc-000000000201");
        sellerId = userRepository.findByEmail("proxy-seller@example.com").orElseThrow().getId();

        bidderAccessToken = registerAndVerifyUser(
                "proxy-bidder@example.com", "ProxyBidder",
                "22222222-aaaa-bbbb-cccc-000000000202");
        bidderId = userRepository.findByEmail("proxy-bidder@example.com").orElseThrow().getId();

        otherBidderAccessToken = registerAndVerifyUser(
                "proxy-other@example.com", "ProxyOther",
                "33333333-aaaa-bbbb-cccc-000000000203");

        unverifiedAccessToken = registerUser(
                "proxy-unverified@example.com", "ProxyUnverified");

        sellerParcel = seedParcel();
    }

    // -------------------------------------------------------------------------
    // POST
    // -------------------------------------------------------------------------

    @Test
    void post_happyPath_returns201() throws Exception {
        Auction a = seedAuction(AuctionStatus.ACTIVE);

        mockMvc.perform(post("/api/v1/auctions/" + a.getId() + "/proxy-bid")
                        .header("Authorization", "Bearer " + bidderAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxAmount\":5000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maxAmount").value(5000))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void post_duplicateActiveProxy_returns409() throws Exception {
        Auction a = seedAuction(AuctionStatus.ACTIVE);
        createProxy(bidderAccessToken, a.getId(), 5000L);

        mockMvc.perform(post("/api/v1/auctions/" + a.getId() + "/proxy-bid")
                        .header("Authorization", "Bearer " + bidderAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxAmount\":6000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROXY_BID_ALREADY_EXISTS"));
    }

    @Test
    void post_belowStartingBid_returns400() throws Exception {
        Auction a = seedAuction(AuctionStatus.ACTIVE);

        mockMvc.perform(post("/api/v1/auctions/" + a.getId() + "/proxy-bid")
                        .header("Authorization", "Bearer " + bidderAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxAmount\":500}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BID_TOO_LOW"));
    }

    @Test
    void post_asSeller_returns403() throws Exception {
        Auction a = seedAuction(AuctionStatus.ACTIVE);

        mockMvc.perform(post("/api/v1/auctions/" + a.getId() + "/proxy-bid")
                        .header("Authorization", "Bearer " + sellerAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxAmount\":5000}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELLER_CANNOT_BID"));
    }

    @Test
    void post_asUnverified_returns403() throws Exception {
        Auction a = seedAuction(AuctionStatus.ACTIVE);

        mockMvc.perform(post("/api/v1/auctions/" + a.getId() + "/proxy-bid")
                        .header("Authorization", "Bearer " + unverifiedAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxAmount\":5000}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_VERIFIED"));
    }

    @Test
    void post_missingAuction_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/auctions/999999/proxy-bid")
                        .header("Authorization", "Bearer " + bidderAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxAmount\":5000}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUCTION_NOT_FOUND"));
    }

    @Test
    void post_unauthenticated_returns401() throws Exception {
        Auction a = seedAuction(AuctionStatus.ACTIVE);
        mockMvc.perform(post("/api/v1/auctions/" + a.getId() + "/proxy-bid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxAmount\":5000}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void post_missingMaxAmount_returns400() throws Exception {
        Auction a = seedAuction(AuctionStatus.ACTIVE);
        mockMvc.perform(post("/api/v1/auctions/" + a.getId() + "/proxy-bid")
                        .header("Authorization", "Bearer " + bidderAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // PUT
    // -------------------------------------------------------------------------

    @Test
    void put_activeWinning_returns200_silentRaise() throws Exception {
        // Bidder creates proxy (no competitor) → becomes winning at startingBid.
        Auction a = seedAuction(AuctionStatus.ACTIVE);
        createProxy(bidderAccessToken, a.getId(), 5000L);

        mockMvc.perform(put("/api/v1/auctions/" + a.getId() + "/proxy-bid")
                        .header("Authorization", "Bearer " + bidderAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxAmount\":8000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxAmount").value(8000))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void put_onNoProxy_returns404() throws Exception {
        Auction a = seedAuction(AuctionStatus.ACTIVE);
        mockMvc.perform(put("/api/v1/auctions/" + a.getId() + "/proxy-bid")
                        .header("Authorization", "Bearer " + bidderAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxAmount\":5000}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROXY_BID_NOT_FOUND"));
    }

    @Test
    void put_onCancelled_returns409() throws Exception {
        Auction a = seedAuction(AuctionStatus.ACTIVE);
        // Direct repo-level seam: materialize a CANCELLED proxy row without going through
        // the normal DELETE flow. The natural flow cannot reach CANCELLED when the caller
        // isn't winning, because cancel is restricted to ACTIVE rows — DELETE on an
        // EXHAUSTED/CANCELLED proxy returns 404. Seed two proxies so bidder's row lands
        // in EXHAUSTED via the losing path, then flip it to CANCELLED directly. This test
        // pins the PUT-on-CANCELLED behavior; DELETE-while-winning is exercised separately
        // in delete_whileWinning_returns409.
        createProxy(otherBidderAccessToken, a.getId(), 10000L);  // other wins
        createProxy(bidderAccessToken, a.getId(), 5000L);  // bidder exhausted

        ProxyBid p = proxyBidRepository.findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc(
                a.getId(), bidderId).orElseThrow();
        p.setStatus(ProxyBidStatus.CANCELLED);
        proxyBidRepository.save(p);

        mockMvc.perform(put("/api/v1/auctions/" + a.getId() + "/proxy-bid")
                        .header("Authorization", "Bearer " + bidderAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxAmount\":7000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PROXY_STATE"));
    }

    @Test
    void put_activeWinningWithMaxBelowCurrent_returns400() throws Exception {
        Auction a = seedAuction(AuctionStatus.ACTIVE);
        createProxy(bidderAccessToken, a.getId(), 5000L);
        // Bidder is now winning at auction.currentBid = 1000 (startingBid).

        mockMvc.perform(put("/api/v1/auctions/" + a.getId() + "/proxy-bid")
                        .header("Authorization", "Bearer " + bidderAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxAmount\":1000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROXY_MAX"));
    }

    // -------------------------------------------------------------------------
    // DELETE
    // -------------------------------------------------------------------------

    @Test
    void delete_whileWinning_returns409() throws Exception {
        Auction a = seedAuction(AuctionStatus.ACTIVE);
        createProxy(bidderAccessToken, a.getId(), 5000L);
        // bidder is now winning.

        mockMvc.perform(delete("/api/v1/auctions/" + a.getId() + "/proxy-bid")
                        .header("Authorization", "Bearer " + bidderAccessToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_CANCEL_WINNING_PROXY"));
    }

    @Test
    void delete_whileNotWinning_returns204() throws Exception {
        // Other bidder wins (larger proxy). bidder ends up EXHAUSTED, which
        // means their row isn't ACTIVE anymore — so to test DELETE on an
        // ACTIVE non-winning proxy we need a specific setup. We'll bypass the
        // exhaust flow by building it directly: userA creates small proxy
        // then userB creates bigger proxy (userA exhausted). userB cancels
        // not possible as winning. So: rely on the repo-level guarantee —
        // a cancellable proxy by definition is ACTIVE + not winning. Simplest
        // clean case: create proxy, manually move currentBidderId off caller.
        Auction a = seedAuction(AuctionStatus.ACTIVE);
        createProxy(bidderAccessToken, a.getId(), 5000L);
        // Move currentBidderId off bidder to simulate someone else being on top.
        Auction reloaded = auctionRepository.findById(a.getId()).orElseThrow();
        reloaded.setCurrentBidderId(999999L);
        auctionRepository.save(reloaded);

        mockMvc.perform(delete("/api/v1/auctions/" + a.getId() + "/proxy-bid")
                        .header("Authorization", "Bearer " + bidderAccessToken))
                .andExpect(status().isNoContent());

        // Verify status flipped
        ProxyBid p = proxyBidRepository.findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc(
                a.getId(), bidderId).orElseThrow();
        assert p.getStatus() == ProxyBidStatus.CANCELLED;
    }

    @Test
    void delete_onNoProxy_returns404() throws Exception {
        Auction a = seedAuction(AuctionStatus.ACTIVE);
        mockMvc.perform(delete("/api/v1/auctions/" + a.getId() + "/proxy-bid")
                        .header("Authorization", "Bearer " + bidderAccessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROXY_BID_NOT_FOUND"));
    }

    // -------------------------------------------------------------------------
    // GET
    // -------------------------------------------------------------------------

    @Test
    void get_noProxy_returns404() throws Exception {
        Auction a = seedAuction(AuctionStatus.ACTIVE);
        mockMvc.perform(get("/api/v1/auctions/" + a.getId() + "/proxy-bid")
                        .header("Authorization", "Bearer " + bidderAccessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROXY_BID_NOT_FOUND"));
    }

    @Test
    void get_withActiveProxy_returns200() throws Exception {
        Auction a = seedAuction(AuctionStatus.ACTIVE);
        createProxy(bidderAccessToken, a.getId(), 5000L);

        mockMvc.perform(get("/api/v1/auctions/" + a.getId() + "/proxy-bid")
                        .header("Authorization", "Bearer " + bidderAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.maxAmount").value(5000));
    }

    @Test
    void get_withCancelledProxy_returns200() throws Exception {
        // GET returns the latest row regardless of status.
        Auction a = seedAuction(AuctionStatus.ACTIVE);
        createProxy(bidderAccessToken, a.getId(), 5000L);
        ProxyBid p = proxyBidRepository.findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc(
                a.getId(), bidderId).orElseThrow();
        p.setStatus(ProxyBidStatus.CANCELLED);
        proxyBidRepository.save(p);

        mockMvc.perform(get("/api/v1/auctions/" + a.getId() + "/proxy-bid")
                        .header("Authorization", "Bearer " + bidderAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void createProxy(String accessToken, Long auctionId, long maxAmount) throws Exception {
        mockMvc.perform(post("/api/v1/auctions/" + auctionId + "/proxy-bid")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxAmount\":" + maxAmount + "}"))
                .andExpect(status().isCreated());
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
        UUID parcelUuid = UUID.fromString("44444444-4444-4444-4444-000000000204");
        UUID ownerUuid = UUID.fromString("55555555-5555-5555-5555-000000000205");
        when(worldApi.fetchParcel(parcelUuid)).thenReturn(Mono.just(new ParcelMetadata(
                parcelUuid, ownerUuid, "agent",
                "Proxy Parcel", "Coniston",
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
                .verificationTier(VerificationTier.SCRIPT)
                .startingBid(1000L)
                .durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(true)
                .currentBid(0L)
                .bidCount(0)
                .consecutiveWorldApiFailures(0)
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .build();
        OffsetDateTime now = OffsetDateTime.now();
        a.setStartsAt(now.minusHours(1));
        a.setEndsAt(now.plusDays(1));
        a.setOriginalEndsAt(now.plusDays(1));
        return auctionRepository.save(a);
    }
}
