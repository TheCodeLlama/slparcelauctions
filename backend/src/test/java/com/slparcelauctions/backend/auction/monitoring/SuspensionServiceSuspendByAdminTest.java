package com.slparcelauctions.backend.auction.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.fraud.FraudFlag;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.notification.Notification;
import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationRepository;
import com.slparcelauctions.backend.notification.NotificationWsBroadcasterPort;
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
class SuspensionServiceSuspendByAdminTest {

    @Autowired SuspensionService suspensionService;
    @Autowired AuctionRepository auctionRepo;
    @Autowired UserRepository userRepo;
    @Autowired FraudFlagRepository fraudFlagRepo;
    @Autowired NotificationRepository notificationRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private Long sellerId;
    private Long auctionId;
    private Auction savedAuction;

    @BeforeEach
    void seed() {
        savedAuction = new TransactionTemplate(txManager).execute(s -> {
            User seller = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("seller-" + UUID.randomUUID() + "@x.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .build());
            sellerId = seller.getId();

            UUID parcelUuid = UUID.randomUUID();
            Auction auction = auctionRepo.save(Auction.builder()
                .seller(seller)
                .slParcelUuid(parcelUuid)
                .title("Test parcel auction")
                .status(AuctionStatus.ACTIVE)
                .verificationTier(VerificationTier.SCRIPT)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .startingBid(100L)
                .durationHours(24)
                .consecutiveWorldApiFailures(0)
                .endsAt(OffsetDateTime.now().plusHours(24))
                .build());
            auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid)
                .ownerUuid(seller.getSlAvatarUuid())
                .ownerType("agent")
                .parcelName("Admin Suspend Test Parcel")
                .regionName("Test Region")
                .regionMaturityRating("GENERAL")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
            auctionRepo.save(auction);
            auctionId = auction.getId();
            return auction;
        });
    }

    @AfterEach
    void cleanup() throws Exception {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            if (auctionId != null) {
                fraudFlagRepo.findByAuctionId(auctionId).forEach(fraudFlagRepo::delete);
                auctionRepo.findById(auctionId).ifPresent(auctionRepo::delete);
            }
        });
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                if (sellerId != null) {
                    st.execute("DELETE FROM notification WHERE user_id = " + sellerId);
                    st.execute("DELETE FROM refresh_tokens WHERE user_id = " + sellerId);
                    st.execute("DELETE FROM users WHERE id = " + sellerId);
                }
            }
        }
        sellerId = auctionId = null;
    }

    @Test
    void suspendByAdmin_flipsSuspended_setsSuspendedAt_noFraudFlag() {
        suspensionService.suspendByAdmin(savedAuction, 999L, "Violates ToS");

        Auction reloaded = auctionRepo.findById(auctionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.SUSPENDED);
        assertThat(reloaded.getSuspendedAt()).isNotNull();

        List<FraudFlag> flags = fraudFlagRepo.findByAuctionId(auctionId);
        assertThat(flags).isEmpty();
    }

    @Test
    void suspendByAdmin_publishesListingSuspendedToSeller() {
        suspensionService.suspendByAdmin(savedAuction, 999L, "Admin action");

        List<Notification> notifications = notificationRepo.findAllByUserId(sellerId);
        assertThat(notifications)
            .anyMatch(n -> n.getCategory() == NotificationCategory.LISTING_SUSPENDED);
    }
}
