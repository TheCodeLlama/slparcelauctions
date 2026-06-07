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
import com.slparcelauctions.backend.promotion.exception.NotAuctionSellerException;
import com.slparcelauctions.backend.promotion.exception.PromotionAlreadyActiveException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.wallet.exception.InsufficientAvailableBalanceException;

/**
 * Integration tests for {@link PromotionService#purchaseFeatured} against a real
 * Postgres database.
 *
 * <p>The class is NOT annotated {@code @Transactional}: the partial-index uniqueness
 * enforcement for the duplicate test requires committed rows, and the wallet-debit
 * assertions need to observe post-commit state. Cleanup is via raw JDBC in
 * {@code @AfterEach}.
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
class PromotionServiceIntegrationTest {

    @Autowired PromotionService promotionService;
    @Autowired FeaturedBoardSlotRepository slotRepo;
    @Autowired UserRepository userRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    private final List<Long> auctionIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void truncateSlots() throws Exception {
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
                    st.execute("DELETE FROM user_ledger WHERE user_id = " + id);
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
    void happy_path_debits_500_assigns_slot_flips_featured() {
        User seller = seedSellerWithBalance(10_000);
        Auction a = seedActiveAuctionFor(seller);

        PromotionService.PurchaseResult result =
                promotionService.purchaseFeatured(seller.getId(), a.getPublicId());

        assertThat(result.newBalanceLindens()).isEqualTo(9_500);
        assertThat(result.slot().getBoardIndex()).isEqualTo(1);

        Auction reloaded = new TransactionTemplate(txManager).execute(s ->
                auctionRepo.findById(a.getId()).orElseThrow());
        assertThat(reloaded.isFeatured()).isTrue();
        // Postgres TIMESTAMPTZ truncates to microseconds; compare at that resolution.
        assertThat(reloaded.getFeaturedUntil().toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS))
                .isEqualTo(a.getEndsAt().toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    @Test
    void duplicate_purchase_throws_and_rolls_back_wallet() {
        User seller = seedSellerWithBalance(10_000);
        Auction a = seedActiveAuctionFor(seller);

        promotionService.purchaseFeatured(seller.getId(), a.getPublicId());

        assertThatThrownBy(() ->
                promotionService.purchaseFeatured(seller.getId(), a.getPublicId()))
                .isInstanceOf(PromotionAlreadyActiveException.class);

        // Balance should still be 9_500, not 9_000.
        assertThat(userRepo.findById(seller.getId()).orElseThrow().getBalanceLindens())
                .isEqualTo(9_500);
    }

    @Test
    void insufficient_funds_throws_and_no_slot_created() {
        User seller = seedSellerWithBalance(100);
        Auction a = seedActiveAuctionFor(seller);

        assertThatThrownBy(() ->
                promotionService.purchaseFeatured(seller.getId(), a.getPublicId()))
                .isInstanceOf(InsufficientAvailableBalanceException.class);

        assertThat(slotRepo.findActiveByAuctionId(a.getId())).isEmpty();
    }

    @Test
    void non_seller_throws() {
        User seller = seedSellerWithBalance(10_000);
        User intruder = seedSellerWithBalance(10_000);
        Auction a = seedActiveAuctionFor(seller);

        assertThatThrownBy(() ->
                promotionService.purchaseFeatured(intruder.getId(), a.getPublicId()))
                .isInstanceOf(NotAuctionSellerException.class);
    }

    // ---------- helpers ----------

    /**
     * Save a seller with the given starting balance and record the id for cleanup.
     */
    private User seedSellerWithBalance(long balanceLindens) {
        User seller = new TransactionTemplate(txManager).execute(s ->
            userRepo.save(User.builder()
                .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("promo-test-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Promo Test Seller")
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .balanceLindens(balanceLindens)
                .build())
        );
        userIds.add(seller.getId());
        return seller;
    }

    /**
     * Save an ACTIVE auction for the given seller and record the id for cleanup.
     */
    private Auction seedActiveAuctionFor(User seller) {
        OffsetDateTime now = OffsetDateTime.now();
        UUID parcelUuid = UUID.randomUUID();
        Auction auction = new TransactionTemplate(txManager).execute(s -> {
            Auction a = auctionRepo.save(Auction.builder()
                .seller(seller)
                .slParcelUuid(parcelUuid)
                .title("Promo Test Auction")
                .status(AuctionStatus.ACTIVE)
                .verificationTier(VerificationTier.SCRIPT)
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
                .parcelName("Promo Test Parcel")
                .regionName("Test Region")
                .regionMaturityRating("GENERAL")
                .areaSqm(512)
                .positionX(64.0).positionY(64.0).positionZ(22.0)
                .build());
            return auctionRepo.save(a);
        });
        auctionIds.add(auction.getId());
        return auction;
    }
}
