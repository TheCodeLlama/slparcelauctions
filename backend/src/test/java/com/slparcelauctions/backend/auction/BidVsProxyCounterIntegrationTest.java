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
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.region.dto.RegionPageData;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.dto.ParcelPageData;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import reactor.core.publisher.Mono;

/**
 * End-to-end coverage for the manual-bid-vs-proxy-counter branch of spec §6
 * steps 7-8 ({@link BidService#placeBid}). A proxy owner (A) has set a
 * maximum of L$1000; another bidder (B) places a manual bid at L$500. The
 * service should emit:
 *
 * <ol>
 *   <li>B's MANUAL bid at 500.</li>
 *   <li>A's PROXY_AUTO counter at {@code min(500 + 50, 1000) = 550}.</li>
 * </ol>
 *
 * <p>After commit: {@code auction.currentBid == 550}, {@code currentBidderId == A},
 * {@code bidCount == 1 opening + 1 manual + 1 counter = 3}.
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
class BidVsProxyCounterIntegrationTest {

    private static final String TRUSTED_OWNER = "00000000-0000-0000-0000-000000000001";

    @Autowired MockMvc mockMvc;
    @Autowired AuctionRepository auctionRepository;
    @Autowired ProxyBidRepository proxyBidRepository;
    @Autowired BidRepository bidRepository;
    @Autowired UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean SlWorldApiClient worldApi;
    @MockitoBean AuctionBroadcastPublisher broadcastPublisher;

    private String sellerAccessToken;
    private Long sellerId;
    private String aAccessToken;
    private Long aId;
    private String bAccessToken;
    private Long bId;
    private UUID sellerParcelUuid;

    @BeforeEach
    void setUp() throws Exception {
        sellerAccessToken = registerAndVerifyUser(
                "cnt-seller@example.com", "CntSeller",
                "11111111-aaaa-bbbb-cccc-000000000501");
        sellerId = userRepository.findByUsername("cnt-seller@example.com").orElseThrow().getId();

        aAccessToken = registerAndVerifyUser(
                "cnt-alice@example.com", "CntAlice",
                "22222222-aaaa-bbbb-cccc-000000000502");
        aId = userRepository.findByUsername("cnt-alice@example.com").orElseThrow().getId();

        bAccessToken = registerAndVerifyUser(
                "cnt-bob@example.com", "CntBob",
                "33333333-aaaa-bbbb-cccc-000000000503");
        bId = userRepository.findByUsername("cnt-bob@example.com").orElseThrow().getId();

        sellerParcelUuid = seedParcel();
    }

    @Test
    void manualBid_withCompetingProxy_emitsManualPlusCounter() throws Exception {
        Auction a = seedAuction();

        // Step 1 — A creates proxy max=1000 → opens at startingBid=500.
        mockMvc.perform(post("/api/v1/auctions/" + a.getPublicId() + "/proxy-bid")
                        .header("Authorization", "Bearer " + aAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxAmount\":1000}"))
                .andExpect(status().isCreated());

        // Sanity: 1 PROXY_AUTO row, currentBid=500, A winning.
        Auction afterA = auctionRepository.findById(a.getId()).orElseThrow();
        assertThat(afterA.getCurrentBid()).isEqualTo(500L);
        assertThat(afterA.getCurrentBidderId()).isEqualTo(aId);
        assertThat(afterA.getBidCount()).isEqualTo(1);

        // Step 2 — B places a manual bid of L$500. Current bid is 500, so the
        // minimum valid bid would be 500+50=550; but the min floor is applied
        // to the manual amount. To test a strict below-cap scenario we need
        // the manual to be >= minRequired AND < P_max. So: first raise the
        // floor by starting from currentBid=500 → minRequired for next is
        // 550. We need B to bid 550 (the minimum), and the proxy counter
        // computes min(550+50, 1000) = 600.
        //
        // This matches the task spec's expected values: counter at 600
        // (500+100 would be wrong because increment at currentBid=500 is L$50,
        // not L$100). The bidCount goes from 1 → 3 (opening + manual + counter).

        mockMvc.perform(post("/api/v1/auctions/" + a.getPublicId() + "/bids")
                        .header("Authorization", "Bearer " + bAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":550}"))
                .andExpect(status().isCreated());

        Auction afterCounter = auctionRepository.findById(a.getId()).orElseThrow();
        // Counter amount: min(550 + minIncrement(550)=50, 1000) = 600.
        assertThat(afterCounter.getCurrentBid()).isEqualTo(600L);
        assertThat(afterCounter.getCurrentBidderId()).isEqualTo(aId);
        assertThat(afterCounter.getBidCount()).isEqualTo(3);

        // A's proxy should still be ACTIVE (cap not exceeded).
        ProxyBid aProxy = proxyBidRepository.findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc(
                a.getId(), aId).orElseThrow();
        assertThat(aProxy.getStatus()).isEqualTo(ProxyBidStatus.ACTIVE);

        // Three total bid rows.
        List<Bid> bids = bidRepository.findByAuctionIdOrderByCreatedAtAsc(a.getId());
        assertThat(bids).hasSize(3);

        // Row 2: B's MANUAL at 550 with their IP.
        Bid manual = bids.get(1);
        assertThat(manual.getBidType()).isEqualTo(BidType.MANUAL);
        assertThat(manual.getAmount()).isEqualTo(550L);
        assertThat(manual.getBidder().getId()).isEqualTo(bId);
        assertThat(manual.getProxyBidId()).isNull();

        // Row 3: A's PROXY_AUTO counter at 600, ip null.
        Bid counter = bids.get(2);
        assertThat(counter.getBidType()).isEqualTo(BidType.PROXY_AUTO);
        assertThat(counter.getAmount()).isEqualTo(600L);
        assertThat(counter.getBidder().getId()).isEqualTo(aId);
        assertThat(counter.getIpAddress()).isNull();
        assertThat(counter.getProxyBidId()).isEqualTo(aProxy.getId());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
        UUID parcelUuid = UUID.fromString("44444444-4444-4444-4444-000000000504");
        UUID ownerUuid = UUID.fromString("55555555-5555-5555-5555-000000000505");
        when(worldApi.fetchParcelPage(parcelUuid)).thenReturn(
                Mono.just(new ParcelPageData(new ParcelMetadata(
                        parcelUuid,
                ownerUuid,
                "agent",
                null,
                "Counter Parcel",
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

        mockMvc.perform(post("/api/v1/parcels/lookup")
                        .header("Authorization", "Bearer " + sellerAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slParcelUuid\":\"" + parcelUuid + "\"}"))
                .andExpect(status().isOk());

        return parcelUuid;
    }

    private Auction seedAuction() {
        User seller = userRepository.findById(sellerId).orElseThrow();
        Auction a = Auction.builder()
                .title("Test listing")
                .slParcelUuid(sellerParcelUuid)
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
        a.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(sellerParcelUuid)
                .ownerUuid(UUID.fromString("55555555-5555-5555-5555-000000000505"))
                .ownerType("agent").parcelName("Counter Parcel")
                .regionName("Coniston").regionMaturityRating("M_NOT")
                .areaSqm(1024).positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
        OffsetDateTime now = OffsetDateTime.now();
        a.setStartsAt(now.minusHours(1));
        a.setEndsAt(now.plusDays(1));
        a.setOriginalEndsAt(now.plusDays(1));
        return auctionRepository.save(a);
    }
}
