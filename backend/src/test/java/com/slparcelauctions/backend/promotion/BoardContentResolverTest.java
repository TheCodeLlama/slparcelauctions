package com.slparcelauctions.backend.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.featured.FeaturedRepository;
import com.slparcelauctions.backend.promotion.dto.FeaturedBoardPayloadDto.Source;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Integration tests for {@link BoardContentResolver} covering the three
 * fallback states: PROMO_01 (slot queue), ALGORITHMIC (featured repo),
 * and PLACEHOLDER (both empty).
 *
 * <p>The class is NOT annotated {@code @Transactional} because slot assign
 * requires committed rows (partial unique index enforcement). Cleanup is via
 * raw JDBC in {@code @AfterEach}.
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
class BoardContentResolverTest {

    @Autowired BoardContentResolver resolver;
    @Autowired FeaturedBoardSlotService slotService;
    @Autowired UserRepository userRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    @MockitoBean FeaturedRepository featuredRepository;

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
        // Default: algo repo returns nothing so tests are isolated
        when(featuredRepository.featured()).thenReturn(List.of());
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
    void promo_pool_queue_returned_as_PROMO_01() {
        User seller = seedSeller();
        Auction a = seedActiveAuction(seller);
        new TransactionTemplate(txManager).execute(s -> slotService.assign(a));

        var payload = resolver.resolve(1);

        assertThat(payload.source()).isEqualTo(Source.PROMO_01);
        assertThat(payload.listings()).hasSize(1);
        assertThat(payload.listings().get(0).publicId()).isEqualTo(a.getPublicId());
    }

    @Test
    void empty_queue_falls_back_to_algorithmic_at_per_board_offset() {
        Auction a1 = seedActiveAuction(seedSeller());
        Auction a2 = seedActiveAuction(seedSeller());
        when(featuredRepository.featured()).thenReturn(List.of(a1, a2));

        var board1 = resolver.resolve(1);
        var board2 = resolver.resolve(2);

        assertThat(board1.source()).isEqualTo(Source.ALGORITHMIC);
        assertThat(board1.listings().get(0).publicId()).isEqualTo(a1.getPublicId());
        assertThat(board2.source()).isEqualTo(Source.ALGORITHMIC);
        assertThat(board2.listings().get(0).publicId()).isEqualTo(a2.getPublicId());
    }

    @Test
    void no_promo_no_algo_returns_PLACEHOLDER() {
        when(featuredRepository.featured()).thenReturn(List.of());

        var payload = resolver.resolve(1);

        assertThat(payload.source()).isEqualTo(Source.PLACEHOLDER);
        assertThat(payload.listings()).isEmpty();
    }

    // ---------- helpers ----------

    private User seedSeller() {
        User seller = new TransactionTemplate(txManager).execute(s ->
            userRepo.save(User.builder()
                .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("bcr-test-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("BCR Test Seller")
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .balanceLindens(0L)
                .build())
        );
        userIds.add(seller.getId());
        return seller;
    }

    private Auction seedActiveAuction(User seller) {
        OffsetDateTime now = OffsetDateTime.now();
        UUID parcelUuid = UUID.randomUUID();
        Auction auction = new TransactionTemplate(txManager).execute(s -> {
            Auction a = auctionRepo.save(Auction.builder()
                .seller(seller)
                .slParcelUuid(parcelUuid)
                .title("BCR Test Auction")
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
                .parcelName("BCR Test Parcel")
                .regionName("Test Region")
                .regionMaturityRating("GENERAL")
                .areaSqm(512)
                .positionX(64.0).positionY(64.0).positionZ(22.0)
                .slurl("secondlife://Test%20Region/64/64/22")
                .build());
            return auctionRepo.save(a);
        });
        auctionIds.add(auction.getId());
        return auction;
    }
}
