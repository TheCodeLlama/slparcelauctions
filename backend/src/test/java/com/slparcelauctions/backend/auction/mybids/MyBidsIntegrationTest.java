package com.slparcelauctions.backend.auction.mybids;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.Bid;
import com.slparcelauctions.backend.auction.BidRepository;
import com.slparcelauctions.backend.auction.BidType;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.broadcast.AuctionBroadcastPublisher;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Full-stack coverage for {@code GET /api/v1/users/me/bids}. Seeds one bidder
 * across seven auctions — one per {@link MyBidStatus} bucket — and verifies
 * the derived-status output across the four supported {@code ?status=} filters
 * (unspecified/all, active, won, lost).
 *
 * <p>Bids and proxy rows are seeded via direct JPA saves rather than the
 * {@link com.slparcelauctions.backend.auction.BidService} flow — buckets like
 * CANCELLED, SUSPENDED, and RESERVE_NOT_MET are terminal states that the
 * service refuses to let bids through. The point of this test is the
 * read-side query + derivation, not the write-side lifecycle.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
@Transactional
class MyBidsIntegrationTest {

    private static final String TRUSTED_OWNER = "00000000-0000-0000-0000-000000000001";

    @Autowired MockMvc mockMvc;
    @Autowired AuctionRepository auctionRepository;
    @Autowired BidRepository bidRepository;
    @Autowired UserRepository userRepository;
    @PersistenceContext EntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean AuctionBroadcastPublisher broadcastPublisher;

    private String sellerAccessToken;
    private Long sellerId;
    private String bidderAccessToken;
    private Long bidderId;
    private Long otherBidderId;
    private java.util.List<UUID> parcelUuids;
    private UUID winningAuctionPublicId;
    private UUID outbidAuctionPublicId;
    private UUID wonAuctionPublicId;
    private UUID lostAuctionPublicId;
    private UUID reserveNotMetAuctionPublicId;
    private UUID cancelledAuctionPublicId;
    private UUID suspendedAuctionPublicId;

    @BeforeEach
    void setUp() throws Exception {
        sellerAccessToken = registerAndVerifyUser(
                "mybids-seller@example.com", "MyBidsSeller",
                "11111111-aaaa-bbbb-cccc-000000000101");
        sellerId = userRepository.findByEmail("mybids-seller@example.com").orElseThrow().getId();

        bidderAccessToken = registerAndVerifyUser(
                "mybids-bidder@example.com", "MyBidsBidder",
                "22222222-aaaa-bbbb-cccc-000000000102");
        bidderId = userRepository.findByEmail("mybids-bidder@example.com").orElseThrow().getId();

        registerAndVerifyUser(
                "mybids-other@example.com", "MyBidsOther",
                "33333333-aaaa-bbbb-cccc-000000000103");
        otherBidderId = userRepository.findByEmail("mybids-other@example.com").orElseThrow().getId();

        parcelUuids = new java.util.ArrayList<>(7);
        for (int i = 0; i < 7; i++) {
            parcelUuids.add(UUID.fromString(
                    String.format("44444444-4444-4444-4444-%012d", 110 + i)));
        }

        winningAuctionPublicId = seedWinning();
        outbidAuctionPublicId = seedOutbid();
        wonAuctionPublicId = seedWon();
        lostAuctionPublicId = seedLost();
        reserveNotMetAuctionPublicId = seedReserveNotMet();
        cancelledAuctionPublicId = seedCancelled();
        suspendedAuctionPublicId = seedSuspended();
    }

    // -------------------------------------------------------------------------
    // status=all (default)
    // -------------------------------------------------------------------------

