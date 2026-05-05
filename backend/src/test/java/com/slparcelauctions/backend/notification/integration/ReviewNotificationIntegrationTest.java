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
import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationRepository;
import com.slparcelauctions.backend.notification.NotificationWsBroadcasterPort;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.review.Review;
import com.slparcelauctions.backend.review.ReviewRepository;
import com.slparcelauctions.backend.review.ReviewService;
import com.slparcelauctions.backend.review.ReviewedRole;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Vertical-slice integration tests for review-reveal notifications.
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
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
class ReviewNotificationIntegrationTest {

    @Autowired ReviewService reviewService;
    @Autowired ReviewRepository reviewRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired EscrowRepository escrowRepo;
    @Autowired UserRepository userRepo;
    @Autowired NotificationRepository notifRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private Long sellerId, winnerId, auctionId, escrowId, reviewId;

    @AfterEach
    void cleanup() throws Exception {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            if (reviewId != null) reviewRepo.findById(reviewId).ifPresent(reviewRepo::delete);
            if (escrowId != null) escrowRepo.findById(escrowId).ifPresent(escrowRepo::delete);
            if (auctionId != null) auctionRepo.findById(auctionId).ifPresent(auctionRepo::delete);
        });
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                for (Long id : new Long[]{sellerId, winnerId}) {
                    if (id != null) {
                        st.execute("DELETE FROM notification WHERE user_id = " + id);
                        st.execute("DELETE FROM refresh_tokens WHERE user_id = " + id);
                        st.execute("DELETE FROM users WHERE id = " + id);
                    }
                }
            }
        }
        sellerId = winnerId = auctionId = escrowId = reviewId = null;
    }

    private User newUser(String prefix) {
        return new TransactionTemplate(txManager).execute(s -> userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email(prefix + "-" + UUID.randomUUID() + "@test.com")
                .passwordHash("h")
                .displayName(prefix)
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .build()));
    }

    private void seedCompletedEscrow() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            User seller = userRepo.findById(sellerId).orElseThrow();
            User winner = userRepo.findById(winnerId).orElseThrow();
            UUID parcelUuid = UUID.randomUUID();
            Auction a = auctionRepo.save(Auction.builder()
                    .title("Review Test Lot")
                    .slParcelUuid(parcelUuid)
                    .seller(seller)
                    .status(AuctionStatus.ENDED)
                    .endOutcome(AuctionEndOutcome.SOLD)
                    .verificationMethod(VerificationMethod.UUID_ENTRY)
                    .verificationTier(VerificationTier.SCRIPT)
                    .startingBid(1000L)
                    .currentBid(1500L)
                    .currentBidderId(winner.getId())
                    .winnerUserId(winner.getId())
                    .finalBidAmount(1500L)
                    .bidCount(1)
                    .durationHours(168)
                    .snipeProtect(false)
                    .listingFeePaid(true)
                    .consecutiveWorldApiFailures(0)
                    .commissionRate(new BigDecimal("0.05"))
                    .agentFeeRate(BigDecimal.ZERO)
                    .startsAt(OffsetDateTime.now().minusDays(10))
                    .endsAt(OffsetDateTime.now().minusDays(9))
                    .originalEndsAt(OffsetDateTime.now().minusDays(9))
                    .endedAt(OffsetDateTime.now().minusDays(9))
                    .build());
            auctionId = a.getId();
            a.setParcelSnapshot(AuctionParcelSnapshot.builder()
                    .slParcelUuid(parcelUuid).ownerType("agent")
                    .ownerName("Seller").parcelName("Review Notification Parcel")
                    .regionName("Mainland").areaSqm(256)
                    .positionX(128.0).positionY(128.0).positionZ(22.0)
                    .build());
            auctionRepo.save(a);
            OffsetDateTime completedAt = OffsetDateTime.now().minusDays(8);
            Escrow e = escrowRepo.save(Escrow.builder()
                    .auction(a)
                    .state(EscrowState.COMPLETED)
                    .finalBidAmount(1500L)
                    .commissionAmt(75L)
                    .payoutAmt(1425L)
                    .paymentDeadline(OffsetDateTime.now().minusDays(9))
                    .consecutiveWorldApiFailures(0)
                    .fundedAt(OffsetDateTime.now().minusDays(9))
                    .completedAt(completedAt)
                    .build());
            escrowId = e.getId();
        });
    }

    @Test
    void reveal_publishesReviewReceivedToReviewee() {
        User seller = newUser("rev-seller"); sellerId = seller.getId();
        User winner = newUser("rev-winner"); winnerId = winner.getId();
        seedCompletedEscrow();

        // Seed a pending review (winner reviews seller)
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Auction a = auctionRepo.findById(auctionId).orElseThrow();
            User w = userRepo.findById(winnerId).orElseThrow();
            User sl = userRepo.findById(sellerId).orElseThrow();
            Review r = reviewRepo.save(Review.builder()
                    .auction(a)
                    .reviewer(w)
                    .reviewee(sl)    // winner reviews seller's SELLER role
                    .reviewedRole(ReviewedRole.SELLER)
                    .rating(4)
                    .text("Good seller")
                    .visible(false)
                    .build());
            reviewId = r.getId();
        });

        reviewService.reveal(reviewId);

        var notifs = notifRepo.findAllByUserId(sellerId).stream()
                .filter(n -> n.getCategory() == NotificationCategory.REVIEW_RECEIVED)
                .toList();
        assertThat(notifs).hasSize(1);
        assertThat(notifs.get(0).getData().get("rating")).isNotNull();

        // Winner (reviewer) should NOT get REVIEW_RECEIVED
        assertThat(notifRepo.findAllByUserId(winnerId).stream()
                .filter(n -> n.getCategory() == NotificationCategory.REVIEW_RECEIVED)
                .toList()).isEmpty();
    }
}
