package com.slparcelauctions.backend.admin.listings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.admin.audit.AdminActionRepository;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.admin.listings.dto.AdminListingRowDto;
import com.slparcelauctions.backend.admin.listings.dto.SetFeaturedRequest;
import com.slparcelauctions.backend.admin.listings.exception.AdminListingStateException;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Integration coverage for {@link AdminListingService#setFeatured(UUID, Long,
 * SetFeaturedRequest)}. Verifies the status guard, the featured_until/featured
 * combination guard, the column write, and the admin_actions audit row.
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
class AdminListingFeaturedIntegrationTest {

    @Autowired AdminListingService adminListingService;
    @Autowired AuctionRepository auctionRepo;
    @Autowired UserRepository userRepo;
    @Autowired AdminActionRepository adminActionRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    private Long sellerId;
    private Long adminId;
    private Long auctionId;
    private UUID auctionPublicId;

    @BeforeEach
    void seed() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            User seller = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("featured-int-seller-" + UUID.randomUUID() + "@x.com")
                .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                .displayName("Featured Int Seller").build());
            sellerId = seller.getId();

            User admin = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("featured-int-admin-" + UUID.randomUUID() + "@x.com")
                .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                .displayName("Featured Int Admin").role(Role.ADMIN).build());
            adminId = admin.getId();

            UUID parcelUuid = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = auctionRepo.save(Auction.builder()
                .seller(seller)
                .slParcelUuid(parcelUuid)
                .title("Featured Integration Auction")
                .status(AuctionStatus.ACTIVE)
                .verificationTier(VerificationTier.BOT)
                .verificationMethod(VerificationMethod.SALE_TO_BOT)
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
                .endsAt(now.plusHours(48))
                .originalEndsAt(now.plusHours(48))
                .createdAt(now)
                .updatedAt(now)
                .build());
            auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid)
                .ownerUuid(seller.getSlAvatarUuid())
                .ownerType("agent")
                .parcelName("Featured Integration Parcel")
                .regionName("Test Region")
                .regionMaturityRating("GENERAL")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
            auctionRepo.save(auction);
            auctionId = auction.getId();
            auctionPublicId = auction.getPublicId();
        });
    }

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                if (auctionId != null) {
                    st.execute("DELETE FROM admin_actions WHERE target_type = 'LISTING' AND target_id = " + auctionId);
                    st.execute("DELETE FROM auction_parcel_snapshots WHERE auction_id = " + auctionId);
                    st.execute("DELETE FROM auctions WHERE id = " + auctionId);
                }
                if (sellerId != null) {
                    st.execute("DELETE FROM notification WHERE user_id = " + sellerId);
                    st.execute("DELETE FROM sl_im_message WHERE user_id = " + sellerId);
                    st.execute("DELETE FROM refresh_tokens WHERE user_id = " + sellerId);
                    st.execute("DELETE FROM users WHERE id = " + sellerId);
                }
                if (adminId != null) {
                    st.execute("DELETE FROM admin_actions WHERE admin_user_id = " + adminId);
                    st.execute("DELETE FROM notification WHERE user_id = " + adminId);
                    st.execute("DELETE FROM refresh_tokens WHERE user_id = " + adminId);
                    st.execute("DELETE FROM users WHERE id = " + adminId);
                }
            }
        }
        sellerId = null;
        adminId = null;
        auctionId = null;
        auctionPublicId = null;
    }

    @Test
    void setFeatured_happyPath_flipsFlag_writesAuditRow() {
        OffsetDateTime until = OffsetDateTime.now().plusDays(7);

        AdminListingRowDto row = adminListingService.setFeatured(
            auctionPublicId, adminId, new SetFeaturedRequest(true, until));

        assertThat(row.isFeatured()).isTrue();
        assertThat(row.featuredUntil()).isCloseTo(until,
            org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.SECONDS));

        Auction auction = auctionRepo.findById(auctionId).orElseThrow();
        assertThat(auction.isFeatured()).isTrue();
        assertThat(auction.getFeaturedUntil()).isNotNull();

        var actions = adminActionRepo.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
            AdminActionTargetType.LISTING, auctionId,
            org.springframework.data.domain.Pageable.unpaged());
        assertThat(actions.getContent()).hasSize(1);
        assertThat(actions.getContent().get(0).getActionType())
            .isEqualTo(AdminActionType.FEATURE_LISTING);
    }

    @Test
    void setFeatured_unfeaturePath_clearsFlagAndUntil_writesUnfeatureAudit() {
        // Pre-flag the auction
        adminListingService.setFeatured(
            auctionPublicId, adminId, new SetFeaturedRequest(true, OffsetDateTime.now().plusDays(7)));

        AdminListingRowDto row = adminListingService.setFeatured(
            auctionPublicId, adminId, new SetFeaturedRequest(false, null));

        assertThat(row.isFeatured()).isFalse();
        assertThat(row.featuredUntil()).isNull();

        var actions = adminActionRepo.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
            AdminActionTargetType.LISTING, auctionId,
            org.springframework.data.domain.Pageable.unpaged());
        assertThat(actions.getContent()).hasSize(2);
        assertThat(actions.getContent().get(0).getActionType())
            .isEqualTo(AdminActionType.UNFEATURE_LISTING);
        assertThat(actions.getContent().get(1).getActionType())
            .isEqualTo(AdminActionType.FEATURE_LISTING);
    }

    @Test
    void setFeatured_nullFeaturedUntil_persistsAsPermanent() {
        AdminListingRowDto row = adminListingService.setFeatured(
            auctionPublicId, adminId, new SetFeaturedRequest(true, null));

        assertThat(row.isFeatured()).isTrue();
        assertThat(row.featuredUntil()).isNull();
    }

    @Test
    void setFeatured_rejectsNonActiveStatus_400() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Auction a = auctionRepo.findById(auctionId).orElseThrow();
            a.setStatus(AuctionStatus.SUSPENDED);
            auctionRepo.save(a);
        });

        SetFeaturedRequest req = new SetFeaturedRequest(true, null);
        assertThatThrownBy(() -> adminListingService.setFeatured(auctionPublicId, adminId, req))
            .isInstanceOf(AdminListingStateException.class)
            .hasMessageContaining("SUSPENDED");
    }

    @Test
    void setFeatured_rejectsFalseWithFeaturedUntilSet() {
        SetFeaturedRequest req = new SetFeaturedRequest(false, OffsetDateTime.now().plusDays(7));
        assertThatThrownBy(() -> adminListingService.setFeatured(auctionPublicId, adminId, req))
            .isInstanceOf(AdminListingStateException.class)
            .hasMessageContaining("featuredUntil");
    }

    @Test
    void setFeatured_unknownPublicId_throws() {
        SetFeaturedRequest req = new SetFeaturedRequest(true, null);
        assertThatThrownBy(() ->
            adminListingService.setFeatured(UUID.randomUUID(), adminId, req))
            .isInstanceOf(AdminListingStateException.class);
    }
}
