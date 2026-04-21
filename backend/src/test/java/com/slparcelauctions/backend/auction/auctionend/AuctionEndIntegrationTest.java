package com.slparcelauctions.backend.auction.auctionend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.broadcast.AuctionBroadcastPublisher;
import com.slparcelauctions.backend.auction.broadcast.CapturingAuctionBroadcastPublisher;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * End-to-end coverage for the auction-end sweep against a real database.
 * Exercises the dev trigger endpoint ({@code POST /api/v1/dev/auction-end/run-once})
 * so the full transactional stack runs: scheduler query → pessimistic lock
 * acquisition → outcome classification → status flip → proxy exhaust →
 * afterCommit envelope publish.
 *
 * <p>The real scheduler is disabled via {@code slpa.auction-end.enabled=false}
 * to keep the cron tick from racing the explicit run-once invocation. The
 * {@link AuctionBroadcastPublisher} is swapped for a
 * {@link CapturingAuctionBroadcastPublisher} so the test can assert envelope
 * contents without a live STOMP broker.
 *
 * <p>The class is NOT {@code @Transactional}: the auction-end worker commits
 * on the caller thread and the test assertions read the committed state back.
 * Fixture rows are seeded inside an explicit {@link TransactionTemplate} and
 * cleaned up in {@link #cleanUp}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.auction-end.enabled=false",
        "slpa.ownership-monitor.enabled=false"
})
@Import(AuctionEndIntegrationTest.CapturingConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuctionEndIntegrationTest {

    @TestConfiguration
    static class CapturingConfig {
        @Bean
        @Primary
        CapturingAuctionBroadcastPublisher capturingPublisher() {
            return new CapturingAuctionBroadcastPublisher();
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired AuctionRepository auctionRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired UserRepository userRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired CapturingAuctionBroadcastPublisher capturingPublisher;
    @Autowired javax.sql.DataSource dataSource;

    private Long seededAuctionId;
    private Long seededParcelId;
    private Long seededSellerId;
    private Long seededBidderId;

    @AfterEach
    void cleanUp() throws Exception {
        capturingPublisher.reset();
        if (seededAuctionId == null) return;
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM bids WHERE auction_id = " + seededAuctionId);
                stmt.execute("DELETE FROM proxy_bids WHERE auction_id = " + seededAuctionId);
                stmt.execute("DELETE FROM auction_tags WHERE auction_id = " + seededAuctionId);
                stmt.execute("DELETE FROM auctions WHERE id = " + seededAuctionId);
                if (seededParcelId != null) {
                    stmt.execute("DELETE FROM parcels WHERE id = " + seededParcelId);
                }
                if (seededBidderId != null) {
                    stmt.execute("DELETE FROM verification_codes WHERE user_id = " + seededBidderId);
                    stmt.execute("DELETE FROM refresh_tokens WHERE user_id = " + seededBidderId);
                    stmt.execute("DELETE FROM users WHERE id = " + seededBidderId);
                }
                if (seededSellerId != null) {
                    stmt.execute("DELETE FROM verification_codes WHERE user_id = " + seededSellerId);
                    stmt.execute("DELETE FROM refresh_tokens WHERE user_id = " + seededSellerId);
                    stmt.execute("DELETE FROM users WHERE id = " + seededSellerId);
                }
            }
        }
        seededAuctionId = null;
        seededParcelId = null;
        seededSellerId = null;
        seededBidderId = null;
    }

    @Test
    void runOnce_closesExpiredAuction_withBidAboveReserve_outcomeSold() throws Exception {
        long currentBid = 2000L;
        long reserve = 1000L;
        int bidCount = 2;
        seedExpiredAuction(currentBid, reserve, bidCount, "Winner Avatar");

        mockMvc.perform(post("/api/v1/dev/auction-end/run-once"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processed", org.hamcrest.Matchers.hasItem(seededAuctionId.intValue())));

        Auction refreshed = auctionRepo.findById(seededAuctionId).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(AuctionStatus.ENDED);
        assertThat(refreshed.getEndOutcome()).isEqualTo(AuctionEndOutcome.SOLD);
        assertThat(refreshed.getWinnerUserId()).isEqualTo(seededBidderId);
        assertThat(refreshed.getFinalBidAmount()).isEqualTo(currentBid);
        assertThat(refreshed.getEndedAt()).isNotNull();

        assertThat(capturingPublisher.ended).hasSize(1);
        assertThat(capturingPublisher.ended.get(0).endOutcome()).isEqualTo(AuctionEndOutcome.SOLD);
        assertThat(capturingPublisher.ended.get(0).winnerUserId()).isEqualTo(seededBidderId);
        assertThat(capturingPublisher.ended.get(0).winnerDisplayName()).isEqualTo("Winner Avatar");
        assertThat(capturingPublisher.ended.get(0).finalBid()).isEqualTo(currentBid);
    }

    @Test
    void runOnce_closesExpiredAuction_withBidBelowReserve_outcomeReserveNotMet() throws Exception {
        long currentBid = 500L;
        long reserve = 2000L;
        int bidCount = 1;
        seedExpiredAuction(currentBid, reserve, bidCount, "Below Reserve Bidder");

        mockMvc.perform(post("/api/v1/dev/auction-end/run-once"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processed", org.hamcrest.Matchers.hasItem(seededAuctionId.intValue())));

        Auction refreshed = auctionRepo.findById(seededAuctionId).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(AuctionStatus.ENDED);
        assertThat(refreshed.getEndOutcome()).isEqualTo(AuctionEndOutcome.RESERVE_NOT_MET);
        assertThat(refreshed.getWinnerUserId()).isNull();
        assertThat(refreshed.getFinalBidAmount()).isNull();
        assertThat(refreshed.getEndedAt()).isNotNull();

        assertThat(capturingPublisher.ended).hasSize(1);
        assertThat(capturingPublisher.ended.get(0).endOutcome()).isEqualTo(AuctionEndOutcome.RESERVE_NOT_MET);
        assertThat(capturingPublisher.ended.get(0).winnerUserId()).isNull();
        assertThat(capturingPublisher.ended.get(0).winnerDisplayName()).isNull();
        assertThat(capturingPublisher.ended.get(0).finalBid()).isNull();
    }

    @Test
    void closeOneEndpoint_closesAuction_returnsClosedId() throws Exception {
        seedExpiredAuction(1500L, 1000L, 1, "Solo Winner");

        mockMvc.perform(post("/api/v1/dev/auctions/" + seededAuctionId + "/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closedId").value(seededAuctionId.intValue()));

        Auction refreshed = auctionRepo.findById(seededAuctionId).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(AuctionStatus.ENDED);
        assertThat(refreshed.getEndOutcome()).isEqualTo(AuctionEndOutcome.SOLD);
    }

    // -------------------------------------------------------------------------
    // Seeding
    // -------------------------------------------------------------------------

    private void seedExpiredAuction(
            long currentBid, long reserve, int bidCount, String bidderDisplayName) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            User seller = userRepo.save(User.builder()
                    .email("end-seller-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("End Seller")
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .build());
            User bidder = userRepo.save(User.builder()
                    .email("end-bidder-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName(bidderDisplayName)
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .build());
            Parcel parcel = parcelRepo.save(Parcel.builder()
                    .slParcelUuid(UUID.randomUUID())
                    .ownerUuid(seller.getSlAvatarUuid())
                    .ownerType("agent")
                    .regionName("EndRegion")
                    .continentName("Sansara")
                    .areaSqm(1024)
                    .maturityRating("MATURE")
                    .verified(true)
                    .verifiedAt(OffsetDateTime.now())
                    .build());
            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = auctionRepo.save(Auction.builder()
                    .parcel(parcel)
                    .seller(seller)
                    .status(AuctionStatus.ACTIVE)
                    .verificationMethod(VerificationMethod.UUID_ENTRY)
                    .verificationTier(VerificationTier.SCRIPT)
                    .startingBid(500L)
                    .reservePrice(reserve)
                    .currentBid(currentBid)
                    .currentBidderId(bidder.getId())
                    .bidCount(bidCount)
                    .durationHours(168)
                    .snipeProtect(false)
                    .listingFeePaid(true)
                    .consecutiveWorldApiFailures(0)
                    .commissionRate(new BigDecimal("0.05"))
                    .agentFeeRate(BigDecimal.ZERO)
                    .startsAt(now.minusHours(2))
                    // Already expired — the run-once sweep must pick this up.
                    .endsAt(now.minusSeconds(1))
                    .originalEndsAt(now.minusSeconds(1))
                    .build());
            seededSellerId = seller.getId();
            seededBidderId = bidder.getId();
            seededParcelId = parcel.getId();
            seededAuctionId = auction.getId();
        });
    }
}
