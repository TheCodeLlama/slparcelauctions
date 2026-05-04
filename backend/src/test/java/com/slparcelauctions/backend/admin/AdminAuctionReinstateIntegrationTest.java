package com.slparcelauctions.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
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

import com.slparcelauctions.backend.admin.audit.AdminActionRepository;
import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.notification.Notification;
import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationRepository;
import com.slparcelauctions.backend.notification.NotificationWsBroadcasterPort;
import com.slparcelauctions.backend.user.Role;
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
class AdminAuctionReinstateIntegrationTest {

    @Autowired AdminAuctionService adminAuctionService;
    @Autowired AdminActionService adminActionService;
    @Autowired AuctionRepository auctionRepo;
    @Autowired UserRepository userRepo;
    @Autowired NotificationRepository notificationRepo;
    @Autowired AdminActionRepository adminActionRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private Long sellerId;
    private Long adminId;
    private Long auctionId;

    @BeforeEach
    void seed() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            User seller = userRepo.save(User.builder()
                .email("reinstate-auction-int-seller-" + UUID.randomUUID() + "@x.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("Reinstate Auction Int Seller")
                .build());
            sellerId = seller.getId();

            User admin = userRepo.save(User.builder()
                .email("reinstate-auction-int-admin-" + UUID.randomUUID() + "@x.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("Reinstate Auction Int Admin")
                .role(Role.ADMIN)
                .build());
            adminId = admin.getId();

            UUID parcelUuid = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = auctionRepo.save(Auction.builder()
                .seller(seller)
                .slParcelUuid(parcelUuid)
                .title("Admin Reinstate Integration Auction")
                .status(AuctionStatus.SUSPENDED)
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
                .endsAt(now.plusHours(2))
                .originalEndsAt(now.plusHours(2))
                .suspendedAt(now.minusHours(3))
                .createdAt(now)
                .updatedAt(now)
                .build());
            auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid)
                .ownerUuid(seller.getSlAvatarUuid())
                .ownerType("agent")
                .parcelName("Reinstate Integration Parcel")
                .regionName("Test Region")
                .regionMaturityRating("GENERAL")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
            auctionRepo.save(auction);
            auctionId = auction.getId();
        });
    }

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                if (auctionId != null) {
                    st.execute("DELETE FROM bot_tasks WHERE auction_id = " + auctionId);
                    st.execute("DELETE FROM admin_actions WHERE target_type = 'LISTING' AND target_id = " + auctionId);
                    st.execute("DELETE FROM auction_tags WHERE auction_id = " + auctionId);
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
    }

    @Test
    void reinstate_fullFlow_flipsActive_clearsSuspendedAt_publishesNotification_writesAdminAction() {
        // Call service.reinstate (the shared primitive) then adminActionService.record (as controller does)
        adminAuctionService.reinstate(auctionId, Optional.empty());
        adminActionService.record(adminId,
            AdminActionType.REINSTATE_LISTING,
            AdminActionTargetType.LISTING,
            auctionId,
            "Admin reinstated from user-detail page",
            null);

        // Auction: status = ACTIVE, suspendedAt = null
        Auction auction = auctionRepo.findById(auctionId).orElseThrow();
        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(auction.getSuspendedAt()).isNull();

        // Notification: LISTING_REINSTATED for seller
        List<Notification> notifications = notificationRepo.findAllByUserId(sellerId).stream()
            .filter(n -> n.getCategory() == NotificationCategory.LISTING_REINSTATED)
            .toList();
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).getTitle()).contains("Admin Reinstate Integration Auction");

        // AdminAction row: REINSTATE_LISTING + LISTING + auctionId
        var actions = adminActionRepo.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
            AdminActionTargetType.LISTING, auctionId, org.springframework.data.domain.Pageable.unpaged());
        assertThat(actions.getContent()).hasSize(1);
        var action = actions.getContent().get(0);
        assertThat(action.getActionType()).isEqualTo(AdminActionType.REINSTATE_LISTING);
        assertThat(action.getTargetType()).isEqualTo(AdminActionTargetType.LISTING);
        assertThat(action.getTargetId()).isEqualTo(auctionId);
        assertThat(action.getAdminUser().getId()).isEqualTo(adminId);
    }
}
