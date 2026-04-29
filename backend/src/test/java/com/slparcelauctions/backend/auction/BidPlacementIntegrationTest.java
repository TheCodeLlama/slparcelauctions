package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
 * Full-stack coverage for {@code /api/v1/auctions/{id}/bids} — the write
 * path goes through Spring Security, the slice exception handler, the
 * BidService transaction boundary, and persists to the real test-profile
 * database. Each case mirrors a spec §6 validation branch plus the happy
 * path and the public GET history endpoint.
 *
 * <p>The {@link AuctionBroadcastPublisher} is mock-swapped so the test
 * keeps running even though no STOMP broker is available; the Task 5
 * production publisher will override this bean in its own test slice.
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
class BidPlacementIntegrationTest {

    private static final String TRUSTED_OWNER = "00000000-0000-0000-0000-000000000001";

    @Autowired MockMvc mockMvc;
    @Autowired ParcelRepository parcelRepository;
    @Autowired AuctionRepository auctionRepository;
    @Autowired BidRepository bidRepository;
    @Autowired UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean SlWorldApiClient worldApi;
    @MockitoBean SlMapApiClient mapApi;
    @MockitoBean AuctionBroadcastPublisher broadcastPublisher;

    private String sellerAccessToken;
    private Long sellerId;
    private String bidderAccessToken;
    private Long bidderId;
    private String unverifiedAccessToken;
    private Parcel sellerParcel;

