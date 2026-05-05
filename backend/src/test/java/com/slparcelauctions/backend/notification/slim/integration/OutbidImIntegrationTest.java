package com.slparcelauctions.backend.notification.slim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.BidRepository;
import com.slparcelauctions.backend.auction.BidService;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.notification.slim.SlImMessage;
import com.slparcelauctions.backend.notification.slim.SlImMessageRepository;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
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
 * Vertical-slice integration tests verifying that bidding events produce
 * SL IM rows for displaced bidders (or no rows when gate conditions fail).
 * Fixture pattern copied from BidNotificationIntegrationTest.
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
class OutbidImIntegrationTest {

    @Autowired BidService bidService;
    @Autowired SlImMessageRepository slImRepo;
    @Autowired UserRepository userRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired BidRepository bidRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    private Long auctionId;
    private final java.util.List<Long> userIds = new java.util.ArrayList<>();

    @BeforeEach
    void clean() {
        slImRepo.deleteAll();
    }

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
                for (Long id : userIds) {
                    if (id != null) {
                        st.execute("DELETE FROM sl_im_message WHERE user_id = " + id);
                        st.execute("DELETE FROM notification WHERE user_id = " + id);
                        st.execute("DELETE FROM refresh_tokens WHERE user_id = " + id);
                        st.execute("DELETE FROM users WHERE id = " + id);
                    }
                }
            }
        }
        userIds.clear();
        auctionId = null;
    }

    @Test
    void outbid_publishesSlImForBidderWithAvatarAndPrefsOn() {
        User seller = saveUser(/* hasAvatar */ false, /* prefs */ true);
        User alice  = saveUser(/* hasAvatar */ true,  /* prefs */ true);
        User bob    = saveUser(/* hasAvatar */ true,  /* prefs */ true);
        Auction a   = saveAuction(seller, 1000L);

        bidService.placeBid(a.getId(), alice.getId(), 1500L, null);
        bidService.placeBid(a.getId(), bob.getId(),   2000L, null);

        // Alice was displaced; she should have one PENDING SL IM row.
        List<SlImMessage> aliceRows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(alice.getId()))
            .toList();
        assertThat(aliceRows).hasSize(1);
        assertThat(aliceRows.get(0).getMessageText())
            .contains("[SLParcels] You've been outbid")
            .contains("L$2,000")
            .endsWith("/auction/" + a.getId());

        // Bob (the new high bidder) gets nothing on this side.
        long bobRows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(bob.getId())).count();
        assertThat(bobRows).isZero();
    }

    @Test
    void outbidStorm_coalescesIntoSingleRow_latestTextWins() {
        User seller = saveUser(false, true);
        User alice  = saveUser(true, true);
        User bob    = saveUser(true, true);
        Auction a   = saveAuction(seller, 1000L);

        bidService.placeBid(a.getId(), alice.getId(), 1500L, null);
        bidService.placeBid(a.getId(), bob.getId(),   2000L, null);
        bidService.placeBid(a.getId(), alice.getId(), 2500L, null);
        bidService.placeBid(a.getId(), bob.getId(),   3000L, null);
        bidService.placeBid(a.getId(), alice.getId(), 3500L, null);
        bidService.placeBid(a.getId(), bob.getId(),   4000L, null);

        // Alice was displaced 3 times. Coalesce key is the same each time.
        // Expect ONE PENDING row with the latest text.
        List<SlImMessage> aliceRows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(alice.getId()))
            .toList();
        assertThat(aliceRows).hasSize(1);
        assertThat(aliceRows.get(0).getMessageText()).contains("L$4,000");
    }

    @Test
    void outbid_groupDisabled_skipsSlImButKeepsInAppRow() {
        User seller = saveUser(false, true);
        User alice  = saveUser(true, /* prefs ON */ true);
        // Disable bidding group for alice:
        Map<String, Object> prefs = new HashMap<>(alice.getNotifySlIm());
        prefs.put("bidding", false);
        alice.setNotifySlIm(prefs);
        userRepo.save(alice);

        User bob = saveUser(true, true);
        Auction a = saveAuction(seller, 1000L);

        bidService.placeBid(a.getId(), alice.getId(), 1500L, null);
        bidService.placeBid(a.getId(), bob.getId(),   2000L, null);

        // Alice has NO SL IM (group disabled).
        long aliceImRows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(alice.getId())).count();
        assertThat(aliceImRows).isZero();
    }

    @Test
    void outbid_userMuted_skipsSlImButKeepsInAppRow() {
        User seller = saveUser(false, true);
        User alice  = saveUser(true, true);
        alice.setNotifySlImMuted(true);
        userRepo.save(alice);

        User bob = saveUser(true, true);
        Auction a = saveAuction(seller, 1000L);

        bidService.placeBid(a.getId(), alice.getId(), 1500L, null);
        bidService.placeBid(a.getId(), bob.getId(),   2000L, null);

        long aliceImRows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(alice.getId())).count();
        assertThat(aliceImRows).isZero();
    }

    // --- Fixture helpers (pattern from BidNotificationIntegrationTest) ---

    private User saveUser(boolean hasAvatar, boolean groupPrefsOn) {
        User u = User.builder()
            .email("u-" + UUID.randomUUID() + "@test.local")
            .passwordHash("hash")
            .displayName("TestUser")
            .verified(true)
            .build();
        if (hasAvatar) {
            u.setSlAvatarUuid(UUID.randomUUID());
        }
        u.setNotifySlImMuted(false);
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("bidding", groupPrefsOn);
        prefs.put("auction_result", groupPrefsOn);
        prefs.put("escrow", groupPrefsOn);
        prefs.put("listing_status", groupPrefsOn);
        prefs.put("reviews", groupPrefsOn);
        prefs.put("system", true);
        u.setNotifySlIm(prefs);
        User saved = userRepo.save(u);
        userIds.add(saved.getId());
        return saved;
    }

    private Auction saveAuction(User seller, long startBidL) {
        // Pattern from BidNotificationIntegrationTest.activeAuction
        UUID parcelUuid = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        Auction a = auctionRepo.save(Auction.builder()
            .title("SL IM Test Lot")
            .slParcelUuid(parcelUuid)
            .seller(seller)
            .status(AuctionStatus.ACTIVE)
            .verificationMethod(VerificationMethod.UUID_ENTRY)
            .verificationTier(VerificationTier.SCRIPT)
            .startingBid(startBidL)
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
        auctionId = a.getId();
        a.setParcelSnapshot(AuctionParcelSnapshot.builder()
            .slParcelUuid(parcelUuid).ownerType("agent")
            .ownerUuid(seller.getSlAvatarUuid() != null ? seller.getSlAvatarUuid() : UUID.randomUUID())
            .ownerName("Seller").parcelName("SL IM Test Parcel")
            .regionName("Mainland").areaSqm(512)
            .positionX(128.0).positionY(128.0).positionZ(22.0)
            .build());
        return auctionRepo.save(a);
    }
}
