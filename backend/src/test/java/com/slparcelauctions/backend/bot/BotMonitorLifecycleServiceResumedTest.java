package com.slparcelauctions.backend.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
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
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.escrow.listing-fee-refund-job.enabled=false",
    "slpa.bot-task.timeout-check-interval=PT10M"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BotMonitorLifecycleServiceResumedTest {

    @Autowired private BotMonitorLifecycleService lifecycle;
    @Autowired private BotTaskRepository botTaskRepo;
    @Autowired private AuctionRepository auctionRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private PlatformTransactionManager txManager;

    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private Long sellerId;
    private Long auctionId;

    @AfterEach
    void cleanup() {
        new TransactionTemplate(txManager).executeWithoutResult(ts -> {
            if (auctionId != null) {
                botTaskRepo.findAll().stream()
                    .filter(t -> t.getAuction() != null && t.getAuction().getId().equals(auctionId))
                    .forEach(botTaskRepo::delete);
                auctionRepo.findById(auctionId).ifPresent(auctionRepo::delete);
            }
            if (sellerId != null) {
                userRepo.findById(sellerId).ifPresent(userRepo::delete);
            }
        });
        sellerId = null;
        auctionId = null;
    }

    @Test
    void onAuctionResumed_botTier_createsMonitorAuctionRow() {
        Auction auction = seedAuction(VerificationTier.BOT, AuctionStatus.SUSPENDED);

        lifecycle.onAuctionResumed(auction);

        List<BotTask> rows = botTaskRepo.findAll().stream()
            .filter(r -> r.getTaskType() == BotTaskType.MONITOR_AUCTION)
            .filter(r -> r.getAuction().getId().equals(auction.getId()))
            .toList();
        assertThat(rows).hasSize(1);
        BotTask monitor = rows.get(0);
        assertThat(monitor.getStatus()).isEqualTo(BotTaskStatus.PENDING);
        assertThat(monitor.getExpectedOwnerUuid())
            .isEqualTo(auction.getParcelSnapshot().getOwnerUuid());
        assertThat(monitor.getNextRunAt())
            .isAfter(OffsetDateTime.now().minusMinutes(1));
    }

    @Test
    void onAuctionResumed_nonBotTier_doesNotCreateMonitorRow() {
        Auction auction = seedAuction(VerificationTier.SCRIPT, AuctionStatus.SUSPENDED);

        lifecycle.onAuctionResumed(auction);

        long count = botTaskRepo.findAll().stream()
            .filter(r -> r.getTaskType() == BotTaskType.MONITOR_AUCTION)
            .filter(r -> r.getAuction().getId().equals(auction.getId()))
            .count();
        assertThat(count).isZero();
    }

    // ------------------------------------------------------------------
    // Seeding
    // ------------------------------------------------------------------

    private Auction seedAuction(VerificationTier tier, AuctionStatus status) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(ts -> {
            User seller = userRepo.save(User.builder()
                .email("resumed-seller-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Resumed Lifecycle Seller")
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .build());
            sellerId = seller.getId();

            UUID parcelUuid = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now();
            Auction a = auctionRepo.save(Auction.builder()
                .title("Resumed Test Listing")
                .slParcelUuid(parcelUuid)
                .seller(seller)
                .status(status)
                .verificationMethod(VerificationMethod.SALE_TO_BOT)
                .verificationTier(tier)
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
            a.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid)
                .ownerUuid(seller.getSlAvatarUuid())
                .ownerType("agent")
                .parcelName("Resumed Test Parcel")
                .regionName("Coniston")
                .regionMaturityRating("GENERAL")
                .areaSqm(1024)
                .positionX(128.0)
                .positionY(64.0)
                .positionZ(22.0)
                .build());
            auctionRepo.save(a);
            auctionId = a.getId();
        });
        return auctionRepo.findById(auctionId).orElseThrow();
    }
}
