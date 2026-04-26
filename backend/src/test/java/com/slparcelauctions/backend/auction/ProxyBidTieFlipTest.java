package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * Q3 tie-flip regression pin. When a manual bid lands at exactly the
 * competing proxy's maxAmount, the comparison must be strict greater-than
 * ({@code amount > P_max}) — so the proxy is <strong>not</strong> exhausted
 * and instead counters at {@code min(amount + increment, P_max) = P_max}.
 * Both rows are persisted; the proxy's row (emitted LAST) is the
 * post-resolution top. The bid is <strong>not</strong> rejected.
 *
 * <p>A prior implementation used {@code amount >= P_max} which incorrectly
 * exhausted the proxy on ties. This test pins the corrected strict-&gt;
 * semantics so a regression can't land unnoticed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false"
})
@Transactional
class ProxyBidTieFlipTest {

    private static final String TRUSTED_OWNER = "00000000-0000-0000-0000-000000000001";

    @Autowired MockMvc mockMvc;
    @Autowired ParcelRepository parcelRepository;
    @Autowired AuctionRepository auctionRepository;
    @Autowired ProxyBidRepository proxyBidRepository;
    @Autowired BidRepository bidRepository;
    @Autowired UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean SlWorldApiClient worldApi;
    @MockitoBean SlMapApiClient mapApi;
    @MockitoBean AuctionBroadcastPublisher broadcastPublisher;

    private String sellerAccessToken;
    private Long sellerId;
    private String aAccessToken;
    private Long aId;
    private String bAccessToken;
    private Long bId;
    private Parcel sellerParcel;

    @BeforeEach
    void setUp() throws Exception {
        sellerAccessToken = registerAndVerifyUser(
                "tie-seller@example.com", "TieSeller",
                "11111111-aaaa-bbbb-cccc-000000000401");
        sellerId = userRepository.findByEmail("tie-seller@example.com").orElseThrow().getId();

        aAccessToken = registerAndVerifyUser(
                "tie-alice@example.com", "TieAlice",
                "22222222-aaaa-bbbb-cccc-000000000402");
        aId = userRepository.findByEmail("tie-alice@example.com").orElseThrow().getId();

        bAccessToken = registerAndVerifyUser(
                "tie-bob@example.com", "TieBob",
                "33333333-aaaa-bbbb-cccc-000000000403");
        bId = userRepository.findByEmail("tie-bob@example.com").orElseThrow().getId();

        sellerParcel = seedParcel();
    }

    @Test
    void manualBidAtExactlyProxyMax_proxyCountersAndRetains() throws Exception {
        // Step 1 — A creates proxy max=1000. Auction startingBid=500.
        // A opens at 500 and is winning.
        Auction a = seedAuction();
        mockMvc.perform(post("/api/v1/auctions/" + a.getId() + "/proxy-bid")
                        .header("Authorization", "Bearer " + aAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxAmount\":1000}"))
                .andExpect(status().isCreated());

        // Verify A is winning at 500 (the opening bid).
        Auction afterA = auctionRepository.findById(a.getId()).orElseThrow();
        assertThat(afterA.getCurrentBid()).isEqualTo(500L);
        assertThat(afterA.getCurrentBidderId()).isEqualTo(aId);

        // Step 2 — B places a manual bid at exactly 1000 (== A.proxy.max).
        // Strict-> semantics: proxy is NOT exhausted; counter fires.
        // counterAmount = min(1000 + 100, 1000) = 1000. Both rows emitted.
        mockMvc.perform(post("/api/v1/auctions/" + a.getId() + "/bids")
                        .header("Authorization", "Bearer " + bAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000}"))
                .andExpect(status().isCreated());

        // Assertions: A wins the tie; auction.currentBid=1000; A.proxy still ACTIVE.
        Auction afterTie = auctionRepository.findById(a.getId()).orElseThrow();
        assertThat(afterTie.getCurrentBid()).isEqualTo(1000L);
        assertThat(afterTie.getCurrentBidderId()).isEqualTo(aId);

        ProxyBid aProxy = proxyBidRepository.findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc(
                a.getId(), aId).orElseThrow();
        assertThat(aProxy.getStatus()).isEqualTo(ProxyBidStatus.ACTIVE);

        // 2 bid rows from the tie (1 opening PROXY_AUTO for A's create +
        // B's MANUAL + A's PROXY_AUTO counter = 3 total).
        List<Bid> bids = bidRepository.findByAuctionIdOrderByCreatedAtAsc(a.getId());
        assertThat(bids).hasSize(3);

        // Last two: B's MANUAL at 1000 then A's PROXY_AUTO at 1000.
        Bid manual = bids.get(1);
        assertThat(manual.getBidType()).isEqualTo(BidType.MANUAL);
        assertThat(manual.getAmount()).isEqualTo(1000L);
        assertThat(manual.getBidder().getId()).isEqualTo(bId);

        Bid counter = bids.get(2);
        assertThat(counter.getBidType()).isEqualTo(BidType.PROXY_AUTO);
        assertThat(counter.getAmount()).isEqualTo(1000L);
        assertThat(counter.getBidder().getId()).isEqualTo(aId);
        assertThat(counter.getIpAddress()).isNull();
        assertThat(counter.getProxyBidId()).isEqualTo(aProxy.getId());
    }

    @Test
    void manualBidStrictlyAboveProxyMax_exhaustsProxyAndWins() throws Exception {
        // Sanity flip: amount > P_max should exhaust the proxy.
        Auction a = seedAuction();
        mockMvc.perform(post("/api/v1/auctions/" + a.getId() + "/proxy-bid")
                        .header("Authorization", "Bearer " + aAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxAmount\":1000}"))
                .andExpect(status().isCreated());

        // B places 1050 — strictly greater than A's cap of 1000.
        mockMvc.perform(post("/api/v1/auctions/" + a.getId() + "/bids")
                        .header("Authorization", "Bearer " + bAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1050}"))
                .andExpect(status().isCreated());

        Auction after = auctionRepository.findById(a.getId()).orElseThrow();
        assertThat(after.getCurrentBid()).isEqualTo(1050L);
        assertThat(after.getCurrentBidderId()).isEqualTo(bId);

        ProxyBid aProxy = proxyBidRepository.findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc(
                a.getId(), aId).orElseThrow();
        assertThat(aProxy.getStatus()).isEqualTo(ProxyBidStatus.EXHAUSTED);
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

    private Parcel seedParcel() throws Exception {
        UUID parcelUuid = UUID.fromString("44444444-4444-4444-4444-000000000404");
        UUID ownerUuid = UUID.fromString("55555555-5555-5555-5555-000000000405");
        when(worldApi.fetchParcel(parcelUuid)).thenReturn(Mono.just(new ParcelMetadata(
                parcelUuid, ownerUuid, "agent",
                "Tie-flip Parcel", "Coniston",
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

    private Auction seedAuction() {
        User seller = userRepository.findById(sellerId).orElseThrow();
        Auction a = Auction.builder()
                .title("Test listing")
                .parcel(sellerParcel)
                .seller(seller)
                .status(AuctionStatus.ACTIVE)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .verificationTier(VerificationTier.SCRIPT)
                .startingBid(500L)
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
