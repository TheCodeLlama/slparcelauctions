package com.slparcelauctions.backend.notification.slim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.Bid;
import com.slparcelauctions.backend.auction.BidRepository;
import com.slparcelauctions.backend.auction.BidService;
import com.slparcelauctions.backend.auction.BidType;
import com.slparcelauctions.backend.auction.CancellationService;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.notification.slim.SlImMessage;
import com.slparcelauctions.backend.notification.slim.SlImMessageRepository;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.testsupport.TestRegions;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Vertical-slice integration tests for fan-out SL IM delivery on auction cancellation.
 * Fixture pattern copied from CancellationFanoutIntegrationTest.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.escrow.scheduler.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.refresh-token-cleanup.enabled=false",
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false"
})
class CancellationFanoutImIntegrationTest {

    @Autowired CancellationService cancellationService;
    @Autowired BidService bidService;
    @Autowired SlImMessageRepository slImRepo;
    @Autowired UserRepository userRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired BidRepository bidRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    private Long parcelId;
    private Long auctionId;
    private final java.util.List<Long> userIds = new java.util.ArrayList<>();

    @BeforeEach
    void clean() {
        slImRepo.deleteAll();
    }

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
        } catch (Exception e) { /* best-effort */ }
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                for (Long id : userIds) {
                    if (id != null) {
                        st.execute("DELETE FROM sl_im_message WHERE user_id = " + id);
                        st.execute("DELETE FROM notification WHERE user_id = " + id);
                        st.execute("DELETE FROM cancellation_logs WHERE seller_id = " + id);
                        st.execute("DELETE FROM refresh_tokens WHERE user_id = " + id);
                        st.execute("DELETE FROM users WHERE id = " + id);
                    }
                }
            }
        }
        userIds.clear();
        auctionId = null;
        parcelId = null;
    }

    @Test
    void cancel_threeActiveBidders_eachGetsOneSlImRow() {
        User seller = saveUser(false);
        User a = saveUser(true);
        User b = saveUser(true);
        User c = saveUser(true);
        Auction au = saveAuction(seller, 1000L);

        // Seed bid rows directly (same pattern as CancellationFanoutIntegrationTest)
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Auction fresh = auctionRepo.findById(auctionId).orElseThrow();
            bidRepo.save(Bid.builder()
                .auction(fresh)
                .bidder(userRepo.findById(a.getId()).orElseThrow())
                .amount(1500L).bidType(BidType.MANUAL).build());
            bidRepo.save(Bid.builder()
                .auction(fresh)
                .bidder(userRepo.findById(b.getId()).orElseThrow())
                .amount(2000L).bidType(BidType.MANUAL).build());
            bidRepo.save(Bid.builder()
                .auction(fresh)
                .bidder(userRepo.findById(c.getId()).orElseThrow())
                .amount(2500L).bidType(BidType.MANUAL).build());
            // update auction bid count
            fresh.setBidCount(3);
            fresh.setCurrentBid(2500L);
            fresh.setCurrentBidderId(c.getId());
            auctionRepo.save(fresh);
        });

        cancellationService.cancel(auctionId, "ownership lost", null);

        // 3 IM rows, one per active bidder, all LISTING_CANCELLED_BY_SELLER.
        var rows = slImRepo.findAll();
        assertThat(rows).hasSize(3);
        assertThat(rows.stream().map(SlImMessage::getUserId).toList())
            .containsExactlyInAnyOrder(a.getId(), b.getId(), c.getId());
        assertThat(rows).allMatch(m -> m.getMessageText().contains("[SLPA] Auction cancelled"));
        assertThat(rows).allMatch(m -> m.getMessageText().contains("ownership lost"));
    }

    @Test
    void cancel_oneBidderListingStatusOff_thatBidderGetsNoSlIm() {
        User seller = saveUser(false);
        User a = saveUser(true);
        User b = saveUser(true);
        // Disable listing_status group for b
        Map<String, Object> prefs = new HashMap<>(b.getNotifySlIm());
        prefs.put("listing_status", false);
        b.setNotifySlIm(prefs);
        userRepo.save(b);

        Auction au = saveAuction(seller, 1000L);
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Auction fresh = auctionRepo.findById(auctionId).orElseThrow();
            bidRepo.save(Bid.builder()
                .auction(fresh)
                .bidder(userRepo.findById(a.getId()).orElseThrow())
                .amount(1500L).bidType(BidType.MANUAL).build());
            bidRepo.save(Bid.builder()
                .auction(fresh)
                .bidder(userRepo.findById(b.getId()).orElseThrow())
                .amount(2000L).bidType(BidType.MANUAL).build());
            fresh.setBidCount(2);
            fresh.setCurrentBid(2000L);
            fresh.setCurrentBidderId(b.getId());
            auctionRepo.save(fresh);
        });

        cancellationService.cancel(auctionId, "ownership lost", null);

        var rows = slImRepo.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getUserId()).isEqualTo(a.getId());
    }

    @Test
    void cancel_emptyActiveBidders_zeroSlImRows() {
        User seller = saveUser(false);
        saveAuction(seller, 1000L);

        cancellationService.cancel(auctionId, "no bidders", null);

        assertThat(slImRepo.findAll()).isEmpty();
    }

    // --- Fixture helpers (pattern from CancellationFanoutIntegrationTest) ---

    private User saveUser(boolean hasAvatar) {
        User u = User.builder()
            .email("u-" + UUID.randomUUID() + "@test.local")
            .passwordHash("hash")
            .displayName("TestUser")
            .verified(true)
            .cancelledWithBids(0)
            .penaltyBalanceOwed(0L)
            .build();
        if (hasAvatar) {
            u.setSlAvatarUuid(UUID.randomUUID());
        }
        u.setNotifySlImMuted(false);
        // Use default notifySlIm which has listing_status=true
        User saved = new TransactionTemplate(txManager).execute(s -> userRepo.save(u));
        userIds.add(saved.getId());
        return saved;
    }

    private Auction saveAuction(User seller, long startBidL) {
        return new TransactionTemplate(txManager).execute(s -> {
            Parcel p = parcelRepo.save(Parcel.builder()
                .region(TestRegions.mainland())
                .slParcelUuid(UUID.randomUUID())
                .ownerUuid(seller.getSlAvatarUuid() != null ? seller.getSlAvatarUuid() : UUID.randomUUID())
                .ownerType("agent")
                                                .areaSqm(256)
                                .verified(true)
                .verifiedAt(OffsetDateTime.now())
                .build());
            parcelId = p.getId();
            Auction a = auctionRepo.save(Auction.builder()
                .title("Cancellation IM Test Lot")
                .parcel(p)
                .seller(seller)
                .status(AuctionStatus.ACTIVE)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .verificationTier(VerificationTier.SCRIPT)
                .startingBid(startBidL)
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
}
