package com.slparcelauctions.backend.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.Bid;
import com.slparcelauctions.backend.auction.BidRepository;
import com.slparcelauctions.backend.auction.BidType;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.notification.Notification;
import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationRepository;
import com.slparcelauctions.backend.notification.NotificationWsBroadcasterPort;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Vertical-slice integration tests for auction-end notifications.
 * Uses the per-auction close endpoint (dev).
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
class AuctionEndNotificationIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired NotificationRepository notifRepo;
    @Autowired UserRepository userRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired BidRepository bidRepo;
    @Autowired EscrowRepository escrowRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    @MockitoBean
    NotificationWsBroadcasterPort wsBroadcaster;

    private Long sellerId;
    private Long bidderId;
    private Long loserBidderId;
    private Long auctionId;

    @AfterEach
    void cleanup() throws Exception {
        if (auctionId != null) {
            new TransactionTemplate(txManager).executeWithoutResult(s -> {
                escrowRepo.findByAuctionId(auctionId).ifPresent(escrowRepo::delete);
                bidRepo.deleteAllByAuctionId(auctionId);
                auctionRepo.findById(auctionId).ifPresent(auctionRepo::delete);
            });
        }
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                for (Long id : new Long[]{sellerId, bidderId, loserBidderId}) {
                    if (id != null) {
                        st.execute("DELETE FROM notification WHERE user_id = " + id);
                        st.execute("DELETE FROM refresh_tokens WHERE user_id = " + id);
                        st.execute("DELETE FROM users WHERE id = " + id);
                    }
                }
            }
        }
        sellerId = bidderId = loserBidderId = auctionId = null;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private User saveUser(String prefix) {
        return new TransactionTemplate(txManager).execute(s -> userRepo.save(User.builder()
                .email(prefix + "-" + UUID.randomUUID() + "@example.com")
                .passwordHash("hash")
                .displayName(prefix)
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .build()));
    }

    private void seedAuction(long currentBid, Long reserve, Long currentBidderId) {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            UUID parcelUuid = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now();
            Auction a = auctionRepo.save(Auction.builder()
                    .title("End Notification Test")
                    .slParcelUuid(parcelUuid)
                    .seller(userRepo.findById(sellerId).orElseThrow())
                    .status(AuctionStatus.ACTIVE)
                    .verificationMethod(VerificationMethod.UUID_ENTRY)
                    .verificationTier(VerificationTier.SCRIPT)
                    .startingBid(500L)
                    .reservePrice(reserve)
                    .currentBid(currentBid)
                    .currentBidderId(currentBidderId)
                    .bidCount(currentBidderId != null ? 1 : 0)
                    .durationHours(168)
                    .snipeProtect(false)
                    .listingFeePaid(true)
                    .consecutiveWorldApiFailures(0)
                    .commissionRate(new BigDecimal("0.05"))
                    .agentFeeRate(BigDecimal.ZERO)
                    .startsAt(now.minusHours(2))
                    .endsAt(now.minusSeconds(1))
                    .originalEndsAt(now.minusSeconds(1))
                    .build());
            auctionId = a.getId();
            a.setParcelSnapshot(AuctionParcelSnapshot.builder()
                    .slParcelUuid(parcelUuid).ownerType("agent")
                    .ownerName("Seller").parcelName("End Notification Parcel")
                    .regionName("Mainland").areaSqm(512)
                    .positionX(128.0).positionY(128.0).positionZ(22.0)
                    .build());
            auctionRepo.save(a);
            if (currentBidderId != null) {
                bidRepo.save(Bid.builder()
                        .auction(a)
                        .bidder(userRepo.findById(currentBidderId).orElseThrow())
                        .amount(currentBid)
                        .bidType(BidType.MANUAL)
                        .build());
            }
        });
    }

    private List<Notification> notifForUser(Long userId, NotificationCategory category) {
        return notifRepo.findAllByUserId(userId).stream()
                .filter(n -> n.getCategory() == category)
                .toList();
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void sold_winnerGetsWon_sellerGetsEndedSold_loserGetsLost() throws Exception {
        User seller = saveUser("end-seller"); sellerId = seller.getId();
        User winner = saveUser("end-winner"); bidderId = winner.getId();
        User loser  = saveUser("end-loser");  loserBidderId = loser.getId();

        seedAuction(2000L, 1000L, winner.getId());
        // Add a bid for the loser
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Auction a = auctionRepo.findById(auctionId).orElseThrow();
            bidRepo.save(Bid.builder()
                    .auction(a)
                    .bidder(userRepo.findById(loserBidderId).orElseThrow())
                    .amount(1800L)
                    .bidType(BidType.MANUAL)
                    .build());
        });

        mockMvc.perform(post("/api/v1/dev/auctions/" + auctionId + "/close"))
                .andExpect(status().isOk());

        assertThat(notifForUser(bidderId, NotificationCategory.AUCTION_WON)).hasSize(1);
        assertThat(notifForUser(sellerId, NotificationCategory.AUCTION_ENDED_SOLD)).hasSize(1);
        assertThat(notifForUser(loserBidderId, NotificationCategory.AUCTION_LOST)).hasSize(1);
        // Winner should NOT get AUCTION_LOST
        assertThat(notifForUser(bidderId, NotificationCategory.AUCTION_LOST)).isEmpty();
    }

    @Test
    void reserveNotMet_sellerGetsReserveNotMet_noBidderWon() throws Exception {
        User seller = saveUser("res-seller"); sellerId = seller.getId();
        User bidder = saveUser("res-bidder"); bidderId = bidder.getId();

        seedAuction(500L, 2000L, bidder.getId());

        mockMvc.perform(post("/api/v1/dev/auctions/" + auctionId + "/close"))
                .andExpect(status().isOk());

        assertThat(notifForUser(sellerId, NotificationCategory.AUCTION_ENDED_RESERVE_NOT_MET)).hasSize(1);
        assertThat(notifForUser(sellerId, NotificationCategory.AUCTION_WON)).isEmpty();
        assertThat(notifForUser(bidderId, NotificationCategory.AUCTION_WON)).isEmpty();
    }

    @Test
    void noBids_sellerGetsNoBids_noOtherNotifications() throws Exception {
        User seller = saveUser("nobid-seller"); sellerId = seller.getId();

        seedAuction(0L, null, null);

        mockMvc.perform(post("/api/v1/dev/auctions/" + auctionId + "/close"))
                .andExpect(status().isOk());

        assertThat(notifForUser(sellerId, NotificationCategory.AUCTION_ENDED_NO_BIDS)).hasSize(1);
        // No other notification categories for the seller
        assertThat(notifRepo.findAllByUserId(sellerId).stream()
                .filter(n -> n.getCategory() != NotificationCategory.AUCTION_ENDED_NO_BIDS)
                .toList()).isEmpty();
    }
}
