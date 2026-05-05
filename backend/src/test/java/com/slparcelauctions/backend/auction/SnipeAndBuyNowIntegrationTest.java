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
import com.slparcelauctions.backend.auction.broadcast.AuctionBroadcastPublisher;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.region.dto.RegionPageData;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.dto.ParcelPageData;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import reactor.core.publisher.Mono;

/**
 * Full-stack integration coverage for snipe-protection stacking and inline
 * buy-it-now. Three scenarios mirror the spec §6 example and its edge
 * cases:
 *
 * <ol>
 *   <li>Snipe stacking across multiple placements — simulate time passage
 *       by mutating {@code auction.endsAt} between placements rather than
 *       standing up a mutable test clock mid-transaction.</li>
 *   <li>Inline buy-it-now — first bid at {@code buyNowPrice} closes the
 *       auction; a subsequent bid attempt returns 409 AUCTION_NOT_ACTIVE.</li>
 *   <li>Snipe + buy-now together — buy-now takes precedence; any snipe
 *       extension that may have fired is irrelevant because the auction is
 *       already ENDED.</li>
 * </ol>
 *
 * <p>Time manipulation: the service reads {@code now} from the injected
 * production {@link java.time.Clock}; we simulate time passage by editing
 * the auction's {@code endsAt} between placements. This models the
 * real-world condition (auction now has less time remaining than before)
 * without needing a mutable test clock or Thread.sleep.
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
class SnipeAndBuyNowIntegrationTest {

    private static final String TRUSTED_OWNER = "00000000-0000-0000-0000-000000000001";

    @Autowired MockMvc mockMvc;
    @Autowired AuctionRepository auctionRepository;
    @Autowired BidRepository bidRepository;
    @Autowired UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean SlWorldApiClient worldApi;
    @MockitoBean AuctionBroadcastPublisher broadcastPublisher;

    private String sellerAccessToken;
    private Long sellerId;
    private String bidder1AccessToken;
    private Long bidder1Id;
    private String bidder2AccessToken;
    private Long bidder2Id;
    private UUID sellerParcelUuid;

    @BeforeEach
    void setUp() throws Exception {
        sellerAccessToken = registerAndVerifyUser(
                "snipe-seller@example.com", "SnipeSeller",
                "11111111-aaaa-bbbb-cccc-000000000101");
        sellerId = userRepository.findByUsername("snipe-seller@example.com").orElseThrow().getId();

        bidder1AccessToken = registerAndVerifyUser(
                "snipe-bidder-1@example.com", "SnipeBidderOne",
                "22222222-aaaa-bbbb-cccc-000000000102");
        bidder1Id = userRepository.findByUsername("snipe-bidder-1@example.com").orElseThrow().getId();

        bidder2AccessToken = registerAndVerifyUser(
                "snipe-bidder-2@example.com", "SnipeBidderTwo",
                "33333333-aaaa-bbbb-cccc-000000000103");
        bidder2Id = userRepository.findByUsername("snipe-bidder-2@example.com").orElseThrow().getId();

        sellerParcelUuid = seedParcel();
    }

    // ------------------------------------------------------------------
    // Scenario 1 — snipe stacking across multiple placements
    // ------------------------------------------------------------------

    @Test
    void snipeStacks_acrossMultiplePlacements() throws Exception {
        // Window = 10min. First placement at endsAt=now+15min is outside
        // the window (15 > 10) so should not extend.
        OffsetDateTime now = OffsetDateTime.now();
        Auction auction = seedAuction(AuctionStatus.ACTIVE,
                /* currentBid */ 0L, /* bidCount */ 0,
                /* snipeProtect */ true, /* snipeWindowMin */ 10,
                /* endsAt */ now.plusMinutes(15),
                /* buyNowPrice */ null);
        OffsetDateTime firstEndsAt = auction.getEndsAt();

        // Placement 1 — outside window, no extension.
        mockMvc.perform(post("/api/v1/auctions/" + auction.getPublicId() + "/bids")
                        .header("Authorization", "Bearer " + bidder1AccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.snipeExtensionMinutes").doesNotExist());

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getEndsAt()).isEqualTo(firstEndsAt);

        // Simulate time passage — move endsAt back by 6min so there are
        // now only 9min remaining (inside the 10min window).
        reloaded.setEndsAt(OffsetDateTime.now().plusMinutes(9));
        auctionRepository.save(reloaded);

        // Placement 2 — inside window, should extend. bidder2 takes over
        // (avoids seller/self-outbid edge cases; increment L$1000→L$100).
        mockMvc.perform(post("/api/v1/auctions/" + auction.getPublicId() + "/bids")
                        .header("Authorization", "Bearer " + bidder2AccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1100}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.snipeExtensionMinutes").value(10));

        reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        OffsetDateTime afterSecond = reloaded.getEndsAt();
        // Extension is bid.createdAt + 10min — should be roughly NOW + 10min
        // (the test executes within a few ms so a 9min lower bound holds).
        assertThat(afterSecond).isAfter(OffsetDateTime.now().plusMinutes(9));

        // Simulate time passage again — pull endsAt inside the 10min window
        // again. Setting endsAt=now+5min means a third bid placed "now"
        // has 5min of remaining time, which is inside the 10min window.
        reloaded.setEndsAt(OffsetDateTime.now().plusMinutes(5));
        auctionRepository.save(reloaded);
        OffsetDateTime beforeThird = reloaded.getEndsAt();

        // Placement 3 — still in window, stacks again. Increment remains
        // L$100 (currentBid=1100 is still in the L$1000-L$9999 tier).
        mockMvc.perform(post("/api/v1/auctions/" + auction.getPublicId() + "/bids")
                        .header("Authorization", "Bearer " + bidder1AccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1200}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.snipeExtensionMinutes").value(10));

        reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        // After the third bid, endsAt should be further out than it was
        // just before the third placement (the stack moved forward).
        assertThat(reloaded.getEndsAt()).isAfter(beforeThird);

        // Each extending bid row carries its own stamp.
        var bids = bidRepository.findByAuctionIdOrderByCreatedAtAsc(auction.getId());
        assertThat(bids).hasSize(3);
        assertThat(bids.get(0).getSnipeExtensionMinutes()).isNull();
        assertThat(bids.get(1).getSnipeExtensionMinutes()).isEqualTo(10);
        assertThat(bids.get(2).getSnipeExtensionMinutes()).isEqualTo(10);
    }

    // ------------------------------------------------------------------
    // Scenario 2 — inline buy-it-now
    // ------------------------------------------------------------------

    @Test
    void buyNow_endsAuctionInline_andRejectsSubsequentBid() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Auction auction = seedAuction(AuctionStatus.ACTIVE,
                0L, 0, /* snipeProtect */ true, /* snipeWindowMin */ 15,
                now.plusHours(1), /* buyNowPrice */ 10_000L);

        // First bid hits the buyNowPrice exactly — closes the auction.
        mockMvc.perform(post("/api/v1/auctions/" + auction.getPublicId() + "/bids")
                        .header("Authorization", "Bearer " + bidder1AccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.buyNowTriggered").value(true));

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.ENDED);
        assertThat(reloaded.getEndOutcome()).isEqualTo(AuctionEndOutcome.BOUGHT_NOW);
        assertThat(reloaded.getWinnerUserId()).isEqualTo(bidder1Id);
        assertThat(reloaded.getFinalBidAmount()).isEqualTo(10_000L);
        assertThat(reloaded.getEndedAt()).isNotNull();

        // Subsequent bid must be rejected with 409 AUCTION_NOT_ACTIVE — the
        // service hits the status != ACTIVE gate before any other check.
        mockMvc.perform(post("/api/v1/auctions/" + auction.getPublicId() + "/bids")
                        .header("Authorization", "Bearer " + bidder2AccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":15000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUCTION_NOT_ACTIVE"))
                .andExpect(jsonPath("$.currentState").value("ENDED"));
    }

    // ------------------------------------------------------------------
    // Scenario 3 — snipe + buy-now precedence
    // ------------------------------------------------------------------

    @Test
    void buyNowTakesPrecedence_evenWhenBidInsideSnipeWindow() throws Exception {
        // Snipe window = 15min; endsAt = now + 5min → bid is inside the
        // window. Buy-now price matches bid exactly. The snipe loop runs
        // first and may extend endsAt, but the buy-now loop then flips
        // status=ENDED so the extension is irrelevant to the outcome.
        OffsetDateTime now = OffsetDateTime.now();
        Auction auction = seedAuction(AuctionStatus.ACTIVE,
                0L, 0, /* snipeProtect */ true, /* snipeWindowMin */ 15,
                now.plusMinutes(5), /* buyNowPrice */ 10_000L);

        mockMvc.perform(post("/api/v1/auctions/" + auction.getPublicId() + "/bids")
                        .header("Authorization", "Bearer " + bidder1AccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.buyNowTriggered").value(true));

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.ENDED);
        assertThat(reloaded.getEndOutcome()).isEqualTo(AuctionEndOutcome.BOUGHT_NOW);
        assertThat(reloaded.getWinnerUserId()).isEqualTo(bidder1Id);
        assertThat(reloaded.getFinalBidAmount()).isEqualTo(10_000L);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

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
        UUID parcelUuid = UUID.fromString("44444444-4444-4444-4444-000000000104");
        UUID ownerUuid = UUID.fromString("55555555-5555-5555-5555-000000000105");
        when(worldApi.fetchParcelPage(parcelUuid)).thenReturn(
                Mono.just(new ParcelPageData(new ParcelMetadata(
                        parcelUuid,
                ownerUuid,
                "agent",
                null,
                "Snipe Parcel",
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
        return parcelUuid;
    }

    private Auction seedAuction(AuctionStatus status, long currentBid, int bidCount,
                                boolean snipeProtect, Integer snipeWindowMin,
                                OffsetDateTime endsAt, Long buyNowPrice) {
        User seller = userRepository.findById(sellerId).orElseThrow();
        UUID ownerUuid = UUID.fromString("55555555-5555-5555-5555-000000000105");
        Auction a = Auction.builder()
                .title("Test listing")
                .slParcelUuid(sellerParcelUuid)
                .seller(seller)
                .status(status)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .verificationTier(VerificationTier.SCRIPT)
                .startingBid(1000L)
                .durationHours(168)
                .snipeProtect(snipeProtect)
                .snipeWindowMin(snipeWindowMin)
                .buyNowPrice(buyNowPrice)
                .listingFeePaid(true)
                .currentBid(currentBid)
                .bidCount(bidCount)
                .consecutiveWorldApiFailures(0)
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .build();
        OffsetDateTime now = OffsetDateTime.now();
        a.setStartsAt(now.minusHours(1));
        a.setEndsAt(endsAt);
        a.setOriginalEndsAt(endsAt);
        Auction saved = auctionRepository.save(a);
        saved.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(sellerParcelUuid)
                .ownerUuid(ownerUuid)
                .ownerType("agent")
                .parcelName("Snipe Parcel")
                .regionName("Coniston")
                .regionMaturityRating("M_NOT")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
        return auctionRepository.save(saved);
    }
}