    @BeforeEach
    void setUp() throws Exception {
        sellerAccessToken = registerAndVerifyUser(
                "bid-seller@example.com", "BidSeller",
                "11111111-aaaa-bbbb-cccc-000000000001");
        sellerId = userRepository.findByEmail("bid-seller@example.com").orElseThrow().getId();

        bidderAccessToken = registerAndVerifyUser(
                "bid-bidder@example.com", "BidBidder",
                "22222222-aaaa-bbbb-cccc-000000000002");
        bidderId = userRepository.findByEmail("bid-bidder@example.com").orElseThrow().getId();

        unverifiedAccessToken = registerUser("bid-unverified@example.com", "BidUnverified");

        sellerParcel = seedParcel();
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void placeBid_firstBidAtStartingBid_returns201AndPersists() throws Exception {
        Auction auction = seedAuction(AuctionStatus.ACTIVE, 0L, 0);

        mockMvc.perform(post("/api/v1/auctions/" + auction.getId() + "/bids")
                        .header("Authorization", "Bearer " + bidderAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(1000))
                .andExpect(jsonPath("$.bidType").value("MANUAL"))
                .andExpect(jsonPath("$.bidCount").value(1))
                .andExpect(jsonPath("$.buyNowTriggered").value(false));

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getCurrentBid()).isEqualTo(1000L);
        assertThat(reloaded.getCurrentBidderId()).isEqualTo(bidderId);
        assertThat(reloaded.getBidCount()).isEqualTo(1);

        List<Bid> bids = bidRepository.findByAuctionIdOrderByCreatedAtAsc(auction.getId());
        assertThat(bids).hasSize(1);
        Bid row = bids.getFirst();
        assertThat(row.getAmount()).isEqualTo(1000L);
        assertThat(row.getBidType()).isEqualTo(BidType.MANUAL);
        assertThat(row.getProxyBidId()).isNull();
        assertThat(row.getBidder().getId()).isEqualTo(bidderId);
    }

    // -------------------------------------------------------------------------
    // Error paths
    // -------------------------------------------------------------------------

    @Test
    void placeBid_belowStartingBid_returns400BidTooLow() throws Exception {
        Auction auction = seedAuction(AuctionStatus.ACTIVE, 0L, 0);

        mockMvc.perform(post("/api/v1/auctions/" + auction.getId() + "/bids")
                        .header("Authorization", "Bearer " + bidderAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":500}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BID_TOO_LOW"))
                .andExpect(jsonPath("$.minRequired").value(1000));
    }

    @Test
    void placeBid_asSeller_returns403SellerCannotBid() throws Exception {
        Auction auction = seedAuction(AuctionStatus.ACTIVE, 0L, 0);

        mockMvc.perform(post("/api/v1/auctions/" + auction.getId() + "/bids")
                        .header("Authorization", "Bearer " + sellerAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELLER_CANNOT_BID"));
    }

    @Test
    void placeBid_asUnverifiedUser_returns403NotVerified() throws Exception {
        Auction auction = seedAuction(AuctionStatus.ACTIVE, 0L, 0);

        mockMvc.perform(post("/api/v1/auctions/" + auction.getId() + "/bids")
                        .header("Authorization", "Bearer " + unverifiedAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_VERIFIED"));
    }

    @Test
    void placeBid_onEndedAuction_returns409() throws Exception {
        // status=ENDED triggers AUCTION_NOT_ACTIVE (status != ACTIVE branch).
        Auction auction = seedAuction(AuctionStatus.ENDED, 0L, 0);

        mockMvc.perform(post("/api/v1/auctions/" + auction.getId() + "/bids")
                        .header("Authorization", "Bearer " + bidderAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUCTION_NOT_ACTIVE"))
                .andExpect(jsonPath("$.currentState").value("ENDED"));
    }

    @Test
    void placeBid_pastEndsAtOnActiveAuction_returns409AlreadyEnded() throws Exception {
        // ACTIVE status but endsAt in the past — scheduler hasn't closed it
        // yet. Spec §6 step 3 requires a distinct 409 AUCTION_ALREADY_ENDED.
        Auction auction = seedAuction(AuctionStatus.ACTIVE, 0L, 0);
        auction.setEndsAt(OffsetDateTime.now().minusMinutes(1));
        auctionRepository.save(auction);

        mockMvc.perform(post("/api/v1/auctions/" + auction.getId() + "/bids")
                        .header("Authorization", "Bearer " + bidderAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUCTION_ALREADY_ENDED"));
    }

    @Test
    void placeBid_missingAuction_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/auctions/999999/bids")
                        .header("Authorization", "Bearer " + bidderAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUCTION_NOT_FOUND"));
    }

    @Test
    void placeBid_missingAmountField_returns400Validation() throws Exception {
        Auction auction = seedAuction(AuctionStatus.ACTIVE, 0L, 0);

        mockMvc.perform(post("/api/v1/auctions/" + auction.getId() + "/bids")
                        .header("Authorization", "Bearer " + bidderAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // -------------------------------------------------------------------------
    // GET /bids history (public)
    // -------------------------------------------------------------------------

    @Test
    void bidHistory_returnsCommittedBidsAndIsPublic() throws Exception {
        Auction auction = seedAuction(AuctionStatus.ACTIVE, 0L, 0);

        placeBidAs(bidderAccessToken, auction.getId(), 1000L);

        // Public — no Authorization header.
        mockMvc.perform(get("/api/v1/auctions/" + auction.getId() + "/bids"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].amount").value(1000))
                .andExpect(jsonPath("$.content[0].bidType").value("MANUAL"))
                .andExpect(jsonPath("$.content[0].bidderDisplayName").value("BidBidder"))
                // Public view — ipAddress must not leak.
                .andExpect(jsonPath("$.content[0].ipAddress").doesNotExist())
                .andExpect(jsonPath("$.content[0].proxyBidId").doesNotExist());
    }

    @Test
    void bidHistory_emptyWhenNoBidsYet() throws Exception {
        Auction auction = seedAuction(AuctionStatus.ACTIVE, 0L, 0);

        mockMvc.perform(get("/api/v1/auctions/" + auction.getId() + "/bids"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void placeBidAs(String accessToken, Long auctionId, long amount) throws Exception {
        mockMvc.perform(post("/api/v1/auctions/" + auctionId + "/bids")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + "}"))
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
        UUID parcelUuid = UUID.fromString("44444444-4444-4444-4444-000000000004");
        UUID ownerUuid = UUID.fromString("55555555-5555-5555-5555-000000000005");
        when(worldApi.fetchParcel(parcelUuid)).thenReturn(Mono.just(new ParcelMetadata(
                parcelUuid, ownerUuid, "agent",
                "Bid Parcel", "Coniston",
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

    private Auction seedAuction(AuctionStatus status, long currentBid, int bidCount) {
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
                .currentBid(currentBid)
                .bidCount(bidCount)
                .consecutiveWorldApiFailures(0)
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
