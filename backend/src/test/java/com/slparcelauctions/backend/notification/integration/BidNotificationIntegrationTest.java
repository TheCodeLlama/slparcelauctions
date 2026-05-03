package com.slparcelauctions.backend.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
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
import com.slparcelauctions.backend.auction.BidService;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.notification.Notification;
import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationRepository;
import com.slparcelauctions.backend.notification.NotificationWsBroadcasterPort;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Vertical-slice integration tests for bid-event notifications.
 * Drives {@link BidService#placeBid} and asserts that OUTBID rows
 * materialise in the notifications table with the correct shape.
 * The WS broadcaster is mocked to prevent STOMP-broker dependency.
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
class BidNotificationIntegrationTest {

    @Autowired BidService bidService;
    @Autowired NotificationRepository notifRepo;
    @Autowired UserRepository userRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired com.slparcelauctions.backend.auction.BidRepository bidRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    @MockitoBean
    NotificationWsBroadcasterPort wsBroadcaster;

    private Long sellerId;
    private Long aliceId;
    private Long bobId;
    private Long auctionId;

    @AfterEach
    void cleanup() throws Exception {
        if (auctionId != null) {
            new TransactionTemplate(txManager).executeWithoutResult(s -> {
                bidRepo.deleteAllByAuctionId(auctionId);
                auctionRepo.findById(auctionId).ifPresent(auctionRepo::delete);
            });
        }
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                for (Long id : new Long[]{sellerId, aliceId, bobId}) {
                    if (id != null) {
                        st.execute("DELETE FROM notification WHERE user_id = " + id);
                        st.execute("DELETE FROM refresh_tokens WHERE user_id = " + id);
                        st.execute("DELETE FROM users WHERE id = " + id);
                    }
                }
            }
        }
        sellerId = aliceId = bobId = auctionId = null;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private User saveUser(String prefix) {
        return userRepo.save(User.builder()
                .email(prefix + "-" + UUID.randomUUID() + "@example.com")
                .passwordHash("hash")
                .displayName(prefix)
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .build());
    }

    private Auction activeAuction(User seller, long startingBid) {
        UUID parcelUuid = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        Auction a = auctionRepo.save(Auction.builder()
                .title("Notification Test Lot")
                .slParcelUuid(parcelUuid)
                .seller(seller)
                .status(AuctionStatus.ACTIVE)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .verificationTier(VerificationTier.SCRIPT)
                .startingBid(startingBid)
                .durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(true)
                .consecutiveWorldApiFailures(0)
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .startsAt(now.minusMinutes(5))
                .endsAt(now.plusHours(24))
                .originalEndsAt(now.plusHours(24))
                .build());
        a.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid).ownerType("agent")
                .ownerUuid(seller.getSlAvatarUuid())
                .ownerName("Seller").parcelName("Bid Notification Parcel")
                .regionName("Mainland").areaSqm(512)
                .positionX(128.0).positionY(128.0).positionZ(22.0)
                .build());
        return auctionRepo.save(a);
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void outbid_publishesNotificationToDisplacedBidder() {
        User seller = saveUser("ob-seller"); sellerId = seller.getId();
        User alice  = saveUser("ob-alice");  aliceId  = alice.getId();
        User bob    = saveUser("ob-bob");    bobId    = bob.getId();

        Auction a = new TransactionTemplate(txManager).execute(s -> activeAuction(seller, 1000L));
        auctionId = a.getId();

        // Alice bids first — she becomes high bidder.
        bidService.placeBid(auctionId, aliceId, 1000L, null);
        // Bob outbids — Alice should receive OUTBID.
        bidService.placeBid(auctionId, bobId, 1100L, null);

        List<Notification> aliceNotifs = notifRepo.findAll().stream()
                .filter(n -> n.getUser().getId().equals(aliceId))
                .filter(n -> n.getCategory() == NotificationCategory.OUTBID)
                .toList();
        assertThat(aliceNotifs).hasSize(1);
        Notification n = aliceNotifs.get(0);
        assertThat(n.getCoalesceKey()).isEqualTo("outbid:" + aliceId + ":" + auctionId);
        assertThat(((Number) n.getData().get("currentBidL")).longValue()).isEqualTo(1100L);
        assertThat(n.getData()).containsEntry("isProxyOutbid", false);

        // Bob should NOT have an OUTBID notification (he is the new high bidder).
        assertThat(notifRepo.findAll().stream()
                .filter(nn -> nn.getUser().getId().equals(bobId))
                .filter(nn -> nn.getCategory() == NotificationCategory.OUTBID)
                .toList()).isEmpty();
    }

    @Test
    void outbid_storm_coalesces_to_one_row() {
        User seller  = saveUser("storm-seller"); sellerId = seller.getId();
        User alice   = saveUser("storm-alice");  aliceId  = alice.getId();
        User bob     = saveUser("storm-bob");    bobId    = bob.getId();

        Auction a = new TransactionTemplate(txManager).execute(s -> activeAuction(seller, 1000L));
        auctionId = a.getId();

        // Alice bids first.
        bidService.placeBid(auctionId, aliceId, 1000L, null);
        // Bob outbids Alice three times in succession — Alice is displaced each time.
        bidService.placeBid(auctionId, bobId, 1100L, null);
        // Bob is now high — Alice must outbid Bob to become high again, then Bob re-outbids.
        bidService.placeBid(auctionId, aliceId, 1200L, null);
        bidService.placeBid(auctionId, bobId, 1400L, null);
        bidService.placeBid(auctionId, aliceId, 1500L, null);
        bidService.placeBid(auctionId, bobId, 1700L, null);

        // Each displacement creates/upserts the coalesced row, so Alice should have exactly 1.
        long aliceOutbidCount = notifRepo.findAll().stream()
                .filter(n -> n.getUser().getId().equals(aliceId))
                .filter(n -> n.getCategory() == NotificationCategory.OUTBID)
                .count();
        assertThat(aliceOutbidCount).isEqualTo(1L);
    }
}