    @Test
    void getMyBids_statusAll_returnsAllSevenBuckets() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/users/me/bids")
                        .param("status", "all")
                        .header("Authorization", "Bearer " + bidderAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(7))
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode content = root.get("content");
        // Collect by auctionPublicId -> derived status for assertion stability.
        java.util.Map<String, String> statusByAuction = new java.util.HashMap<>();
        for (JsonNode row : content) {
            String pid = row.get("auction").get("publicId").asText();
            String s = row.get("myBidStatus").asText();
            statusByAuction.put(pid, s);
        }
        org.assertj.core.api.Assertions.assertThat(statusByAuction).containsOnly(
                org.assertj.core.api.Assertions.entry(winningAuctionPublicId.toString(), "WINNING"),
                org.assertj.core.api.Assertions.entry(outbidAuctionPublicId.toString(), "OUTBID"),
                org.assertj.core.api.Assertions.entry(wonAuctionPublicId.toString(), "WON"),
                org.assertj.core.api.Assertions.entry(lostAuctionPublicId.toString(), "LOST"),
                org.assertj.core.api.Assertions.entry(reserveNotMetAuctionPublicId.toString(), "RESERVE_NOT_MET"),
                org.assertj.core.api.Assertions.entry(cancelledAuctionPublicId.toString(), "CANCELLED"),
                org.assertj.core.api.Assertions.entry(suspendedAuctionPublicId.toString(), "SUSPENDED"));
    }

    // -------------------------------------------------------------------------
    // status=active
    // -------------------------------------------------------------------------

    @Test
    void getMyBids_statusActive_returnsWinningAndOutbid() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/users/me/bids")
                        .param("status", "active")
                        .header("Authorization", "Bearer " + bidderAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andReturn();

        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
        java.util.Set<String> statuses = new java.util.HashSet<>();
        for (JsonNode row : content) {
            statuses.add(row.get("myBidStatus").asText());
        }
        org.assertj.core.api.Assertions.assertThat(statuses)
                .containsExactlyInAnyOrder("WINNING", "OUTBID");
    }

    // -------------------------------------------------------------------------
    // status=won
    // -------------------------------------------------------------------------

    @Test
    void getMyBids_statusWon_returnsOnlyWon() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/users/me/bids")
                        .param("status", "won")
                        .header("Authorization", "Bearer " + bidderAccessToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
        org.assertj.core.api.Assertions.assertThat(content.size()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(content.get(0).get("myBidStatus").asText()).isEqualTo("WON");
        org.assertj.core.api.Assertions.assertThat(content.get(0).get("auction").get("publicId").asText())
                .isEqualTo(wonAuctionPublicId.toString());
    }

    // -------------------------------------------------------------------------
    // status=lost
    // -------------------------------------------------------------------------

    @Test
    void getMyBids_statusLost_returnsLostBucket() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/users/me/bids")
                        .param("status", "lost")
                        .header("Authorization", "Bearer " + bidderAccessToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
        java.util.Map<String, String> statusByAuction = new java.util.HashMap<>();
        for (JsonNode row : content) {
            statusByAuction.put(
                    row.get("auction").get("publicId").asText(),
                    row.get("myBidStatus").asText());
        }
        org.assertj.core.api.Assertions.assertThat(statusByAuction).containsOnly(
                org.assertj.core.api.Assertions.entry(lostAuctionPublicId.toString(), "LOST"),
                org.assertj.core.api.Assertions.entry(reserveNotMetAuctionPublicId.toString(), "RESERVE_NOT_MET"),
                org.assertj.core.api.Assertions.entry(cancelledAuctionPublicId.toString(), "CANCELLED"),
                org.assertj.core.api.Assertions.entry(suspendedAuctionPublicId.toString(), "SUSPENDED"));
    }

    // -------------------------------------------------------------------------
    // Pagination tiebreaker: identical endsAt -> deterministic id ordering
    // -------------------------------------------------------------------------

    @Test
    void getMyBids_identicalEndsAt_ordersByAuctionIdAscending() throws Exception {
        // Two fresh ACTIVE auctions sharing the EXACT same endsAt. Without a
        // stable tiebreaker the DB is free to interleave these across pages,
        // which causes duplicate / missing rows in a paginated traversal.
        OffsetDateTime sharedEndsAt = OffsetDateTime.now().plusDays(2).withNano(0);
        UUID pA = UUID.fromString("44444444-4444-4444-4444-000000000100");
        UUID pB = UUID.fromString("44444444-4444-4444-4444-000000000101");
        Auction auctionA = seedAuctionWithEndsAt(pA, sharedEndsAt);
        Auction auctionB = seedAuctionWithEndsAt(pB, sharedEndsAt);
        saveBid(auctionA, bidderId, 2500L);
        saveBid(auctionB, bidderId, 2500L);

        // The auction with the lower DB id must appear first (tie-break by id ASC).
        // Track which publicId belongs to the lower/higher DB id.
        String lowerPublicId;
        String higherPublicId;
        if (auctionA.getId() < auctionB.getId()) {
            lowerPublicId = auctionA.getPublicId().toString();
            higherPublicId = auctionB.getPublicId().toString();
        } else {
            lowerPublicId = auctionB.getPublicId().toString();
            higherPublicId = auctionA.getPublicId().toString();
        }

        // Invoke the endpoint twice and assert stable ordering across calls —
        // the two new auctions must appear in id-ascending order both times.
        for (int i = 0; i < 2; i++) {
            MvcResult result = mockMvc.perform(get("/api/v1/users/me/bids")
                            .param("status", "active")
                            .header("Authorization", "Bearer " + bidderAccessToken))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("content");

            // Extract the order of our two new auction publicIds from the content.
            java.util.List<String> orderedNew = new java.util.ArrayList<>();
            for (JsonNode row : content) {
                String pid = row.get("auction").get("publicId").asText();
                if (pid.equals(lowerPublicId) || pid.equals(higherPublicId)) {
                    orderedNew.add(pid);
                }
            }
            org.assertj.core.api.Assertions.assertThat(orderedNew)
                    .as("identical endsAt must break ties by auction id ASC on iteration %d", i)
                    .containsExactly(lowerPublicId, higherPublicId);
        }
    }

    private Auction seedAuctionWithEndsAt(UUID parcelUuid, OffsetDateTime endsAt) {
        User seller = userRepository.findById(sellerId).orElseThrow();
        OffsetDateTime now = OffsetDateTime.now();
        Auction a = Auction.builder()
                .title("Test listing")
                .slParcelUuid(parcelUuid)
                .seller(seller)
                .status(AuctionStatus.ACTIVE)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .verificationTier(VerificationTier.SCRIPT)
                .startingBid(1000L)
                .durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(true)
                .currentBid(2500L)
                .bidCount(1)
                .consecutiveWorldApiFailures(0)
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .build();
        a.setStartsAt(now.minusHours(1));
        a.setEndsAt(endsAt);
        a.setOriginalEndsAt(endsAt);
        Auction saved = auctionRepository.save(a);
        saved.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid)
                .ownerUuid(UUID.randomUUID())
                .ownerType("agent")
                .parcelName("MyBids Parcel")
                .regionName("Coniston")
                .regionMaturityRating("GENERAL")
                .areaSqm(2048)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
        return auctionRepository.save(saved);
    }

    // -------------------------------------------------------------------------
    // Non-ACTIVE rows sort by endedAt (not endsAt). Spec §10 conditional
    // ORDER BY — earlier deviation surfaced as DEFERRED_WORK ledger entry.
    // -------------------------------------------------------------------------

    @Test
    void getMyBids_endedRowsSortByEndedAt_notEndsAt() throws Exception {
        // Seed two ENDED auctions whose endsAt order differs from endedAt order.
        // auctionEarlyEndsAt: endsAt=now-10h, endedAt=now-1h (extended/snipe-protected)
        // auctionLateEndsAt:  endsAt=now-2h,  endedAt=now-5h (closed earlier, no extension)
        // If sorted by endsAt DESC: late (-2h) before early (-10h) -> WRONG
        // If sorted by endedAt DESC: early (-1h) before late (-5h) -> CORRECT
        UUID pEarly = UUID.fromString("44444444-4444-4444-4444-000000000200");
        UUID pLate = UUID.fromString("44444444-4444-4444-4444-000000000201");

        OffsetDateTime now = OffsetDateTime.now();
        Auction earlyEndsAtAuction = seedEndedAuctionWithTimes(
                pEarly, now.minusHours(10), now.minusHours(1));
        Auction lateEndsAtAuction = seedEndedAuctionWithTimes(
                pLate, now.minusHours(2), now.minusHours(5));
        saveBid(earlyEndsAtAuction, bidderId, 1500L);
        saveBid(lateEndsAtAuction, bidderId, 1500L);

        MvcResult result = mockMvc.perform(get("/api/v1/users/me/bids")
                        .param("status", "all")
                        .header("Authorization", "Bearer " + bidderAccessToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("content");

        // Walk content in returned order, capturing positions of our two seeded rows.
        String earlyPublicId = earlyEndsAtAuction.getPublicId().toString();
        String latePublicId = lateEndsAtAuction.getPublicId().toString();
        int earlyIdx = -1;
        int lateIdx = -1;
        for (int i = 0; i < content.size(); i++) {
            String pid = content.get(i).get("auction").get("publicId").asText();
            if (pid.equals(earlyPublicId)) earlyIdx = i;
            if (pid.equals(latePublicId)) lateIdx = i;
        }
        org.assertj.core.api.Assertions.assertThat(earlyIdx)
                .as("auction with endedAt=now-1h must appear before auction with endedAt=now-5h")
                .isGreaterThanOrEqualTo(0)
                .isLessThan(lateIdx);
    }

    private Auction seedEndedAuctionWithTimes(
            UUID parcelUuid, OffsetDateTime endsAt, OffsetDateTime endedAt) {
        User seller = userRepository.findById(sellerId).orElseThrow();
        OffsetDateTime startsAt = endsAt.minusDays(7);
        Auction a = Auction.builder()
                .title("Test listing")
                .slParcelUuid(parcelUuid)
                .seller(seller)
                .status(AuctionStatus.ENDED)
                .endOutcome(AuctionEndOutcome.SOLD)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .verificationTier(VerificationTier.SCRIPT)
                .startingBid(1000L)
                .durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(true)
                .currentBid(1500L)
                .bidCount(1)
                .consecutiveWorldApiFailures(0)
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .build();
        a.setStartsAt(startsAt);
        a.setEndsAt(endsAt);
        a.setOriginalEndsAt(endsAt);
        a.setEndedAt(endedAt);
        Auction saved = auctionRepository.save(a);
        saved.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid)
                .ownerUuid(UUID.randomUUID())
                .ownerType("agent")
                .parcelName("MyBids Parcel")
                .regionName("Coniston")
                .regionMaturityRating("GENERAL")
                .areaSqm(2048)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
        return auctionRepository.save(saved);
    }

    // -------------------------------------------------------------------------
    // Hydration query-count guard: bulk-load auctions with parcel + seller in
    // one query rather than per-id findById (which lazy-loaded seller per row).
    // -------------------------------------------------------------------------

    @Test
    void getMyBids_hydratesAuctionsWithoutNPlusOne() throws Exception {
        SessionFactory sessionFactory = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class);
        Statistics stats = sessionFactory.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        mockMvc.perform(get("/api/v1/users/me/bids")
                        .param("status", "all")
                        .header("Authorization", "Bearer " + bidderAccessToken))
                .andExpect(status().isOk());

        // Seven auctions are seeded in @BeforeEach. The previous per-id
        // findById loop with a lazy seller would entity-load Auction 7 times
        // (one per id) and User 7 additional times (lazy seller fetch per
        // row). The bulk-load path entity-loads Auction 7 times too (once
        // per row in the result set), but seller is in the EntityGraph so
        // User loads come from the same SELECT — they do not show up as
        // additional load events. The strict guard here is on the entity
        // *fetch* (= lazy initialization) count, which the old path drove
        // up via lazy seller hydration and the new path leaves at zero.
        long entityFetchCount = stats.getEntityFetchCount();
        org.assertj.core.api.Assertions.assertThat(entityFetchCount)
                .as("My Bids must not lazy-fetch seller per row (regression to N+1)")
                .isLessThan(7L);
    }

    // -------------------------------------------------------------------------
    // Auth sanity: no token -> 401/403
    // -------------------------------------------------------------------------

    @Test
    void getMyBids_withoutAuth_returnsUnauthorizedOrForbidden() throws Exception {
        // Matches SecurityConfig's catch-all .authenticated(): Spring returns
        // 403 when anonymous access is rejected.
        mockMvc.perform(get("/api/v1/users/me/bids"))
                .andExpect(r -> {
                    int s = r.getResponse().getStatus();
                    org.assertj.core.api.Assertions.assertThat(s)
                            .isIn(401, 403);
                });
    }

    // -------------------------------------------------------------------------
    // Seeding helpers — direct JPA saves so we can create terminal states
    // (CANCELLED, SUSPENDED) that BidService wouldn't let bids through.
    // -------------------------------------------------------------------------

    private UUID seedWinning() {
        // ACTIVE, caller is currentBidder.
        Auction a = seedAuction(parcelUuids.get(0), AuctionStatus.ACTIVE, 1500L, 1);
        a.setCurrentBidderId(bidderId);
        auctionRepository.save(a);
        saveBid(a, bidderId, 1500L);
        return a.getPublicId();
    }

    private UUID seedOutbid() {
        // ACTIVE, someone else is currentBidder; caller still has a bid on record.
        Auction a = seedAuction(parcelUuids.get(1), AuctionStatus.ACTIVE, 2000L, 2);
        a.setCurrentBidderId(otherBidderId);
        auctionRepository.save(a);
        saveBid(a, bidderId, 1500L);
        saveBid(a, otherBidderId, 2000L);
        return a.getPublicId();
    }

    private UUID seedWon() {
        // ENDED + SOLD, caller is winner.
        Auction a = seedAuction(parcelUuids.get(2), AuctionStatus.ENDED, 3000L, 1);
        a.setEndOutcome(AuctionEndOutcome.SOLD);
        a.setCurrentBidderId(bidderId);
        a.setWinnerUserId(bidderId);
        a.setFinalBidAmount(3000L);
        a.setEndedAt(OffsetDateTime.now().minusHours(1));
        auctionRepository.save(a);
        saveBid(a, bidderId, 3000L);
        return a.getPublicId();
    }

    private UUID seedLost() {
        // ENDED + SOLD, someone else is winner; caller bid earlier.
        Auction a = seedAuction(parcelUuids.get(3), AuctionStatus.ENDED, 4000L, 2);
        a.setEndOutcome(AuctionEndOutcome.SOLD);
        a.setCurrentBidderId(otherBidderId);
        a.setWinnerUserId(otherBidderId);
        a.setFinalBidAmount(4000L);
        a.setEndedAt(OffsetDateTime.now().minusHours(2));
        auctionRepository.save(a);
        saveBid(a, bidderId, 2000L);
        saveBid(a, otherBidderId, 4000L);
        return a.getPublicId();
    }

    private UUID seedReserveNotMet() {
        // ENDED + RESERVE_NOT_MET, caller was the high bidder.
        Auction a = seedAuction(parcelUuids.get(4), AuctionStatus.ENDED, 1200L, 1);
        a.setReservePrice(5000L);
        a.setEndOutcome(AuctionEndOutcome.RESERVE_NOT_MET);
        a.setCurrentBidderId(bidderId);
        // No winner set — reserve wasn't met.
        a.setEndedAt(OffsetDateTime.now().minusHours(3));
        auctionRepository.save(a);
        saveBid(a, bidderId, 1200L);
        return a.getPublicId();
    }

    private UUID seedCancelled() {
        // CANCELLED by seller after caller had already bid.
        Auction a = seedAuction(parcelUuids.get(5), AuctionStatus.CANCELLED, 1000L, 1);
        a.setEndedAt(OffsetDateTime.now().minusHours(4));
        auctionRepository.save(a);
        saveBid(a, bidderId, 1000L);
        return a.getPublicId();
    }

    private UUID seedSuspended() {
        // SUSPENDED via ownership-check path. Caller bid earlier.
        Auction a = seedAuction(parcelUuids.get(6), AuctionStatus.SUSPENDED, 1100L, 1);
        a.setEndedAt(OffsetDateTime.now().minusHours(5));
        auctionRepository.save(a);
        saveBid(a, bidderId, 1100L);
        return a.getPublicId();
    }

    private Auction seedAuction(
            UUID parcelUuid, AuctionStatus status, long currentBid, int bidCount) {
        User seller = userRepository.findById(sellerId).orElseThrow();
        UUID ownerUuid = UUID.fromString(
                String.format("55555555-5555-5555-5555-%012d", parcelUuid.getLeastSignificantBits() & 0xFFFFFFFFFFFFL));
        OffsetDateTime now = OffsetDateTime.now();
        Auction a = Auction.builder()
                .title("Test listing")
                .slParcelUuid(parcelUuid)
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
        a.setStartsAt(now.minusHours(1));
        if (status == AuctionStatus.ACTIVE) {
            a.setEndsAt(now.plusDays(1));
            a.setOriginalEndsAt(now.plusDays(1));
        } else {
            a.setEndsAt(now.minusHours(1));
            a.setOriginalEndsAt(now.minusHours(1));
        }
        Auction saved = auctionRepository.save(a);
        saved.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid)
                .ownerUuid(ownerUuid)
                .ownerType("agent")
                .parcelName("MyBids Parcel")
                .regionName("Coniston")
                .regionMaturityRating("GENERAL")
                .areaSqm(2048)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
        return auctionRepository.save(saved);
    }

    private void saveBid(Auction a, Long userId, Long amount) {
        User user = userRepository.findById(userId).orElseThrow();
        Bid b = Bid.builder()
                .auction(a)
                .bidder(user)
                .amount(amount)
                .bidType(BidType.MANUAL)
                .build();
        bidRepository.save(b);
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

}
