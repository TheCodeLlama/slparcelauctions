package com.slparcelauctions.backend.auction.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
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
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.notification.NotificationWsBroadcasterPort;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
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
class SuspensionServiceSuspendedAtTest {

    @Autowired SuspensionService suspensionService;
    @Autowired AuctionRepository auctionRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired UserRepository userRepo;
    @Autowired FraudFlagRepository fraudFlagRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private Long sellerId, parcelId, auctionId;

    // The saved auction entity (with real seller/parcel references, not lazy proxies)
    private Auction savedAuction;

    @BeforeEach
    void seed() {
        savedAuction = new TransactionTemplate(txManager).execute(s -> {
            User seller = userRepo.save(User.builder()
                .email("seller-" + UUID.randomUUID() + "@x.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .build());
            sellerId = seller.getId();

            Parcel parcel = parcelRepo.save(Parcel.builder()
                .slParcelUuid(UUID.randomUUID())
                .regionName("TestRegion")
                .ownerUuid(seller.getSlAvatarUuid())
                .areaSqm(1024)
                .build());
            parcelId = parcel.getId();

            Auction auction = auctionRepo.save(Auction.builder()
                .seller(seller)
                .parcel(parcel)
                .title("Test parcel auction")
                .status(AuctionStatus.ACTIVE)
                .verificationTier(VerificationTier.SCRIPT)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .startingBid(100L)
                .durationHours(24)
                .endsAt(OffsetDateTime.now().plusHours(24))
                .build());
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

    private ParcelMetadata evidenceFor(Auction auction) {
        return new ParcelMetadata(
            auction.getParcel().getSlParcelUuid(),
            UUID.randomUUID(),
            "agent",
            "TestRegion",
            auction.getParcel().getRegionName(),
            null, null, null, null, null, null, null);
    }

    @Test
    void suspendForOwnershipChange_setsSuspendedAt_onFirstCall() {
        suspensionService.suspendForOwnershipChange(savedAuction, evidenceFor(savedAuction));

        Auction reloaded = auctionRepo.findById(auctionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.SUSPENDED);
        assertThat(reloaded.getSuspendedAt()).isNotNull();
    }

    @Test
    void suspendForOwnershipChange_doesNotMoveSuspendedAt_onSecondCall() {
        suspensionService.suspendForOwnershipChange(savedAuction, evidenceFor(savedAuction));
        OffsetDateTime firstSuspendedAt = auctionRepo.findById(auctionId).orElseThrow().getSuspendedAt();

        // Call again with the same (now-SUSPENDED) entity — suspendedAt must not move
        suspensionService.suspendForOwnershipChange(savedAuction, evidenceFor(savedAuction));

        OffsetDateTime secondSuspendedAt = auctionRepo.findById(auctionId).orElseThrow().getSuspendedAt();
        assertThat(secondSuspendedAt).isEqualTo(firstSuspendedAt);
    }

    @Test
    void suspendForDeletedParcel_setsSuspendedAt_onFirstCall() {
        suspensionService.suspendForDeletedParcel(savedAuction);
        Auction reloaded = auctionRepo.findById(auctionId).orElseThrow();
        assertThat(reloaded.getSuspendedAt()).isNotNull();
    }
}
