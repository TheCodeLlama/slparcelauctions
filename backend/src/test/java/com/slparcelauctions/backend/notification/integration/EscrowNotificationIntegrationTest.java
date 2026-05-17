package com.slparcelauctions.backend.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
import com.slparcelauctions.backend.auction.BidRepository;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowService;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.escrow.EscrowTransactionRepository;
import com.slparcelauctions.backend.escrow.FreezeReason;
import com.slparcelauctions.backend.escrow.EscrowDisputeReasonCategory;
import com.slparcelauctions.backend.escrow.dto.EscrowDisputeRequest;
import com.slparcelauctions.backend.escrow.terminal.TerminalRepository;
import com.slparcelauctions.backend.notification.Notification;
import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationRepository;
import com.slparcelauctions.backend.notification.NotificationWsBroadcasterPort;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Vertical-slice integration tests for escrow lifecycle notifications.
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
class EscrowNotificationIntegrationTest {

    private static final String SHARED_SECRET = "dev-escrow-secret-do-not-use-in-prod";

    @Autowired EscrowService escrowService;
    @Autowired EscrowRepository escrowRepo;
    @Autowired EscrowTransactionRepository ledgerRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired BidRepository bidRepo;
    @Autowired UserRepository userRepo;
    @Autowired NotificationRepository notifRepo;
    @Autowired TerminalRepository terminalRepo;
    @Autowired com.slparcelauctions.backend.auction.fraud.FraudFlagRepository fraudFlagRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private Long sellerId, winnerId, auctionId, escrowId;
    private String terminalId;

