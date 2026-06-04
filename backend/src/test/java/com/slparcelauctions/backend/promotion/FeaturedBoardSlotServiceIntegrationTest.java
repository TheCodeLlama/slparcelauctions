package com.slparcelauctions.backend.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.promotion.exception.PromotionAlreadyActiveException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Integration tests for {@link FeaturedBoardSlotService} assign and release
 * flows against a real Postgres database.
 *
 * <p>{@code assign} runs under {@code Propagation.MANDATORY}, so every call
 * is wrapped in an explicit {@link TransactionTemplate} block. The test class
 * is NOT annotated {@code @Transactional} because the board-balance test needs
 * multiple committed transactions to exercise cross-test state (and because the
 * rollback approach would hide the partial-index enforcement for the duplicate
 * test). Cleanup is via raw JDBC in {@code @AfterEach}.
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
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.realty.invitation-expiry.enabled=false"
})
class FeaturedBoardSlotServiceIntegrationTest {

    @Autowired FeaturedBoardSlotService slotService;
    @Autowired FeaturedBoardSlotRepository slotRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired UserRepository userRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    /** IDs of rows created by the current test -- used for JDBC cleanup. */
    private final List<Long> auctionIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void truncateSlots() throws Exception {
        // Wipe all board-slot rows before each test so leftover rows from a
        // prior run (or prior test) don't skew the least-loaded-board selection.
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                st.execute("DELETE FROM featured_board_slots");
            }
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                st.execute("DELETE FROM featured_board_slots");
                for (Long id : auctionIds) {
                    st.execute("DELETE FROM auction_parcel_snapshots WHERE auction_id = " + id);
                    st.execute("DELETE FROM auctions WHERE id = " + id);
                }
                for (Long id : userIds) {
                    st.execute("DELETE FROM notification WHERE user_id = " + id);
                    st.execute("DELETE FROM sl_im_message WHERE user_id = " + id);
                    st.execute("DELETE FROM refresh_tokens WHERE user_id = " + id);
                    st.execute("DELETE FROM users WHERE id = " + id);
                }
            }
        }
        auctionIds.clear();
        userIds.clear();
    }

    // ---------- tests ----------

    @Test
    void first_purchase_lands_on_board_one_position_zero() {
        Auction auction = seedAuction(seedSeller());

        FeaturedBoardSlot slot = assignInTx(auction);

        assertThat(slot.getBoardIndex()).isEqualTo(1);
        assertThat(slot.getPosition()).isEqualTo(0);
        assertThat(slot.getReleasedAt()).isNull();
    }

    @Test
    void six_purchases_balance_across_five_boards() {
        // 5 boards configured (slpa.promotions.featured-slot-count=5).
        // Expect: 1->1, 2->2, 3->3, 4->4, 5->5, 6->1 (position 1 on board 1).
        List<FeaturedBoardSlot> slots = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Auction a = seedAuction(seedSeller());
            slots.add(assignInTx(a));
        }

        assertThat(slots.get(0).getBoardIndex()).isEqualTo(1);
        assertThat(slots.get(0).getPosition()).isEqualTo(0);

        assertThat(slots.get(1).getBoardIndex()).isEqualTo(2);
        assertThat(slots.get(1).getPosition()).isEqualTo(0);

        assertThat(slots.get(2).getBoardIndex()).isEqualTo(3);
        assertThat(slots.get(2).getPosition()).isEqualTo(0);

        assertThat(slots.get(3).getBoardIndex()).isEqualTo(4);
        assertThat(slots.get(3).getPosition()).isEqualTo(0);

        assertThat(slots.get(4).getBoardIndex()).isEqualTo(5);
        assertThat(slots.get(4).getPosition()).isEqualTo(0);

        // 6th lands back on board 1 at position 1
        assertThat(slots.get(5).getBoardIndex()).isEqualTo(1);
        assertThat(slots.get(5).getPosition()).isEqualTo(1);
    }

    @Test
    void duplicate_purchase_fails_with_PromotionAlreadyActive() {
        Auction auction = seedAuction(seedSeller());
        assignInTx(auction);

        assertThatThrownBy(() -> assignInTx(auction))
            .isInstanceOf(PromotionAlreadyActiveException.class);
    }

    @Test
    void release_drops_active_row_and_frees_board_for_reuse() {
        // Assign A to board 1, B to board 2, release A, assign C -> board 1.
        Auction auctionA = seedAuction(seedSeller());
        Auction auctionB = seedAuction(seedSeller());
        Auction auctionC = seedAuction(seedSeller());

        FeaturedBoardSlot slotA = assignInTx(auctionA);
        assignInTx(auctionB);

        assertThat(slotA.getBoardIndex()).isEqualTo(1);

        slotService.releaseForAuction(auctionA.getId());

        // Verify the row is now released
        FeaturedBoardSlot released = slotRepo.findById(slotA.getId()).orElseThrow();
        assertThat(released.getReleasedAt()).isNotNull();

        // Board 1 is now the least-loaded board again (0 active vs board 2 has 1)
        FeaturedBoardSlot slotC = assignInTx(auctionC);
        assertThat(slotC.getBoardIndex()).isEqualTo(1);
    }

    @Test
    void release_for_nonexistent_auction_is_noop() {
        // Must not throw for an auction id that has no active slot
        long nonexistentId = Long.MAX_VALUE;
        slotService.releaseForAuction(nonexistentId);
        // If we reach here the noop contract is satisfied
    }

    // ---------- helpers ----------

    /**
     * Save a new unique seller and record the id for cleanup.
     */
    private User seedSeller() {
        User seller = new TransactionTemplate(txManager).execute(s ->
            userRepo.save(User.builder()
                .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("slot-test-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("Slot Test Seller")
                .verified(true)
                .build())
        );
        userIds.add(seller.getId());
        return seller;
    }

    /**
     * Save a new ACTIVE auction for the given seller and record the id for cleanup.
     */
    private Auction seedAuction(User seller) {
        OffsetDateTime now = OffsetDateTime.now();
        UUID parcelUuid = UUID.randomUUID();
        Auction auction = new TransactionTemplate(txManager).execute(s -> {
            Auction a = auctionRepo.save(Auction.builder()
                .seller(seller)
                .slParcelUuid(parcelUuid)
                .title("Slot Test Auction")
                .status(AuctionStatus.ACTIVE)
                .verificationTier(VerificationTier.BOT)
                .verifiedAt(now)
                .startingBid(1_000L)
                .currentBid(0L)
                .bidCount(0)
                .durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(true)
                .listingFeeAmt(100L)
                .consecutiveWorldApiFailures(0)
                .commissionRate(new BigDecimal("0.05"))
                .startsAt(now.minusHours(1))
                .endsAt(now.plusHours(47))
                .originalEndsAt(now.plusHours(47))
                .createdAt(now)
                .updatedAt(now)
                .build());
            a.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid)
                .ownerUuid(seller.getSlAvatarUuid())
                .ownerType("agent")
                .parcelName("Slot Test Parcel")
                .regionName("Test Region")
                .regionMaturityRating("GENERAL")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
            return auctionRepo.save(a);
        });
        auctionIds.add(auction.getId());
        return auction;
    }

    /**
     * Wrap {@link FeaturedBoardSlotService#assign} in a transaction so the
     * {@code Propagation.MANDATORY} requirement is satisfied.
     */
    private FeaturedBoardSlot assignInTx(Auction auction) {
        return new TransactionTemplate(txManager).execute(s ->
            slotService.assign(auction)
        );
    }
}
