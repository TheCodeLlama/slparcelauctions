package com.slparcelauctions.backend.admin;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.admin.dto.AdminFraudFlagDetailDto;
import com.slparcelauctions.backend.admin.exception.AuctionNotSuspendedException;
import com.slparcelauctions.backend.admin.exception.FraudFlagAlreadyResolvedException;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.fraud.FraudFlag;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
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
class AdminFraudFlagServiceDismissReinstateTest {

    @Autowired AdminFraudFlagService service;
    @Autowired AuctionRepository auctionRepo;
    @Autowired UserRepository userRepo;
    @Autowired FraudFlagRepository fraudFlagRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private Long sellerId;
    private Long adminId;
    private Long auctionId;
    private Long flagId;

    @BeforeEach
    void seed() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            User seller = userRepo.save(User.builder()
                .email("dismiss-seller-" + UUID.randomUUID() + "@x.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("Dismiss Test Seller")
                .build());
            sellerId = seller.getId();

            User admin = userRepo.save(User.builder()
                .email("dismiss-admin-" + UUID.randomUUID() + "@x.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("Dismiss Test Admin")
                .role(Role.ADMIN)
                .build());
            adminId = admin.getId();

            UUID parcelUuid = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = auctionRepo.save(Auction.builder()
                .seller(seller)
                .slParcelUuid(parcelUuid)
                .title("Suspended Test Auction")
                .status(AuctionStatus.SUSPENDED)
                .verificationTier(VerificationTier.SCRIPT)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .startingBid(100L)
                .durationHours(24)
                .endsAt(now.plusHours(24))
                .suspendedAt(now.minusHours(6))
                .consecutiveWorldApiFailures(0)
                .build());
            auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid)
                .ownerUuid(seller.getSlAvatarUuid())
                .ownerType("agent")
                .parcelName("Dismiss Test Parcel")
                .regionName("Test Region")
                .regionMaturityRating("GENERAL")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
            auctionRepo.save(auction);
            auctionId = auction.getId();

            FraudFlag flag = fraudFlagRepo.save(FraudFlag.builder()
                .auction(auction)
                .slParcelUuid(parcelUuid)
                .reason(FraudFlagReason.OWNERSHIP_CHANGED_TO_UNKNOWN)
                .detectedAt(now.minusHours(6))
                .resolved(false)
                .build());
            flagId = flag.getId();
        });
    }

    @AfterEach
    void cleanup() throws Exception {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            if (flagId != null) fraudFlagRepo.findById(flagId).ifPresent(fraudFlagRepo::delete);
            if (auctionId != null) {
                // clean up bot tasks first if any
                auctionRepo.findById(auctionId).ifPresent(auctionRepo::delete);
            }
        });
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                if (auctionId != null) {
                    st.execute("DELETE FROM bot_tasks WHERE auction_id = " + auctionId);
                }
                if (sellerId != null) {
                    st.execute("DELETE FROM notification WHERE user_id = " + sellerId);
                    st.execute("DELETE FROM sl_im_message WHERE user_id = " + sellerId);
                    st.execute("DELETE FROM refresh_tokens WHERE user_id = " + sellerId);
                    st.execute("DELETE FROM users WHERE id = " + sellerId);
                }
                if (adminId != null) {
                    st.execute("DELETE FROM notification WHERE user_id = " + adminId);
                    st.execute("DELETE FROM refresh_tokens WHERE user_id = " + adminId);
                    st.execute("DELETE FROM users WHERE id = " + adminId);
                }
            }
        }
        sellerId = null;
        adminId = null;
        auctionId = null;
        flagId = null;
    }

    @Test
    void dismiss_marksResolvedWithNotes_doesNotChangeAuctionStatus() {
        service.dismiss(flagId, adminId, "Dismissed — false positive");

        FraudFlag flag = fraudFlagRepo.findById(flagId).orElseThrow();
        assertThat(flag.isResolved()).isTrue();
        assertThat(flag.getAdminNotes()).isEqualTo("Dismissed — false positive");
        assertThat(flag.getResolvedAt()).isNotNull();
        assertThat(flag.getResolvedBy()).isNotNull();
        assertThat(flag.getResolvedBy().getId()).isEqualTo(adminId);

        // Auction must still be SUSPENDED — dismiss does NOT change auction state
        Auction auction = auctionRepo.findById(auctionId).orElseThrow();
        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.SUSPENDED);
    }

    @Test
    void dismiss_alreadyResolved_throws() {
        service.dismiss(flagId, adminId, "First dismiss");

        assertThatThrownBy(() -> service.dismiss(flagId, adminId, "Second dismiss"))
            .isInstanceOf(FraudFlagAlreadyResolvedException.class)
            .hasMessageContaining(flagId.toString());
    }

    @Test
    void reinstate_flipsToActive_extendsEndsAt_clearsSuspendedAt_marksResolved() {
        OffsetDateTime originalEndsAt = auctionRepo.findById(auctionId).orElseThrow().getEndsAt();
        OffsetDateTime suspendedAt = auctionRepo.findById(auctionId).orElseThrow().getSuspendedAt();

        service.reinstate(flagId, adminId, "Reinstated after review");

        Auction after = auctionRepo.findById(auctionId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(after.getSuspendedAt()).isNull();

        // endsAt should be extended by approximately the suspension duration (6h ± 5s)
        long expectedExtensionSeconds = java.time.Duration.between(suspendedAt, OffsetDateTime.now()).getSeconds();
        long actualExtensionSeconds = java.time.Duration.between(originalEndsAt, after.getEndsAt()).getSeconds();
        assertThat(Math.abs(actualExtensionSeconds - expectedExtensionSeconds)).isLessThanOrEqualTo(5);

        FraudFlag flag = fraudFlagRepo.findById(flagId).orElseThrow();
        assertThat(flag.isResolved()).isTrue();
        assertThat(flag.getAdminNotes()).isEqualTo("Reinstated after review");
        assertThat(flag.getResolvedBy().getId()).isEqualTo(adminId);
    }

    @Test
    void reinstate_auctionNotSuspended_throws() {
        // Flip auction to CANCELLED
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Auction a = auctionRepo.findById(auctionId).orElseThrow();
            a.setStatus(AuctionStatus.CANCELLED);
            auctionRepo.save(a);
        });

        assertThatThrownBy(() -> service.reinstate(flagId, adminId, "notes"))
            .isInstanceOf(AuctionNotSuspendedException.class);
    }

    @Test
    void reinstate_fallbackToFlagDetectedAt_whenSuspendedAtIsNull() {
        // Null out suspendedAt on the auction
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Auction a = auctionRepo.findById(auctionId).orElseThrow();
            a.setSuspendedAt(null);
            auctionRepo.save(a);
        });

        OffsetDateTime originalEndsAt = auctionRepo.findById(auctionId).orElseThrow().getEndsAt();
        FraudFlag flag = fraudFlagRepo.findById(flagId).orElseThrow();
        OffsetDateTime detectedAt = flag.getDetectedAt();

        service.reinstate(flagId, adminId, "Fallback reinstate");

        Auction after = auctionRepo.findById(auctionId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(AuctionStatus.ACTIVE);

        // endsAt extended by ~6h (detectedAt was now - 6h) ± 5s
        long expectedExtensionSeconds = java.time.Duration.between(detectedAt, OffsetDateTime.now()).getSeconds();
        long actualExtensionSeconds = java.time.Duration.between(originalEndsAt, after.getEndsAt()).getSeconds();
        assertThat(Math.abs(actualExtensionSeconds - expectedExtensionSeconds)).isLessThanOrEqualTo(5);
    }
}
