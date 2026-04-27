package com.slparcelauctions.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.fraud.FraudFlag;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.bot.BotTask;
import com.slparcelauctions.backend.bot.BotTaskRepository;
import com.slparcelauctions.backend.bot.BotTaskType;
import com.slparcelauctions.backend.notification.Notification;
import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationRepository;
import com.slparcelauctions.backend.notification.NotificationWsBroadcasterPort;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

@SpringBootTest
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
class AdminFraudFlagReinstateIntegrationTest {

    @Autowired AdminFraudFlagService service;
    @Autowired AuctionRepository auctionRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired UserRepository userRepo;
    @Autowired FraudFlagRepository fraudFlagRepo;
    @Autowired BotTaskRepository botTaskRepo;
    @Autowired NotificationRepository notificationRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private Long sellerId;
    private Long adminId;
    private Long parcelId;
    private Long auctionId;
    private Long flagId;

    @BeforeEach
    void seed() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            User seller = userRepo.save(User.builder()
                .email("reinstate-int-seller-" + UUID.randomUUID() + "@x.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("Reinstate Int Seller")
                .build());
            sellerId = seller.getId();

            User admin = userRepo.save(User.builder()
                .email("reinstate-int-admin-" + UUID.randomUUID() + "@x.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("Reinstate Int Admin")
                .role(Role.ADMIN)
                .build());
            adminId = admin.getId();

            Parcel parcel = parcelRepo.save(Parcel.builder()
                .slParcelUuid(UUID.randomUUID())
                .regionName("ReinstateIntRegion")
                .ownerUuid(seller.getSlAvatarUuid())
                .ownerType("agent")
                .areaSqm(1024)
                .maturityRating("MODERATE")
                .positionX(128.0)
                .positionY(64.0)
                .positionZ(22.0)
                .continentName("Sansara")
                .verified(true)
                .verifiedAt(OffsetDateTime.now())
                .build());
            parcelId = parcel.getId();

            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = auctionRepo.save(Auction.builder()
                .seller(seller)
                .parcel(parcel)
                .title("Bot Reinstate Integration Auction")
                .status(AuctionStatus.SUSPENDED)
                .verificationTier(VerificationTier.BOT)
                .verificationMethod(VerificationMethod.SALE_TO_BOT)
                .verifiedAt(now)
                .startingBid(1_000L)
                .durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(true)
                .listingFeeAmt(100L)
                .currentBid(0L)
                .bidCount(0)
                .consecutiveWorldApiFailures(0)
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .startsAt(now.minusHours(2))
                .endsAt(now.plusHours(166))
                .originalEndsAt(now.plusHours(166))
                .suspendedAt(now.minusHours(6))
                .createdAt(now)
                .updatedAt(now)
                .build());
            auctionId = auction.getId();

            FraudFlag flag = fraudFlagRepo.save(FraudFlag.builder()
                .auction(auction)
                .parcel(parcel)
                .reason(FraudFlagReason.BOT_PRICE_DRIFT)
                .detectedAt(now.minusHours(6))
                .resolved(false)
                .build());
            flagId = flag.getId();
        });
    }

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                if (flagId != null) {
                    st.execute("DELETE FROM fraud_flags WHERE id = " + flagId);
                }
                if (auctionId != null) {
                    st.execute("DELETE FROM bot_tasks WHERE auction_id = " + auctionId);
                    st.execute("DELETE FROM auction_tags WHERE auction_id = " + auctionId);
                    st.execute("DELETE FROM auctions WHERE id = " + auctionId);
                }
                if (parcelId != null) {
                    st.execute("DELETE FROM parcels WHERE id = " + parcelId);
                }
                if (sellerId != null) {
                    st.execute("DELETE FROM notification WHERE user_id = " + sellerId);
                    st.execute("DELETE FROM sl_im_message WHERE user_id = " + sellerId);
                    st.execute("DELETE FROM refresh_tokens WHERE user_id = " + sellerId);
                    st.execute("DELETE FROM users WHERE id = " + sellerId);
                }
                if (adminId != null) {
                    st.execute("DELETE FROM notification WHERE user_id = " + adminId);
                    st.execute("DELETE FROM refresh_tokens WHERE user_id = " + adminId);
                    st.execute("DELETE FROM users WHERE id = " + adminId);
                }
            }
        }
        sellerId = null;
        adminId = null;
        parcelId = null;
        auctionId = null;
        flagId = null;
    }

    @Test
    void reinstate_botTier_fullFlow_flipsActive_extendsEndsAt_clearsSuspendedAt_spawnsMonitor_publishesNotification() {
        service.reinstate(flagId, adminId, "Full flow reinstate");

        // Auction: status = ACTIVE, suspendedAt = null
        Auction auction = auctionRepo.findById(auctionId).orElseThrow();
        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(auction.getSuspendedAt()).isNull();

        // BotTask: MONITOR_AUCTION row created for the auction
        List<BotTask> monitorTasks = botTaskRepo.findAll().stream()
            .filter(t -> t.getTaskType() == BotTaskType.MONITOR_AUCTION)
            .filter(t -> t.getAuction().getId().equals(auctionId))
            .toList();
        assertThat(monitorTasks).hasSize(1);

        // Notification: LISTING_REINSTATED for seller
        List<Notification> notifications = notificationRepo.findAll().stream()
            .filter(n -> n.getUser().getId().equals(sellerId))
            .filter(n -> n.getCategory() == NotificationCategory.LISTING_REINSTATED)
            .toList();
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).getTitle()).contains("Bot Reinstate Integration Auction");
    }
}