    @AfterEach
    void cleanup() throws Exception {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            if (escrowId != null) {
                ledgerRepo.findByEscrowIdOrderByCreatedAtAsc(escrowId).forEach(ledgerRepo::delete);
                escrowRepo.findById(escrowId).ifPresent(escrowRepo::delete);
            }
            if (auctionId != null) {
                fraudFlagRepo.findByAuctionId(auctionId).forEach(fraudFlagRepo::delete);
                bidRepo.deleteAllByAuctionId(auctionId);
                auctionRepo.findById(auctionId).ifPresent(auctionRepo::delete);
            }
            if (terminalId != null) terminalRepo.findById(terminalId).ifPresent(terminalRepo::delete);
        });
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                for (Long id : new Long[]{sellerId, winnerId}) {
                    if (id != null) {
                        st.execute("DELETE FROM notification WHERE user_id = " + id);
                        st.execute("DELETE FROM refresh_tokens WHERE user_id = " + id);
                        // Wallet-model refund migration: ESCROW_REFUND
                        // ledger row written when escrow freezes; clear
                        // before deleting the user.
                        st.execute("DELETE FROM user_ledger WHERE user_id = " + id);
                        st.execute("DELETE FROM users WHERE id = " + id);
                    }
                }
            }
        }
        sellerId = winnerId = auctionId = escrowId = null;
        terminalId = null;
    }

    // â”€â”€ helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private User newUser(String prefix) {
        return new TransactionTemplate(txManager).execute(s -> userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email(prefix + "-" + UUID.randomUUID() + "@test.com")
                .passwordHash("h")
                .displayName(prefix)
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .build()));
    }

    private void seedPendingEscrow() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            User seller = userRepo.findById(sellerId).orElseThrow();
            User winner = userRepo.findById(winnerId).orElseThrow();
            UUID parcelUuid = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now();
            Auction a = auctionRepo.save(Auction.builder()
                    .title("Escrow Test Lot")
                    .slParcelUuid(parcelUuid)
                    .seller(seller)
                    .status(AuctionStatus.TRANSFER_PENDING)
                    .endOutcome(AuctionEndOutcome.SOLD)

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
                    .startsAt(now.minusHours(25))
                    .endsAt(now.minusHours(1))
                    .originalEndsAt(now.minusHours(1))
                    .endedAt(now.minusHours(1))
                    .build());
            auctionId = a.getId();
            a.setParcelSnapshot(AuctionParcelSnapshot.builder()
                    .slParcelUuid(parcelUuid).ownerType("agent")
                    .ownerName("Seller").parcelName("Escrow Notification Parcel")
                    .regionName("Mainland").areaSqm(256)
                    .positionX(128.0).positionY(128.0).positionZ(22.0)
                    .build());
            auctionRepo.save(a);
            Escrow e = escrowRepo.save(Escrow.builder()
                    .auction(a)
                    .state(EscrowState.ESCROW_PENDING)
                    .finalBidAmount(1500L)
                    .commissionAmt(75L)
                    .payoutAmt(1425L)
                    .consecutiveWorldApiFailures(0)
                    .build());
            escrowId = e.getId();
        });
    }

    private void seedTransferPendingEscrow() {
        seedPendingEscrow();
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Escrow e = escrowRepo.findById(escrowId).orElseThrow();
            e.setState(EscrowState.TRANSFER_PENDING);
            e.setFundedAt(OffsetDateTime.now());
            e.setTransferDeadline(OffsetDateTime.now().plusHours(72));
            escrowRepo.save(e);
        });
    }

    private List<Notification> notifFor(Long userId, NotificationCategory category) {
        return notifRepo.findAllByUserId(userId).stream()
                .filter(n -> n.getCategory() == category)
                .toList();
    }

    // â”€â”€ tests â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    // Wallet-only escrow funding (spec 2026-05-16): the terminal payment
    // path is now a defensive refund-only branch and no longer fires
    // ESCROW_FUNDED. The seller-only fanout for that notification is
    // covered alongside auction-end auto-funding in EscrowCreateOnAuctionEndIntegrationTest.

    @Test
    void disputed_publishesToBothParties() {
        User seller = newUser("esc-dis-seller"); sellerId = seller.getId();
        User winner = newUser("esc-dis-winner"); winnerId = winner.getId();
        seedPendingEscrow();

        new TransactionTemplate(txManager).executeWithoutResult(s ->
                escrowService.fileDispute(auctionId,
                        new EscrowDisputeRequest(
                                EscrowDisputeReasonCategory.SELLER_NOT_RESPONSIVE,
                                "Seller did not transfer",
                                null),
                        winnerId,
                        List.of()));

        assertThat(notifFor(sellerId, NotificationCategory.ESCROW_DISPUTED)).hasSize(1);
        assertThat(notifFor(winnerId, NotificationCategory.ESCROW_DISPUTED)).hasSize(1);
    }

    @Test
    void frozen_publishesToBothParties() {
        User seller = newUser("esc-frz-seller"); sellerId = seller.getId();
        User winner = newUser("esc-frz-winner"); winnerId = winner.getId();
        seedTransferPendingEscrow();

        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Escrow e = escrowRepo.findById(escrowId).orElseThrow();
            escrowService.freezeForFraud(e, FreezeReason.UNKNOWN_OWNER,
                    Map.of("reason", "test"), OffsetDateTime.now());
        });

        assertThat(notifFor(sellerId, NotificationCategory.ESCROW_FROZEN)).hasSize(1);
        assertThat(notifFor(winnerId, NotificationCategory.ESCROW_FROZEN)).hasSize(1);
    }

    // Wallet-only escrow funding (spec 2026-05-16): EscrowService.expirePayment
    // is deleted. ESCROW_PENDING never persists past createForEndedAuction's
    // commit, so the 48h payment-timeout sweep no longer exists.

    @Test
    void transferConfirmed_publishesToBothParties() {
        User seller = newUser("esc-conf-seller"); sellerId = seller.getId();
        User winner = newUser("esc-conf-winner"); winnerId = winner.getId();
        seedTransferPendingEscrow();

        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Escrow e = escrowRepo.findById(escrowId).orElseThrow();
            escrowService.confirmTransfer(e, OffsetDateTime.now());
        });

        assertThat(notifFor(sellerId, NotificationCategory.ESCROW_TRANSFER_CONFIRMED)).hasSize(1);
        assertThat(notifFor(winnerId, NotificationCategory.ESCROW_TRANSFER_CONFIRMED)).hasSize(1);
    }
}
