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
import com.slparcelauctions.backend.escrow.EscrowTransactionRepository;
import com.slparcelauctions.backend.escrow.command.TerminalCommand;
import com.slparcelauctions.backend.escrow.command.TerminalCommandAction;
import com.slparcelauctions.backend.escrow.command.TerminalCommandPurpose;
import com.slparcelauctions.backend.escrow.command.TerminalCommandRepository;
import com.slparcelauctions.backend.escrow.command.TerminalCommandService;
import com.slparcelauctions.backend.escrow.command.TerminalCommandStatus;
import com.slparcelauctions.backend.escrow.command.dto.PayoutResultRequest;
import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationRepository;
import com.slparcelauctions.backend.notification.NotificationWsBroadcasterPort;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Vertical-slice integration test for ESCROW_PAYOUT_STALLED notification.
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
class TerminalCommandStallNotificationIntegrationTest {

    @Autowired TerminalCommandService terminalCommandService;
    @Autowired TerminalCommandRepository cmdRepo;
    @Autowired EscrowRepository escrowRepo;
    @Autowired EscrowTransactionRepository ledgerRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired UserRepository userRepo;
    @Autowired NotificationRepository notifRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private Long sellerId, winnerId, auctionId, escrowId, cmdId;

    @AfterEach
    void cleanup() throws Exception {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            if (cmdId != null) {
                ledgerRepo.findByEscrowIdOrderByCreatedAtAsc(escrowId).forEach(ledgerRepo::delete);
                cmdRepo.findById(cmdId).ifPresent(cmdRepo::delete);
            }
            if (escrowId != null) escrowRepo.findById(escrowId).ifPresent(escrowRepo::delete);
            if (auctionId != null) {
                auctionRepo.findById(auctionId).ifPresent(auctionRepo::delete);
            }
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
        sellerId = winnerId = auctionId = escrowId = cmdId = null;
    }

    private User newUser(String prefix) {
        return new TransactionTemplate(txManager).execute(s -> userRepo.save(User.builder()
                .email(prefix + "-" + UUID.randomUUID() + "@test.com")
                .passwordHash("h")
                .displayName(prefix)
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .build()));
    }

