package com.slparcelauctions.backend.escrow.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.BidRepository;
import com.slparcelauctions.backend.auction.ProxyBidRepository;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auth.RefreshTokenRepository;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.escrow.EscrowTransaction;
import com.slparcelauctions.backend.escrow.EscrowTransactionRepository;
import com.slparcelauctions.backend.escrow.EscrowTransactionType;
import com.slparcelauctions.backend.escrow.broadcast.CapturingEscrowBroadcastPublisher;
import com.slparcelauctions.backend.notification.Notification;
import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationRepository;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroup;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroupRepository;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.verification.VerificationCodeRepository;
import com.slparcelauctions.backend.verification.VerificationCodeType;
import com.slparcelauctions.backend.wallet.UserLedgerRepository;

/**
 * Verifies the Sub-project G §8.1 short-circuit:
 * <ul>
 *   <li>{@code queuePayout} returns {@link Optional#empty()} for payoutAmt == 0.</li>
 *   <li>No {@link TerminalCommand} row is persisted for the escrow.</li>
 *   <li>An {@code AUCTION_ESCROW_PAYOUT} ledger row with amount=0 is written.</li>
 *   <li>The escrow transitions to {@link EscrowState#COMPLETED}.</li>
 *   <li>The seller notification fires.</li>
 *   <li>Re-invocation is idempotent: a second call is a no-op (no extra ledger row,
 *       no second seller notification).</li>
 *   <li>For a positive payoutAmt the legacy path still queues a
 *       {@link TerminalCommand}.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.auction-end.enabled=false",
        "slpa.ownership-monitor.enabled=false",
        "slpa.escrow.ownership-monitor-job.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false",
        "slpa.realty.invitation-expiry.enabled=false",
        "slpa.realty.group-bulk-suspend.enabled=false",
        "slpa.realty.sl-group.reverify.enabled=false",
        "slpa.realty.group-suspension-expiry.enabled=false"
})
@Import(TerminalCommandServiceZeroPayoutTest.CapturingConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TerminalCommandServiceZeroPayoutTest {

    @TestConfiguration
    static class CapturingConfig {
        @Bean
        @Primary
        CapturingEscrowBroadcastPublisher capturingEscrowPublisher() {
            return new CapturingEscrowBroadcastPublisher();
        }
    }

    @Autowired TerminalCommandService svc;
    @Autowired EscrowRepository escrowRepo;
    @Autowired EscrowTransactionRepository ledgerRepo;
    @Autowired TerminalCommandRepository cmdRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired BidRepository bidRepo;
    @Autowired ProxyBidRepository proxyBidRepo;
    @Autowired UserRepository userRepo;
    @Autowired RealtyGroupRepository realtyGroupRepo;
    @Autowired RealtyGroupSlGroupRepository realtyGroupSlGroupRepo;
    @Autowired RefreshTokenRepository refreshTokenRepo;
    @Autowired VerificationCodeRepository verificationCodeRepo;
    @Autowired NotificationRepository notificationRepo;
    @Autowired RealtyGroupLedgerRepository realtyGroupLedgerRepo;
    @Autowired UserLedgerRepository userLedgerRepo;
    @Autowired PlatformTransactionManager txManager;

    private Long seededAuctionId;
    private Long seededEscrowId;
    private Long seededSellerId;
    private Long seededBidderId;
    private Long seededRealtyGroupSlGroupId;
    private Long seededRealtyGroupId;

    @AfterEach
    void cleanUp() {
        if (seededAuctionId == null) return;
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            if (seededEscrowId != null) {
                ledgerRepo.findByEscrowIdOrderByCreatedAtAsc(seededEscrowId)
                        .forEach(ledgerRepo::delete);
                cmdRepo.findAll().stream()
                        .filter(c -> seededEscrowId.equals(c.getEscrowId()))
                        .forEach(cmdRepo::delete);
            }
            // AgentCommissionDistributor writes user_ledger + realty_group_ledger
            // rows keyed by refType=AUCTION, refId=auctionId. Drop those before
            // we delete the parent group / users.
            final Long auctionForCleanup = seededAuctionId;
            userLedgerRepo.findAll().stream()
                    .filter(e -> "AUCTION".equals(e.getRefType())
                            && auctionForCleanup.equals(e.getRefId()))
                    .forEach(userLedgerRepo::delete);
            realtyGroupLedgerRepo.findAll().stream()
                    .filter(e -> "AUCTION".equals(e.getRefType())
                            && auctionForCleanup.equals(e.getRefId()))
                    .forEach(realtyGroupLedgerRepo::delete);
            escrowRepo.findByAuctionId(seededAuctionId).ifPresent(escrowRepo::delete);
            bidRepo.deleteAllByAuctionId(seededAuctionId);
            proxyBidRepo.deleteAllByAuctionId(seededAuctionId);
            auctionRepo.findById(seededAuctionId).ifPresent(auctionRepo::delete);
            if (seededRealtyGroupSlGroupId != null) {
                realtyGroupSlGroupRepo.findById(seededRealtyGroupSlGroupId)
                        .ifPresent(realtyGroupSlGroupRepo::delete);
            }
            if (seededRealtyGroupId != null) {
                realtyGroupRepo.findById(seededRealtyGroupId)
                        .ifPresent(realtyGroupRepo::delete);
            }
            for (Long userId : new Long[]{seededBidderId, seededSellerId}) {
                if (userId == null) continue;
                refreshTokenRepo.findAllByUserId(userId).forEach(refreshTokenRepo::delete);
                verificationCodeRepo.findByUserIdAndTypeAndUsedFalse(userId, VerificationCodeType.PLAYER)
                        .forEach(verificationCodeRepo::delete);
                verificationCodeRepo.findByUserIdAndTypeAndUsedFalse(userId, VerificationCodeType.PARCEL)
                        .forEach(verificationCodeRepo::delete);
                notificationRepo.deleteAllByUserId(userId);
                userRepo.findById(userId).ifPresent(userRepo::delete);
            }
        });
        seededAuctionId = null;
        seededEscrowId = null;
        seededSellerId = null;
        seededBidderId = null;
        seededRealtyGroupSlGroupId = null;
        seededRealtyGroupId = null;
    }

    @Test
    void queuePayout_returns_empty_and_runs_success_inline_when_payout_amt_is_zero() {
        Escrow escrow = fixtureCase3Escrow();
        TransactionTemplate tx = new TransactionTemplate(txManager);

        Optional<TerminalCommand> result = tx.execute(status -> {
            Escrow managed = escrowRepo.findById(escrow.getId()).orElseThrow();
            return svc.queuePayout(managed);
        });

        assertThat(result).isEmpty();

        // No TerminalCommand row for this escrow.
        long cmdRows = cmdRepo.findAll().stream()
                .filter(c -> escrow.getId().equals(c.getEscrowId()))
                .count();
        assertThat(cmdRows).isZero();

        // Escrow has been flipped to COMPLETED.
        Escrow reloaded = escrowRepo.findById(escrow.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(EscrowState.COMPLETED);
        assertThat(reloaded.getCompletedAt()).isNotNull();

        // AUCTION_ESCROW_PAYOUT ledger row written with amount=0.
        List<EscrowTransaction> ledger =
                ledgerRepo.findByEscrowIdOrderByCreatedAtAsc(escrow.getId());
        long payoutRows = ledger.stream()
                .filter(r -> r.getType() == EscrowTransactionType.AUCTION_ESCROW_PAYOUT)
                .count();
        assertThat(payoutRows).isEqualTo(1L);
        EscrowTransaction payoutRow = ledger.stream()
                .filter(r -> r.getType() == EscrowTransactionType.AUCTION_ESCROW_PAYOUT)
                .findFirst()
                .orElseThrow();
        assertThat(payoutRow.getAmount()).isZero();

        // Seller payout notification fired.
        List<Notification> sellerNotifs = notificationRepo.findAllByUserId(seededSellerId);
        long payoutNotifs = sellerNotifs.stream()
                .filter(n -> n.getCategory() == NotificationCategory.ESCROW_PAYOUT)
                .count();
        assertThat(payoutNotifs).isEqualTo(1L);
    }

    @Test
    void queuePayout_is_idempotent_when_escrow_already_completed() {
        Escrow escrow = fixtureCase3Escrow();
        TransactionTemplate tx = new TransactionTemplate(txManager);

        // First call -- completes inline.
        tx.executeWithoutResult(status -> {
            Escrow managed = escrowRepo.findById(escrow.getId()).orElseThrow();
            svc.queuePayout(managed);
        });

        long ledgerCountAfterFirst =
                ledgerRepo.findByEscrowIdOrderByCreatedAtAsc(escrow.getId()).size();
        long notifCountAfterFirst = notificationRepo.findAllByUserId(seededSellerId).stream()
                .filter(n -> n.getCategory() == NotificationCategory.ESCROW_PAYOUT)
                .count();

        // Second call -- escrow already COMPLETED, should be a no-op.
        Optional<TerminalCommand> second = tx.execute(status -> {
            Escrow managed = escrowRepo.findById(escrow.getId()).orElseThrow();
            return svc.queuePayout(managed);
        });

        assertThat(second).isEmpty();
        assertThat(ledgerRepo.findByEscrowIdOrderByCreatedAtAsc(escrow.getId()).size())
                .isEqualTo(ledgerCountAfterFirst);
        long payoutNotifsAfterSecond = notificationRepo.findAllByUserId(seededSellerId).stream()
                .filter(n -> n.getCategory() == NotificationCategory.ESCROW_PAYOUT)
                .count();
        assertThat(payoutNotifsAfterSecond).isEqualTo(notifCountAfterFirst);
    }

    @Test
    void queuePayout_enqueues_a_command_when_payout_amt_is_positive() {
        Escrow escrow = fixtureIndividualEscrow(1000L);
        TransactionTemplate tx = new TransactionTemplate(txManager);

        Optional<TerminalCommand> result = tx.execute(status -> {
            Escrow managed = escrowRepo.findById(escrow.getId()).orElseThrow();
            return svc.queuePayout(managed);
        });

        assertThat(result).isPresent();
        assertThat(result.get().getAmount()).isEqualTo(1000L);
        assertThat(result.get().getAction()).isEqualTo(TerminalCommandAction.PAYOUT);
        assertThat(result.get().getPurpose()).isEqualTo(TerminalCommandPurpose.AUCTION_ESCROW);
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    /**
     * Case-3 escrow: SL-group-owned auction, {@code payoutAmt = 0}, escrow in
     * {@link EscrowState#TRANSFER_PENDING} so the {@code TRANSFER_PENDING ->
     * COMPLETED} transition is legal.
     */
    private Escrow fixtureCase3Escrow() {
        long finalBid = 10_000L;
        TransactionTemplate tx = new TransactionTemplate(txManager);
        return tx.execute(status -> {
            UUID parcelUuid = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now();

            User seller = userRepo.save(User.builder()
                    .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("zero-seller-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("Zero Seller")
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .build());

            User bidder = userRepo.save(User.builder()
                    .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("zero-bidder-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("Zero Bidder")
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .build());

            String suffix = UUID.randomUUID().toString().substring(0, 8);
            RealtyGroup group = realtyGroupRepo.save(RealtyGroup.builder()
                    .name("Zero Group " + suffix)
                    .slug("zero-group-" + suffix)
                    .leaderId(seller.getId())
                    .build());

            RealtyGroupSlGroup slGroup = realtyGroupSlGroupRepo.save(RealtyGroupSlGroup.builder()
                    .realtyGroupId(group.getId())
                    .slGroupUuid(UUID.randomUUID())
                    .slGroupName("Zero SL Group " + suffix)
                    .verified(true)
                    .verifiedAt(now)
                    .build());

            Auction auction = auctionRepo.save(Auction.builder()
                    .title("Zero-Payout Test Listing")
                    .slParcelUuid(parcelUuid)
                    .seller(seller)
                    .listingAgent(seller)
                    .realtyGroupId(group.getId())
                    .realtyGroupSlGroupId(slGroup.getId())
                    .agentCommissionRate(new BigDecimal("0.1000"))
                    .status(AuctionStatus.TRANSFER_PENDING)

                    .verificationTier(VerificationTier.SCRIPT)
                    .startingBid(500L)
                    .reservePrice(1_000L)
                    .currentBid(finalBid)
                    .bidCount(2)
                    .durationHours(168)
                    .snipeProtect(false)
                    .listingFeePaid(true)
                    .consecutiveWorldApiFailures(0)
                    .commissionRate(new BigDecimal("0.05"))
                    .startsAt(now.minusHours(3))
                    .endsAt(now.minusHours(1))
                    .originalEndsAt(now.minusHours(1))
                    .endedAt(now.minusHours(1))
                    .endOutcome(AuctionEndOutcome.SOLD)
                    .winnerUserId(bidder.getId())
                    .finalBidAmount(finalBid)
                    .build());
            auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                    .slParcelUuid(parcelUuid)
                    .ownerUuid(slGroup.getSlGroupUuid())
                    .ownerType("group")
                    .parcelName("Zero Test Parcel")
                    .regionName("Coniston")
                    .regionMaturityRating("GENERAL")
                    .areaSqm(1024)
                    .positionX(128.0).positionY(64.0).positionZ(22.0)
                    .build());
            auctionRepo.save(auction);

            // payoutAmt = 0 (case-3), commission = 5% of finalBid.
            long commission = (long) (finalBid * 0.05);
            Escrow escrow = escrowRepo.save(Escrow.builder()
                    .auction(auction)
                    .state(EscrowState.TRANSFER_PENDING)
                    .finalBidAmount(finalBid)
                    .commissionAmt(commission)
                    .payoutAmt(0L)
                    .transferDeadline(now.plusHours(72))
                    .fundedAt(now.minusMinutes(30))
                    .transferConfirmedAt(now.minusMinutes(5))
                    .build());

            seededSellerId = seller.getId();
            seededBidderId = bidder.getId();
            seededAuctionId = auction.getId();
            seededEscrowId = escrow.getId();
            seededRealtyGroupSlGroupId = slGroup.getId();
            seededRealtyGroupId = group.getId();
            return escrow;
        });
    }

    /**
     * Individual (non-group) escrow with a non-zero {@code payoutAmt}. Used to
     * confirm the positive-path still enqueues a {@link TerminalCommand}.
     */
    private Escrow fixtureIndividualEscrow(long payoutAmt) {
        long finalBid = payoutAmt + 100;
        TransactionTemplate tx = new TransactionTemplate(txManager);
        return tx.execute(status -> {
            UUID parcelUuid = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now();

            User seller = userRepo.save(User.builder()
                    .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("indiv-seller-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("Individual Seller")
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .build());

            User bidder = userRepo.save(User.builder()
                    .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("indiv-bidder-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("Individual Bidder")
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .build());

            Auction auction = auctionRepo.save(Auction.builder()
                    .title("Individual Listing")
                    .slParcelUuid(parcelUuid)
                    .seller(seller)
                    .status(AuctionStatus.TRANSFER_PENDING)

                    .verificationTier(VerificationTier.SCRIPT)
                    .startingBid(500L)
                    .reservePrice(1_000L)
                    .currentBid(finalBid)
                    .bidCount(2)
                    .durationHours(168)
                    .snipeProtect(false)
                    .listingFeePaid(true)
                    .consecutiveWorldApiFailures(0)
                    .commissionRate(new BigDecimal("0.05"))
                    .startsAt(now.minusHours(3))
                    .endsAt(now.minusHours(1))
                    .originalEndsAt(now.minusHours(1))
                    .endedAt(now.minusHours(1))
                    .endOutcome(AuctionEndOutcome.SOLD)
                    .winnerUserId(bidder.getId())
                    .finalBidAmount(finalBid)
                    .build());
            auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                    .slParcelUuid(parcelUuid)
                    .ownerUuid(seller.getSlAvatarUuid())
                    .ownerType("avatar")
                    .parcelName("Individual Parcel")
                    .regionName("Coniston")
                    .regionMaturityRating("GENERAL")
                    .areaSqm(1024)
                    .positionX(128.0).positionY(64.0).positionZ(22.0)
                    .build());
            auctionRepo.save(auction);

            long commission = (long) (finalBid * 0.05);
            Escrow escrow = escrowRepo.save(Escrow.builder()
                    .auction(auction)
                    .state(EscrowState.TRANSFER_PENDING)
                    .finalBidAmount(finalBid)
                    .commissionAmt(commission)
                    .payoutAmt(payoutAmt)
                    .transferDeadline(now.plusHours(72))
                    .fundedAt(now.minusMinutes(30))
                    .transferConfirmedAt(now.minusMinutes(5))
                    .build());

            seededSellerId = seller.getId();
            seededBidderId = bidder.getId();
            seededAuctionId = auction.getId();
            seededEscrowId = escrow.getId();
            return escrow;
        });
    }
}
