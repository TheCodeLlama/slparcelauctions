package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * Pin test for the EXHAUSTED → ACTIVE resurrection branch of
 * {@code PUT /proxy-bid}. Scenario:
 *
 * <ol>
 *   <li>User A creates proxy max=600 on a startingBid=500 auction. No
 *       competitor — A opens at 500 and is winning.</li>
 *   <li>User B creates proxy max=1000. Branch 2 fires: A's proxy is
 *       exhausted, B wins at {@code min(600 + 50, 1000) = 650}.</li>
 *   <li>User A PUTs max=2000. Resurrection: A flips to ACTIVE, runs
 *       resolution. Branch 2 again — A's new max (2000) &gt; B's max (1000),
 *       so B is exhausted and A wins at {@code min(1000 + 100, 2000) = 1100}
 *       (tier-break: currentBid=1000 → L$100 increment).</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false"
})
@Transactional
class ProxyBidResurrectionTest {

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
                "res-seller@example.com", "ResSeller",
                "11111111-aaaa-bbbb-cccc-000000000301");
        sellerId = userRepository.findByEmail("res-seller@example.com").orElseThrow().getId();

        aAccessToken = registerAndVerifyUser(
                "res-alice@example.com", "ResAlice",
                "22222222-aaaa-bbbb-cccc-000000000302");
        aId = userRepository.findByEmail("res-alice@example.com").orElseThrow().getId();

        bAccessToken = registerAndVerifyUser(
                "res-bob@example.com", "ResBob",
                "33333333-aaaa-bbbb-cccc-000000000303");
        bId = userRepository.findByEmail("res-bob@example.com").orElseThrow().getId();

        sellerParcel = seedParcel();
    }

    @Test
    void exhaustedProxy_whenMaxIncreased_resurrectsAndWins() throws Exception {
        Auction a = seedAuction(500L);

        // Step 1 — A creates proxy max=600 → opens at 500, A winning.
        mockMvc.perform(post("/api/v1/auctions/" + a.getId() + "/proxy-bid")
                        .header("Authorization", "Bearer " + aAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxAmount\":600}"))
                .andExpect(status().isCreated());

        Auction afterA = auctionRepository.findById(a.getId()).orElseThrow();
        assertThat(afterA.getCurrentBid()).isEqualTo(500L);
        assertThat(afterA.getCurrentBidderId()).isEqualTo(aId);

        // Step 2 — B creates proxy max=1000. A exhausted, B wins at 650.
        mockMvc.perform(post("/api/v1/auctions/" + a.getId() + "/proxy-bid")
                        .header("Authorization", "Bearer " + bAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxAmount\":1000}"))
                .andExpect(status().isCreated());

        Auction afterB = auctionRepository.findById(a.getId()).orElseThrow();
        assertThat(afterB.getCurrentBid()).isEqualTo(650L);
        assertThat(afterB.getCurrentBidderId()).isEqualTo(bId);
        ProxyBid aProxy = proxyBidRepository.findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc(
                a.getId(), aId).orElseThrow();
        assertThat(aProxy.getStatus()).isEqualTo(ProxyBidStatus.EXHAUSTED);

        // Step 3 — A PUTs max=2000 → resurrects. A's new max (2000) > B's (1000).
        // settleAmount = min(1000 + 100, 2000) = 1100. A wins at 1100.
        mockMvc.perform(put("/api/v1/auctions/" + a.getId() + "/proxy-bid")
                        .header("Authorization", "Bearer " + aAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxAmount\":2000}"))
                .andExpect(status().isOk());

        Auction afterResurrection = auctionRepository.findById(a.getId()).orElseThrow();
        assertThat(afterResurrection.getCurrentBid()).isEqualTo(1100L);
        assertThat(afterResurrection.getCurrentBidderId()).isEqualTo(aId);

        ProxyBid aResurrected = proxyBidRepository.findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc(
                a.getId(), aId).orElseThrow();
        assertThat(aResurrected.getStatus()).isEqualTo(ProxyBidStatus.ACTIVE);
        assertThat(aResurrected.getMaxAmount()).isEqualTo(2000L);

        ProxyBid bAfter = proxyBidRepository.findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc(
                a.getId(), bId).orElseThrow();
        assertThat(bAfter.getStatus()).isEqualTo(ProxyBidStatus.EXHAUSTED);

        // Verify the emitted PROXY_AUTO row exists.
        List<Bid> bids = bidRepository.findByAuctionIdOrderByCreatedAtAsc(a.getId());
        Bid last = bids.get(bids.size() - 1);
        assertThat(last.getAmount()).isEqualTo(1100L);
        assertThat(last.getBidType()).isEqualTo(BidType.PROXY_AUTO);
        assertThat(last.getBidder().getId()).isEqualTo(aId);
        assertThat(last.getIpAddress()).isNull();
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
        UUID parcelUuid = UUID.fromString("44444444-4444-4444-4444-000000000304");
        UUID ownerUuid = UUID.fromString("55555555-5555-5555-5555-000000000305");
        when(worldApi.fetchParcel(parcelUuid)).thenReturn(Mono.just(new ParcelMetadata(
                parcelUuid, ownerUuid, "agent",
                "Resurrection Parcel", "Coniston",
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

    private Auction seedAuction(long startingBid) {
        User seller = userRepository.findById(sellerId).orElseThrow();
        Auction a = Auction.builder()
                .title("Test listing")
                .parcel(sellerParcel)
                .seller(seller)
                .status(AuctionStatus.ACTIVE)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .verificationTier(VerificationTier.SCRIPT)
                .startingBid(startingBid)
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
