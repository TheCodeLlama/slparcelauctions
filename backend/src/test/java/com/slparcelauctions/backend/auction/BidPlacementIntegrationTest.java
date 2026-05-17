package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.region.dto.RegionPageData;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.dto.ParcelPageData;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.wallet.BidReservation;
import com.slparcelauctions.backend.wallet.BidReservationRepository;

import reactor.core.publisher.Mono;

/**
 * Full-stack coverage for {@code /api/v1/auctions/{id}/bids} â€” the write
 * path goes through Spring Security, the slice exception handler, the
 * BidService transaction boundary, and persists to the real test-profile
 * database. Each case mirrors a spec Â§6 validation branch plus the happy
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
        "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.realty.invitation-expiry.enabled=false"
})
@Transactional
class BidPlacementIntegrationTest {

    private static final String TRUSTED_OWNER = "00000000-0000-0000-0000-000000000001";

    @Autowired MockMvc mockMvc;
    @Autowired AuctionRepository auctionRepository;
    @Autowired BidRepository bidRepository;
    @Autowired UserRepository userRepository;
    @Autowired BidReservationRepository reservationRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean SlWorldApiClient worldApi;
    @MockitoBean AuctionBroadcastPublisher broadcastPublisher;

    private String sellerAccessToken;
    private Long sellerId;
    private String bidderAccessToken;
    private Long bidderId;
    private String unverifiedAccessToken;
    private UUID sellerParcelUuid;

    @BeforeEach
    void setUp() throws Exception {
        sellerAccessToken = registerAndVerifyUser(
                "bid-seller@example.com", "BidSeller",
                "11111111-aaaa-bbbb-cccc-000000000001");
        sellerId = userRepository.findByUsername("bid-seller@example.com").orElseThrow().getId();

        bidderAccessToken = registerAndVerifyUser(
                "bid-bidder@example.com", "BidBidder",
                "22222222-aaaa-bbbb-cccc-000000000002");
        bidderId = userRepository.findByUsername("bid-bidder@example.com").orElseThrow().getId();
        // Wallet-only escrow funding (spec 2026-05-16): every bid hard-
        // reserves L$ from the bidder's wallet. Seed enough balance to
        // cover any bid amount the tests below use.
        seedBidderBalance(bidderId, 10_000_000L);

        unverifiedAccessToken = registerUser("bid-unverified@example.com", "BidUnverified");

        sellerParcelUuid = seedParcel();
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void placeBid_firstBidAtStartingBid_returns201AndPersists() throws Exception {
        Auction auction = seedAuction(AuctionStatus.ACTIVE, 0L, 0);

        mockMvc.perform(post("/api/v1/auctions/" + auction.getPublicId() + "/bids")
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

    @Test
    void placeBid_sameBidderRaisesOwnBid_succeedsWithoutUniqueViolation() throws Exception {
        // Regression: the same user re-bidding on the same auction used to
        // trip bid_reservations_active_idx (the partial unique index on
        // (user_id, auction_id) WHERE released_at IS NULL). Hibernate's
        // default action queue flushed the new reservation INSERT before
        // the prior reservation's release UPDATE, so both rows were briefly
        // active for the same (user, auction) cell. Different-user outbids
        // weren't affected because their (user, auction) cells differ.
        // WalletService.swapReservation now calls reservationRepository
        // .flush() right after marking the prior reservation released, so
        // the UPDATE lands before the INSERT.
        Auction auction = seedAuction(AuctionStatus.ACTIVE, 0L, 0);

        mockMvc.perform(post("/api/v1/auctions/" + auction.getPublicId() + "/bids")
                        .header("Authorization", "Bearer " + bidderAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auctions/" + auction.getPublicId() + "/bids")
                        .header("Authorization", "Bearer " + bidderAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1500}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(1500));

        List<BidReservation> active = reservationRepository.findAllActiveForAuction(auction.getId());
        assertThat(active).hasSize(1);
        BidReservation only = active.getFirst();
        assertThat(only.getAmount()).isEqualTo(1500L);
        assertThat(only.getUserId()).isEqualTo(bidderId);
        assertThat(only.getReleasedAt()).isNull();
    }

    // -------------------------------------------------------------------------
    // Error paths
    // -------------------------------------------------------------------------

    @Test
    void placeBid_belowStartingBid_returns400BidTooLow() throws Exception {
        Auction auction = seedAuction(AuctionStatus.ACTIVE, 0L, 0);

        mockMvc.perform(post("/api/v1/auctions/" + auction.getPublicId() + "/bids")
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

        mockMvc.perform(post("/api/v1/auctions/" + auction.getPublicId() + "/bids")
                        .header("Authorization", "Bearer " + sellerAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELLER_CANNOT_BID"));
    }

    @Test
    void placeBid_asUnverifiedUser_returns403NotVerified() throws Exception {
        Auction auction = seedAuction(AuctionStatus.ACTIVE, 0L, 0);

        mockMvc.perform(post("/api/v1/auctions/" + auction.getPublicId() + "/bids")
                        .header("Authorization", "Bearer " + unverifiedAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_VERIFIED"));
    }

    @Test
    void placeBid_onEndedAuction_returns409() throws Exception {
        // status != ACTIVE triggers AUCTION_NOT_ACTIVE. Use TRANSFER_PENDING
        // (the post-close mid-flight status) as a concrete non-ACTIVE example.
        Auction auction = seedAuction(AuctionStatus.TRANSFER_PENDING, 0L, 0);

        mockMvc.perform(post("/api/v1/auctions/" + auction.getPublicId() + "/bids")
                        .header("Authorization", "Bearer " + bidderAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUCTION_NOT_ACTIVE"))
                .andExpect(jsonPath("$.currentState").value("TRANSFER_PENDING"));
    }

    @Test
    void placeBid_pastEndsAtOnActiveAuction_returns409AlreadyEnded() throws Exception {
        // ACTIVE status but endsAt in the past â€” scheduler hasn't closed it
        // yet. Spec Â§6 step 3 requires a distinct 409 AUCTION_ALREADY_ENDED.
        Auction auction = seedAuction(AuctionStatus.ACTIVE, 0L, 0);
        auction.setEndsAt(OffsetDateTime.now().minusMinutes(1));
        auctionRepository.save(auction);

        mockMvc.perform(post("/api/v1/auctions/" + auction.getPublicId() + "/bids")
                        .header("Authorization", "Bearer " + bidderAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUCTION_ALREADY_ENDED"));
    }

    @Test
    void placeBid_missingAuction_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/auctions/00000000-0000-0000-0000-000000999999/bids")
                        .header("Authorization", "Bearer " + bidderAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUCTION_NOT_FOUND"));
    }

    @Test
    void placeBid_missingAmountField_returns400Validation() throws Exception {
        Auction auction = seedAuction(AuctionStatus.ACTIVE, 0L, 0);

        mockMvc.perform(post("/api/v1/auctions/" + auction.getPublicId() + "/bids")
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

        placeBidAs(bidderAccessToken, auction.getPublicId(), 1000L);

        // Public â€” no Authorization header.
        mockMvc.perform(get("/api/v1/auctions/" + auction.getPublicId() + "/bids"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].amount").value(1000))
                .andExpect(jsonPath("$.content[0].bidType").value("MANUAL"))
                .andExpect(jsonPath("$.content[0].bidderDisplayName").value("BidBidder"))
                // Public view â€” ipAddress must not leak.
                .andExpect(jsonPath("$.content[0].ipAddress").doesNotExist())
                .andExpect(jsonPath("$.content[0].proxyBidId").doesNotExist());
    }

    @Test
    void bidHistory_emptyWhenNoBidsYet() throws Exception {
        Auction auction = seedAuction(AuctionStatus.ACTIVE, 0L, 0);

        mockMvc.perform(get("/api/v1/auctions/" + auction.getPublicId() + "/bids"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void placeBidAs(String accessToken, UUID auctionPublicId, long amount) throws Exception {
        mockMvc.perform(post("/api/v1/auctions/" + auctionPublicId + "/bids")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + "}"))
                .andExpect(status().isCreated());
    }

    private String registerUser(String email, String displayName) throws Exception {
        String body = String.format(
                "{\"username\":\"%s\",\"password\":\"hunter22abc\"}",
                email);
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated()).andReturn();
        JsonNode json = objectMapper.readTree(reg.getResponse().getContentAsString());
        String token = json.get("accessToken").asText();
        // Registration no longer accepts displayName; set it via PUT /me so
        // downstream tests that assert on displayName keep working.
        if (displayName != null) {
            mockMvc.perform(put("/api/v1/users/me")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(String.format("{\"displayName\":\"%s\"}", displayName)))
                    .andExpect(status().isOk());
        }
        return token;
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
        UUID parcelUuid = UUID.fromString("44444444-4444-4444-4444-000000000004");
        UUID ownerUuid = UUID.fromString("55555555-5555-5555-5555-000000000005");
        when(worldApi.fetchParcelPage(parcelUuid)).thenReturn(
                Mono.just(new ParcelPageData(new ParcelMetadata(
                        parcelUuid,
                ownerUuid,
                "agent",
                null,
                "Bid Parcel",
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

    private void seedBidderBalance(Long userId, long lindens) {
        User u = userRepository.findById(userId).orElseThrow();
        u.setBalanceLindens(lindens);
        u.setReservedLindens(0L);
        u.setPenaltyBalanceOwed(0L);
        userRepository.save(u);
    }

    private Auction seedAuction(AuctionStatus status, long currentBid, int bidCount) {
        User seller = userRepository.findById(sellerId).orElseThrow();
        Auction a = Auction.builder()
                .title("Test listing")
                .slParcelUuid(sellerParcelUuid)
                .seller(seller)
                .status(status)

                .verificationTier(VerificationTier.SCRIPT)
                .startingBid(1000L)
                .durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(true)
                .currentBid(currentBid)
                .bidCount(bidCount)
                .consecutiveWorldApiFailures(0)
                .commissionRate(new BigDecimal("0.05"))
                .build();
        a.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(sellerParcelUuid)
                .ownerUuid(UUID.fromString("55555555-5555-5555-5555-000000000005"))
                .ownerType("agent").parcelName("Bid Parcel")
                .regionName("Coniston").regionMaturityRating("M_NOT")
                .areaSqm(1024).positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
        if (status == AuctionStatus.ACTIVE) {
            OffsetDateTime now = OffsetDateTime.now();
            a.setStartsAt(now.minusHours(1));
            a.setEndsAt(now.plusDays(1));
            a.setOriginalEndsAt(now.plusDays(1));
        }
        return auctionRepository.save(a);
    }
}
