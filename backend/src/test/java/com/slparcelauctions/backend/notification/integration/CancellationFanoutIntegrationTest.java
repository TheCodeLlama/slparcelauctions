package com.slparcelauctions.backend.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
import com.slparcelauctions.backend.auction.Bid;
import com.slparcelauctions.backend.auction.BidRepository;
import com.slparcelauctions.backend.auction.BidType;
import com.slparcelauctions.backend.auction.CancellationService;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationRepository;
import com.slparcelauctions.backend.notification.NotificationWsBroadcasterPort;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Vertical-slice integration tests for cancellation fan-out notifications.
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
        "slpa.notifications.cleanup.enabled=false"
})
class CancellationFanoutIntegrationTest {

    @Autowired CancellationService cancellationService;
    @Autowired AuctionRepository auctionRepo;
    @Autowired BidRepository bidRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired UserRepository userRepo;
    @Autowired NotificationRepository notifRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private Long sellerId, bidder1Id, bidder2Id, auctionId, parcelId;

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                if (auctionId != null) {
                    st.execute("DELETE FROM cancellation_logs WHERE auction_id = " + auctionId);
                    st.execute("DELETE FROM bids WHERE auction_id = " + auctionId);
                    st.execute("DELETE FROM auctions WHERE id = " + auctionId);
                }
                if (parcelId != null) {
                    st.execute("DELETE FROM parcels WHERE id = " + parcelId);
                }
            }
        } catch (Exception e2) {
            // Best-effort cleanup
        }
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                for (Long id : new Long[]{sellerId, bidder1Id, bidder2Id}) {
                    if (id != null) {
                        st.execute("DELETE FROM notification WHERE user_id = " + id);
                        st.execute("DELETE FROM cancellation_logs WHERE seller_id = " + id);
                        st.execute("DELETE FROM refresh_tokens WHERE user_id = " + id);
                        st.execute("DELETE FROM users WHERE id = " + id);
                    }
                }
            }
        }
        sellerId = bidder1Id = bidder2Id = auctionId = parcelId = null;
    }

    private User newUser(String prefix) {
        return new TransactionTemplate(txManager).execute(s -> userRepo.save(User.builder()
                .email(prefix + "-" + UUID.randomUUID() + "@test.com")
                .passwordHash("h")
                .displayName(prefix)
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .cancelledWithBids(0)
                .penaltyBalanceOwed(0L)
                .build()));
    }

    private Auction buildActiveAuction(User seller, int bidCount, User topBidder, long currentBid) {
        return new TransactionTemplate(txManager).execute(s -> {
            Parcel p = parcelRepo.save(Parcel.builder()
                    .slParcelUuid(UUID.randomUUID())
                    .ownerUuid(seller.getSlAvatarUuid())
                    .ownerType("agent")
                    .regionName("CancelRegion")
                    .continentName("Sansara")
                    .areaSqm(256)
                    .maturityRating("GENERAL")
                    .verified(true)
                    .verifiedAt(OffsetDateTime.now())
                    .build());
            parcelId = p.getId();
            Auction a = auctionRepo.save(Auction.builder()
                    .title("Cancellation Test Lot")
                    .parcel(p)
                    .seller(seller)
                    .status(AuctionStatus.ACTIVE)
                    .verificationMethod(VerificationMethod.UUID_ENTRY)
                    .verificationTier(VerificationTier.SCRIPT)
                    .startingBid(1000L)
                    .currentBid(bidCount > 0 ? currentBid : 0L)
                    .currentBidderId(topBidder != null ? topBidder.getId() : null)
                    .bidCount(bidCount)
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
    void cancel_withBidders_fansOutToEachBidder() {
        User seller  = newUser("fan-seller");  sellerId  = seller.getId();
        User bidder1 = newUser("fan-bidder1"); bidder1Id = bidder1.getId();
        User bidder2 = newUser("fan-bidder2"); bidder2Id = bidder2.getId();

        Auction a = buildActiveAuction(seller, 2, bidder1, 1500L);

        // Add bid rows for both bidders
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Auction fresh = auctionRepo.findById(auctionId).orElseThrow();
            bidRepo.save(Bid.builder()
                    .auction(fresh)
                    .bidder(userRepo.findById(bidder1Id).orElseThrow())
                    .amount(1000L).bidType(BidType.MANUAL).build());
            bidRepo.save(Bid.builder()
                    .auction(fresh)
                    .bidder(userRepo.findById(bidder2Id).orElseThrow())
                    .amount(1500L).bidType(BidType.MANUAL).build());
        });

        cancellationService.cancel(auctionId, "changed mind");

        // Both bidders should have LISTING_CANCELLED_BY_SELLER
        assertThat(notifRepo.findAll().stream()
                .filter(n -> n.getUser().getId().equals(bidder1Id))
                .filter(n -> n.getCategory() == NotificationCategory.LISTING_CANCELLED_BY_SELLER)
                .count()).isEqualTo(1L);
        assertThat(notifRepo.findAll().stream()
                .filter(n -> n.getUser().getId().equals(bidder2Id))
                .filter(n -> n.getCategory() == NotificationCategory.LISTING_CANCELLED_BY_SELLER)
                .count()).isEqualTo(1L);
        // Seller should NOT get LISTING_CANCELLED_BY_SELLER
        assertThat(notifRepo.findAll().stream()
                .filter(n -> n.getUser().getId().equals(sellerId))
                .filter(n -> n.getCategory() == NotificationCategory.LISTING_CANCELLED_BY_SELLER)
                .count()).isEqualTo(0L);
    }

    @Test
    void cancel_withNoBidders_publishesNoNotifications() {
        User seller = newUser("nobid-cancel-seller"); sellerId = seller.getId();
        buildActiveAuction(seller, 0, null, 0L);

        cancellationService.cancel(auctionId, "testing");

        // No bidders → no LISTING_CANCELLED_BY_SELLER notifications for anyone.
        // Check specifically: no notification for the seller and no other recipients
        // (we can't assert the total table since other tests may leave rows).
        // We verify there's no notification for the seller (who doesn't get this category anyway).
        assertThat(notifRepo.findAll().stream()
                .filter(n -> n.getUser().getId().equals(sellerId))
                .filter(n -> n.getCategory() == NotificationCategory.LISTING_CANCELLED_BY_SELLER)
                .count()).isEqualTo(0L);
        // And the total for this category should be 0 for this run
        // (since we have no other bidders seeded in this test)
        long cancelNotifCount = notifRepo.findAll().stream()
                .filter(n -> n.getCategory() == NotificationCategory.LISTING_CANCELLED_BY_SELLER)
                .filter(n -> n.getData() != null
                        && n.getData().containsKey("auctionId")
                        && Long.valueOf(auctionId).equals(
                                ((Number) n.getData().get("auctionId")).longValue()))
                .count();
        assertThat(cancelNotifCount).isEqualTo(0L);
    }
}
