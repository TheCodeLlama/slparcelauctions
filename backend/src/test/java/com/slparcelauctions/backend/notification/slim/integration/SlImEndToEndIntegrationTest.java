package com.slparcelauctions.backend.notification.slim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.BidRepository;
import com.slparcelauctions.backend.auction.BidService;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.notification.slim.SlImMessage;
import com.slparcelauctions.backend.notification.slim.SlImMessageRepository;
import com.slparcelauctions.backend.notification.slim.SlImMessageStatus;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * E2E contract test: event → SL IM queue → polling endpoint → confirmation,
 * asserting status transitions and idempotent re-confirmation behavior.
 * Fixture helpers copied from OutbidImIntegrationTest (Task 3).
 */
@SpringBootTest
@AutoConfigureMockMvc
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
    "slpa.ownership-monitor.enabled=false",
    "slpa.notifications.sl-im.dispatcher.shared-secret=test-e2e-secret",
    "slpa.notifications.sl-im.dispatcher.max-batch-limit=50"
})
class SlImEndToEndIntegrationTest {

    @Autowired BidService bidService;
    @Autowired SlImMessageRepository slImRepo;
    @Autowired UserRepository userRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired BidRepository bidRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;
    @Autowired MockMvc mvc;

    private static final String AUTH = "Bearer test-e2e-secret";

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
    void eventThroughTerminalContract_endToEnd() throws Exception {
        // Driver event: outbid creates an SL IM row for the displaced bidder.
        User seller = saveUserNoAvatar();
        User alice  = saveUserWithAvatar();
        User bob    = saveUserWithAvatar();
        Auction a   = saveAuction(seller, 1000L);

        bidService.placeBid(a.getId(), alice.getId(), 1500L, "127.0.0.1");
        bidService.placeBid(a.getId(), bob.getId(),   2000L, "127.0.0.1");

        // Step 1: assert the IM row materializes for alice (the displaced bidder).
        var aliceRows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(alice.getId()))
            .toList();
        assertThat(aliceRows).hasSize(1);
        long imId = aliceRows.get(0).getId();
        assertThat(aliceRows.get(0).getStatus()).isEqualTo(SlImMessageStatus.PENDING);

        // Step 2: poll endpoint returns it.
        mvc.perform(get("/api/v1/internal/sl-im/pending?limit=10")
                .header("Authorization", AUTH))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.messages.length()").value(greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.messages[?(@.id == " + imId + ")]").exists());

        // Step 3: confirm delivered.
        mvc.perform(post("/api/v1/internal/sl-im/" + imId + "/delivered")
                .header("Authorization", AUTH))
            .andExpect(status().isNoContent());

        SlImMessage delivered = slImRepo.findById(imId).orElseThrow();
        assertThat(delivered.getStatus()).isEqualTo(SlImMessageStatus.DELIVERED);
        assertThat(delivered.getDeliveredAt()).isNotNull();
        var deliveredAtFirstCall = delivered.getDeliveredAt();

        // Step 4: idempotent second confirmation — 204, delivered_at NOT re-stamped.
        mvc.perform(post("/api/v1/internal/sl-im/" + imId + "/delivered")
                .header("Authorization", AUTH))
            .andExpect(status().isNoContent());

        SlImMessage afterIdempotent = slImRepo.findById(imId).orElseThrow();
        assertThat(afterIdempotent.getDeliveredAt()).isEqualTo(deliveredAtFirstCall);
        assertThat(afterIdempotent.getAttempts()).isEqualTo(1); // not incremented on idempotent
    }

    // --- Fixture helpers (pattern from OutbidImIntegrationTest) ---

    private User saveUserNoAvatar() {
        return saveUser(false);
    }

    private User saveUserWithAvatar() {
        return saveUser(true);
    }

    private User saveUser(boolean hasAvatar) {
        User u = User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
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
        prefs.put("bidding", true);
        prefs.put("auction_result", true);
        prefs.put("escrow", true);
        prefs.put("listing_status", true);
        prefs.put("reviews", true);
        prefs.put("system", true);
        u.setNotifySlIm(prefs);
        User saved = userRepo.save(u);
        userIds.add(saved.getId());
        return saved;
    }

    private Auction saveAuction(User seller, long startBidL) {
        UUID parcelUuid = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        Auction a = auctionRepo.save(Auction.builder()
            .title("E2E SL IM Test Lot")
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
            .ownerName("Seller").parcelName("E2E SL IM Test Parcel")
            .regionName("Mainland").areaSqm(512)
            .positionX(128.0).positionY(128.0).positionZ(22.0)
            .build());
        return auctionRepo.save(a);
    }
}
