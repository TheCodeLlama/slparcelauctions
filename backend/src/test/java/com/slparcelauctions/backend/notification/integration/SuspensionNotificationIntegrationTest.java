package com.slparcelauctions.backend.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
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
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.auction.monitoring.SuspensionService;
import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationRepository;
import com.slparcelauctions.backend.notification.NotificationWsBroadcasterPort;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Vertical-slice integration tests for suspension notifications.
 */
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
class SuspensionNotificationIntegrationTest {

    @Autowired SuspensionService suspensionService;
    @Autowired AuctionRepository auctionRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired UserRepository userRepo;
    @Autowired NotificationRepository notifRepo;
    @Autowired FraudFlagRepository fraudFlagRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private Long sellerId, auctionId, parcelId;

    @AfterEach
    void cleanup() throws Exception {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            if (auctionId != null) {
                fraudFlagRepo.findByAuctionId(auctionId).forEach(fraudFlagRepo::delete);
                auctionRepo.findById(auctionId).ifPresent(auctionRepo::delete);
            }
            if (parcelId != null) parcelRepo.findById(parcelId).ifPresent(parcelRepo::delete);
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
        sellerId = auctionId = parcelId = null;
    }

    private User newUser(String prefix) {
        return new TransactionTemplate(txManager).execute(s -> userRepo.save(User.builder()
                .email(prefix + "-" + UUID.randomUUID() + "@test.com")
                .passwordHash("h")
                .displayName(prefix)
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .build()));
    }

    private Auction seedActiveAuction(User seller) {
        return new TransactionTemplate(txManager).execute(s -> {
            Parcel p = parcelRepo.save(Parcel.builder()
                    .slParcelUuid(UUID.randomUUID())
                    .ownerUuid(seller.getSlAvatarUuid())
                    .ownerType("agent")
                    .regionName("SuspRegion")
                    .continentName("Sansara")
                    .areaSqm(256)
                    .maturityRating("GENERAL")
                    .verified(true)
                    .verifiedAt(OffsetDateTime.now())
                    .build());
            parcelId = p.getId();
            Auction a = auctionRepo.save(Auction.builder()
                    .title("Suspension Test Lot")
                    .parcel(p)
                    .seller(seller)
                    .status(AuctionStatus.ACTIVE)
                    .verificationMethod(VerificationMethod.UUID_ENTRY)
                    .verificationTier(VerificationTier.SCRIPT)
                    .startingBid(1000L)
                    .currentBid(0L)
                    .bidCount(0)
                    .durationHours(168)
                    .snipeProtect(false)
                    .listingFeePaid(true)
                    .consecutiveWorldApiFailures(0)
                    .commissionRate(new BigDecimal("0.05"))
                    .agentFeeRate(BigDecimal.ZERO)
                    .startsAt(OffsetDateTime.now().minusHours(1))
                    .endsAt(OffsetDateTime.now().plusHours(24))
                    .originalEndsAt(OffsetDateTime.now().plusHours(24))
                    .build());
            auctionId = a.getId();
            return a;
        });
    }

    @Test
    void suspendForOwnershipChange_publishesListingSuspended() {
        User seller = newUser("susp-seller"); sellerId = seller.getId();
        Auction a = seedActiveAuction(seller);

        ParcelMetadata evidence = new ParcelMetadata(
                a.getParcel().getSlParcelUuid(),
                UUID.randomUUID(),
                "agent",
                "SuspRegion",
                a.getParcel().getRegionName(),
                null, null, null, null, null, null, null);
        suspensionService.suspendForOwnershipChange(a, evidence);

        var notifs = notifRepo.findAll().stream()
                .filter(n -> n.getUser().getId().equals(sellerId))
                .filter(n -> n.getCategory() == NotificationCategory.LISTING_SUSPENDED)
                .toList();
        assertThat(notifs).hasSize(1);
    }

    @Test
    void suspendForDeletedParcel_publishesListingSuspended() {
        User seller = newUser("del-seller"); sellerId = seller.getId();
        Auction a = seedActiveAuction(seller);

        suspensionService.suspendForDeletedParcel(a);

        var notifs = notifRepo.findAll().stream()
                .filter(n -> n.getUser().getId().equals(sellerId))
                .filter(n -> n.getCategory() == NotificationCategory.LISTING_SUSPENDED)
                .toList();
        assertThat(notifs).hasSize(1);
    }

    @Test
    void suspendForBotObservation_publishesListingSuspended() {
        User seller = newUser("bot-seller"); sellerId = seller.getId();
        Auction a = seedActiveAuction(seller);

        suspensionService.suspendForBotObservation(
                a, FraudFlagReason.BOT_PRICE_DRIFT, Map.of("sentinel", "mismatch"));

        var notifs = notifRepo.findAll().stream()
                .filter(n -> n.getUser().getId().equals(sellerId))
                .filter(n -> n.getCategory() == NotificationCategory.LISTING_SUSPENDED)
                .toList();
        assertThat(notifs).hasSize(1);
    }
}
