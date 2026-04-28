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
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowService;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.escrow.EscrowTransactionRepository;
import com.slparcelauctions.backend.escrow.FreezeReason;
import com.slparcelauctions.backend.escrow.EscrowDisputeReasonCategory;
import com.slparcelauctions.backend.escrow.dto.EscrowDisputeRequest;
import com.slparcelauctions.backend.escrow.payment.dto.EscrowPaymentRequest;
import com.slparcelauctions.backend.escrow.terminal.Terminal;
import com.slparcelauctions.backend.escrow.terminal.TerminalRepository;
import com.slparcelauctions.backend.notification.Notification;
import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationRepository;
import com.slparcelauctions.backend.notification.NotificationWsBroadcasterPort;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
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
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
class EscrowNotificationIntegrationTest {

    private static final String SHARED_SECRET = "dev-escrow-secret-do-not-use-in-prod";

    @Autowired EscrowService escrowService;
    @Autowired EscrowRepository escrowRepo;
    @Autowired EscrowTransactionRepository ledgerRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired BidRepository bidRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired UserRepository userRepo;
    @Autowired NotificationRepository notifRepo;
    @Autowired TerminalRepository terminalRepo;
    @Autowired com.slparcelauctions.backend.auction.fraud.FraudFlagRepository fraudFlagRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private Long sellerId, winnerId, auctionId, parcelId, escrowId;
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
            if (parcelId != null) parcelRepo.findById(parcelId).ifPresent(parcelRepo::delete);
            if (terminalId != null) terminalRepo.findById(terminalId).ifPresent(terminalRepo::delete);
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
        sellerId = winnerId = auctionId = parcelId = escrowId = null;
        terminalId = null;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private User newUser(String prefix) {
        return new TransactionTemplate(txManager).execute(s -> userRepo.save(User.builder()
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
            Parcel p = parcelRepo.save(Parcel.builder()
                    .slParcelUuid(UUID.randomUUID())
                    .ownerType("agent")
                    .regionName("EscrowTestRegion")
                    .continentName("Sansara")
                    .areaSqm(256)
                    .maturityRating("GENERAL")
                    .verified(true)
                    .verifiedAt(OffsetDateTime.now())
                    .build());
            parcelId = p.getId();
            OffsetDateTime now = OffsetDateTime.now();
            Auction a = auctionRepo.save(Auction.builder()
                    .title("Escrow Test Lot")
                    .parcel(p)
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
                    .startsAt(now.minusHours(25))
                    .endsAt(now.minusHours(1))
                    .originalEndsAt(now.minusHours(1))
                    .endedAt(now.minusHours(1))
                    .build());
            auctionId = a.getId();
            Escrow e = escrowRepo.save(Escrow.builder()
                    .auction(a)
                    .state(EscrowState.ESCROW_PENDING)
                    .finalBidAmount(1500L)
                    .commissionAmt(75L)
                    .payoutAmt(1425L)
                    .paymentDeadline(now.plusHours(47))
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
        return notifRepo.findAll().stream()
                .filter(n -> n.getUser().getId().equals(userId))
                .filter(n -> n.getCategory() == category)
                .toList();
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void funded_publishesEscrowFundedToSellerOnly() {
        User seller = newUser("esc-funded-seller"); sellerId = seller.getId();
        User winner = newUser("esc-funded-winner"); winnerId = winner.getId();
        seedPendingEscrow();

        // Register a terminal so acceptPayment's terminal check passes.
        terminalId = "escrow-notif-terminal-" + UUID.randomUUID();
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            terminalRepo.save(Terminal.builder()
                    .terminalId(terminalId)
                    .httpInUrl("https://test.sim/cap/abc")
                    .regionName("TestRegion")
                    .active(true)
                    .lastSeenAt(OffsetDateTime.now())
                    .build());
        });

        User freshWinner = new TransactionTemplate(txManager).execute(s ->
                userRepo.findById(winnerId).orElseThrow());
        EscrowPaymentRequest req = new EscrowPaymentRequest(
                auctionId, freshWinner.getSlAvatarUuid().toString(),
                1500L, "sl-txn-" + UUID.randomUUID(), terminalId, SHARED_SECRET);

        new TransactionTemplate(txManager).executeWithoutResult(s ->
                escrowService.acceptPayment(req));

        assertThat(notifFor(sellerId, NotificationCategory.ESCROW_FUNDED)).hasSize(1);
        assertThat(notifFor(winnerId, NotificationCategory.ESCROW_FUNDED)).isEmpty();
    }

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

    @Test
    void expirePayment_publishesToBothParties() {
        User seller = newUser("esc-exp-seller"); sellerId = seller.getId();
        User winner = newUser("esc-exp-winner"); winnerId = winner.getId();
        seedPendingEscrow();

        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Escrow e = escrowRepo.findById(escrowId).orElseThrow();
            escrowService.expirePayment(e, OffsetDateTime.now());
        });

        assertThat(notifFor(sellerId, NotificationCategory.ESCROW_EXPIRED)).hasSize(1);
        assertThat(notifFor(winnerId, NotificationCategory.ESCROW_EXPIRED)).hasSize(1);
    }

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