    private String seedPayoutCommandAtMaxAttempts() {
        String idempotencyKey = "stall-test-" + UUID.randomUUID();
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            User seller = userRepo.findById(sellerId).orElseThrow();
            User winner = userRepo.findById(winnerId).orElseThrow();
            UUID parcelUuid = UUID.randomUUID();
            Auction a = auctionRepo.save(Auction.builder()
                    .title("Stall Test Lot")
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
                    .startsAt(OffsetDateTime.now().minusDays(2))
                    .endsAt(OffsetDateTime.now().minusDays(1))
                    .originalEndsAt(OffsetDateTime.now().minusDays(1))
                    .endedAt(OffsetDateTime.now().minusDays(1))
                    .build());
            auctionId = a.getId();
            a.setParcelSnapshot(AuctionParcelSnapshot.builder()
                    .slParcelUuid(parcelUuid).ownerType("agent")
                    .ownerName("Seller").parcelName("Stall Test Parcel")
                    .regionName("Mainland").areaSqm(256)
                    .positionX(128.0).positionY(128.0).positionZ(22.0)
                    .build());
            auctionRepo.save(a);
            Escrow e = escrowRepo.save(Escrow.builder()
                    .auction(a)
                    .state(EscrowState.TRANSFER_PENDING)
                    .finalBidAmount(1500L)
                    .commissionAmt(75L)
                    .payoutAmt(1425L)
                    .paymentDeadline(OffsetDateTime.now().minusDays(1))
                    .consecutiveWorldApiFailures(0)
                    .fundedAt(OffsetDateTime.now().minusDays(2))
                    .transferConfirmedAt(OffsetDateTime.now().minusHours(2))
                    .build());
            escrowId = e.getId();
            // Create a PAYOUT command already at MAX_ATTEMPTS (4)
            TerminalCommand cmd = cmdRepo.save(TerminalCommand.builder()
                    .escrowId(e.getId())
                    .action(TerminalCommandAction.PAYOUT)
                    .purpose(TerminalCommandPurpose.AUCTION_ESCROW)
                    .recipientUuid(seller.getSlAvatarUuid().toString())
                    .amount(1425L)
                    .status(TerminalCommandStatus.FAILED)
                    .idempotencyKey(idempotencyKey)
                    .nextAttemptAt(OffsetDateTime.now().minusMinutes(1))
                    .attemptCount(4)  // at MAX_ATTEMPTS
                    .requiresManualReview(false)
                    .build());
            cmdId = cmd.getId();
        });
        return idempotencyKey;
    }

    @Test
    void payoutCommandStall_publishesEscrowPayoutStalled() {
        User seller = newUser("stall-seller"); sellerId = seller.getId();
        User winner = newUser("stall-winner"); winnerId = winner.getId();
        String ikey = seedPayoutCommandAtMaxAttempts();

        // Trigger a failure callback that tips the command into stall
        PayoutResultRequest req = new PayoutResultRequest(ikey, false, null, "terminal error", "test-terminal", "dev-escrow-secret-do-not-use-in-prod");
        terminalCommandService.applyCallback(req);

        var notifs = notifRepo.findAll().stream()
                .filter(n -> n.getUser().getId().equals(sellerId))
                .filter(n -> n.getCategory() == NotificationCategory.ESCROW_PAYOUT_STALLED)
                .toList();
        assertThat(notifs).hasSize(1);

        // Winner should NOT get ESCROW_PAYOUT_STALLED
        assertThat(notifRepo.findAll().stream()
                .filter(n -> n.getUser().getId().equals(winnerId))
                .filter(n -> n.getCategory() == NotificationCategory.ESCROW_PAYOUT_STALLED)
                .toList()).isEmpty();
    }

    @Test
    void nonPayoutCommandStall_doesNotPublishEscrowPayoutStalled() {
        User seller = newUser("nostall-seller"); sellerId = seller.getId();
        User winner = newUser("nostall-winner"); winnerId = winner.getId();

        // Create a REFUND command at MAX_ATTEMPTS
        String ikey = "nostall-test-" + UUID.randomUUID();
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            User w = userRepo.findById(winnerId).orElseThrow();
            User sl = userRepo.findById(sellerId).orElseThrow();
            UUID parcelUuid2 = UUID.randomUUID();
            Auction a = auctionRepo.save(Auction.builder()
                    .title("NoStall Test Lot").slParcelUuid(parcelUuid2).seller(sl)
                    .status(AuctionStatus.DISPUTED)
                    .endOutcome(AuctionEndOutcome.SOLD)
                    .verificationMethod(VerificationMethod.UUID_ENTRY)
                    .verificationTier(VerificationTier.SCRIPT)
                    .startingBid(1000L).currentBid(1500L)
                    .currentBidderId(w.getId()).winnerUserId(w.getId())
                    .finalBidAmount(1500L).bidCount(1).durationHours(168)
                    .snipeProtect(false).listingFeePaid(true)
                    .consecutiveWorldApiFailures(0)
                    .commissionRate(new BigDecimal("0.05")).agentFeeRate(BigDecimal.ZERO)
                    .startsAt(OffsetDateTime.now().minusDays(2))
                    .endsAt(OffsetDateTime.now().minusDays(1))
                    .originalEndsAt(OffsetDateTime.now().minusDays(1))
                    .endedAt(OffsetDateTime.now().minusDays(1)).build());
            auctionId = a.getId();
            a.setParcelSnapshot(AuctionParcelSnapshot.builder()
                    .slParcelUuid(parcelUuid2).ownerType("agent")
                    .ownerName("Seller").parcelName("NoStall Test Parcel")
                    .regionName("Mainland").areaSqm(256)
                    .positionX(128.0).positionY(128.0).positionZ(22.0)
                    .build());
            auctionRepo.save(a);
            Escrow e = escrowRepo.save(Escrow.builder()
                    .auction(a).state(EscrowState.DISPUTED)
                    .finalBidAmount(1500L).commissionAmt(75L).payoutAmt(1425L)
                    .paymentDeadline(OffsetDateTime.now().minusDays(1))
                    .consecutiveWorldApiFailures(0)
                    .fundedAt(OffsetDateTime.now().minusDays(2))
                    .disputedAt(OffsetDateTime.now().minusHours(12))
                    .build());
            escrowId = e.getId();
            TerminalCommand cmd = cmdRepo.save(TerminalCommand.builder()
                    .escrowId(e.getId())
                    .action(TerminalCommandAction.REFUND)  // REFUND, not PAYOUT
                    .purpose(TerminalCommandPurpose.AUCTION_ESCROW)
                    .recipientUuid(w.getSlAvatarUuid().toString())
                    .amount(1500L).status(TerminalCommandStatus.FAILED)
                    .idempotencyKey(ikey)
                    .nextAttemptAt(OffsetDateTime.now().minusMinutes(1))
                    .attemptCount(4).requiresManualReview(false).build());
            cmdId = cmd.getId();
        });

        PayoutResultRequest req = new PayoutResultRequest(ikey, false, null, "terminal error", "test-terminal", "dev-escrow-secret-do-not-use-in-prod");
        terminalCommandService.applyCallback(req);

        // Refund stall should NOT publish ESCROW_PAYOUT_STALLED
        assertThat(notifRepo.findAll().stream()
                .filter(n -> n.getUser().getId().equals(sellerId))
                .filter(n -> n.getCategory() == NotificationCategory.ESCROW_PAYOUT_STALLED)
                .toList()).isEmpty();
    }
}
