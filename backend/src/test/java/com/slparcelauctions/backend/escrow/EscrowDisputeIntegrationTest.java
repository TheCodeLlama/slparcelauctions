package com.slparcelauctions.backend.escrow;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.BidRepository;
import com.slparcelauctions.backend.auction.ProxyBidRepository;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auth.RefreshTokenRepository;
import com.slparcelauctions.backend.escrow.broadcast.CapturingEscrowBroadcastPublisher;
import com.slparcelauctions.backend.escrow.broadcast.EscrowBroadcastPublisher;
import com.slparcelauctions.backend.escrow.broadcast.EscrowDisputedEnvelope;
import com.slparcelauctions.backend.escrow.dto.EscrowDisputeRequest;
import com.slparcelauctions.backend.escrow.dto.EscrowStatusResponse;
import com.slparcelauctions.backend.escrow.exception.IllegalEscrowTransitionException;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.notification.NotificationRepository;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.verification.VerificationCodeRepository;
import com.slparcelauctions.backend.verification.VerificationCodeType;

/**
 * End-to-end coverage for {@code EscrowService.fileDispute}. Seeds escrow
 * rows directly via the repository so each test starts at a precise state
 * (ESCROW_PENDING, TRANSFER_PENDING, or the terminal COMPLETED) without
 * having to drive the full upstream auction lifecycle each time.
 *
 * <p>The {@link EscrowBroadcastPublisher} is swapped for
 * {@link CapturingEscrowBroadcastPublisher} so envelope fanout can be
 * asserted without a live STOMP broker. Teardown follows the FK-respecting
 * order from the create-on-auction-end integration tests: escrow row first,
 * then bids / auction, then parcel + users.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.auction-end.enabled=false",
        "slpa.ownership-monitor.enabled=false",
        "slpa.escrow.ownership-monitor-job.enabled=false",
        "slpa.notifications.cleanup.enabled=false"
})
@Import(EscrowDisputeIntegrationTest.CapturingConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EscrowDisputeIntegrationTest {

    @TestConfiguration
    static class CapturingConfig {
        @Bean
        @Primary
        CapturingEscrowBroadcastPublisher capturingEscrowPublisher() {
            return new CapturingEscrowBroadcastPublisher();
        }
    }

    @Autowired EscrowService escrowService;
    @Autowired AuctionRepository auctionRepo;
    @Autowired BidRepository bidRepo;
    @Autowired ProxyBidRepository proxyBidRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired UserRepository userRepo;
    @Autowired RefreshTokenRepository refreshTokenRepo;
    @Autowired VerificationCodeRepository verificationCodeRepo;
    @Autowired EscrowRepository escrowRepo;
    @Autowired EscrowCommissionCalculator commissionCalculator;
    @Autowired NotificationRepository notificationRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired CapturingEscrowBroadcastPublisher capturingEscrowPublisher;

    private Long seededAuctionId;
    private Long seededEscrowId;
    private Long seededParcelId;
    private Long seededSellerId;
    private Long seededBidderId;

    @BeforeEach
    void resetCapture() {
        capturingEscrowPublisher.reset();
    }

    @AfterEach
    void cleanUp() {
        if (seededAuctionId == null) return;
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            escrowRepo.findByAuctionId(seededAuctionId).ifPresent(escrowRepo::delete);
            bidRepo.deleteAllByAuctionId(seededAuctionId);
            proxyBidRepo.deleteAllByAuctionId(seededAuctionId);
            auctionRepo.findById(seededAuctionId).ifPresent(auctionRepo::delete);
            if (seededParcelId != null) {
                parcelRepo.findById(seededParcelId).ifPresent(parcelRepo::delete);
            }
            for (Long userId : new Long[]{seededBidderId, seededSellerId}) {
                if (userId == null) continue;
                refreshTokenRepo.findAllByUserId(userId)
                        .forEach(refreshTokenRepo::delete);
                verificationCodeRepo.findByUserIdAndTypeAndUsedFalse(userId,
                        VerificationCodeType.PLAYER)
                        .forEach(verificationCodeRepo::delete);
                verificationCodeRepo.findByUserIdAndTypeAndUsedFalse(userId,
                        VerificationCodeType.PARCEL)
                        .forEach(verificationCodeRepo::delete);
                notificationRepo.deleteAllByUserId(userId);
                userRepo.findById(userId).ifPresent(userRepo::delete);
            }
        });
        seededAuctionId = null;
        seededEscrowId = null;
        seededParcelId = null;
        seededSellerId = null;
        seededBidderId = null;
    }

    @Test
    void disputeFromEscrowPending_flipsStateAndBroadcasts() {
        seedEndedAuctionWithEscrow(EscrowState.ESCROW_PENDING, /* funded */ false);

        EscrowStatusResponse resp = escrowService.fileDispute(
                seededAuctionId,
                new EscrowDisputeRequest(
                        EscrowDisputeReasonCategory.SELLER_NOT_RESPONSIVE,
                        "Seller has gone silent for 72 hours, no parcel transfer."),
                seededBidderId);

        assertThat(resp.state()).isEqualTo(EscrowState.DISPUTED);
        assertThat(resp.disputeReasonCategory()).isEqualTo("SELLER_NOT_RESPONSIVE");

        Escrow persisted = escrowRepo.findById(seededEscrowId).orElseThrow();
        assertThat(persisted.getState()).isEqualTo(EscrowState.DISPUTED);
        assertThat(persisted.getDisputedAt()).isNotNull();
        assertThat(persisted.getDisputeReasonCategory()).isEqualTo("SELLER_NOT_RESPONSIVE");
        assertThat(persisted.getDisputeDescription())
                .isEqualTo("Seller has gone silent for 72 hours, no parcel transfer.");
        // No refund should be queued because the escrow was never funded.
        // Task 7 wires this to TerminalCommandService; for Task 3 we only
        // confirm that the funded flag stayed untouched and that the stub
        // did not mutate state.
        assertThat(persisted.getFundedAt()).isNull();

        assertThat(capturingEscrowPublisher.disputed).hasSize(1);
        EscrowDisputedEnvelope env = capturingEscrowPublisher.disputed.get(0);
        assertThat(env.type()).isEqualTo("ESCROW_DISPUTED");
        assertThat(env.auctionId()).isEqualTo(seededAuctionId);
        assertThat(env.escrowId()).isEqualTo(seededEscrowId);
        assertThat(env.state()).isEqualTo(EscrowState.DISPUTED);
        assertThat(env.reason()).isEqualTo("SELLER_NOT_RESPONSIVE");
        assertThat(env.serverTime()).isNotNull();
    }

    @Test
    void disputeFromTransferPending_flipsStateAndBroadcasts() {
        seedEndedAuctionWithEscrow(EscrowState.TRANSFER_PENDING, /* funded */ true);

        EscrowStatusResponse resp = escrowService.fileDispute(
                seededAuctionId,
                new EscrowDisputeRequest(
                        EscrowDisputeReasonCategory.WRONG_PARCEL_TRANSFERRED,
                        "Seller transferred a different parcel than listed."),
                seededSellerId);

        assertThat(resp.state()).isEqualTo(EscrowState.DISPUTED);

        Escrow persisted = escrowRepo.findById(seededEscrowId).orElseThrow();
        assertThat(persisted.getState()).isEqualTo(EscrowState.DISPUTED);
        assertThat(persisted.getFundedAt()).isNotNull();
        assertThat(persisted.getDisputedAt()).isNotNull();
        assertThat(persisted.getDisputeReasonCategory())
                .isEqualTo("WRONG_PARCEL_TRANSFERRED");

        // Verifying queueRefundIfFunded was actually invoked is deferred to
        // Task 7, where TerminalCommandService is introduced. Here we only
        // confirm the dispute broadcast fires and the state persisted.
        assertThat(capturingEscrowPublisher.disputed).hasSize(1);
        assertThat(capturingEscrowPublisher.disputed.get(0).reason())
                .isEqualTo("WRONG_PARCEL_TRANSFERRED");
    }

    @Test
    void disputeFromCompletedTerminal_throwsAndDoesNotBroadcast() {
        seedEndedAuctionWithEscrow(EscrowState.COMPLETED, /* funded */ true);

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalEscrowTransitionException.class,
                () -> escrowService.fileDispute(
                        seededAuctionId,
                        new EscrowDisputeRequest(
                                EscrowDisputeReasonCategory.OTHER,
                                "Attempting to dispute after completion."),
                        seededSellerId));

        Escrow persisted = escrowRepo.findById(seededEscrowId).orElseThrow();
        assertThat(persisted.getState()).isEqualTo(EscrowState.COMPLETED);
        assertThat(persisted.getDisputedAt()).isNull();
        assertThat(capturingEscrowPublisher.disputed).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Seeding — directly persists an Auction + Escrow row in the requested
    // starting state so dispute transitions can be exercised without driving
    // the full auction lifecycle each test.
    // -------------------------------------------------------------------------

    private void seedEndedAuctionWithEscrow(EscrowState startingState, boolean funded) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            User seller = userRepo.save(User.builder()
                    .email("escrow-dispute-seller-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("Escrow Dispute Seller")
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .build());
            User bidder = userRepo.save(User.builder()
                    .email("escrow-dispute-bidder-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("Escrow Dispute Bidder")
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .build());
            Parcel parcel = parcelRepo.save(Parcel.builder()
                    .slParcelUuid(UUID.randomUUID())
                    .ownerUuid(seller.getSlAvatarUuid())
                    .ownerType("agent")
                    .regionName("EscrowDisputeRegion")
                    .continentName("Sansara")
                    .areaSqm(1024)
                    .maturityRating("MODERATE")
                    .verified(true)
                    .verifiedAt(OffsetDateTime.now())
                    .build());
            OffsetDateTime now = OffsetDateTime.now();
            long finalBid = 5_000L;
            Auction auction = auctionRepo.save(Auction.builder()
                    .title("Test listing")
                    .parcel(parcel)
                    .seller(seller)
                    .status(AuctionStatus.ENDED)
                    .verificationMethod(VerificationMethod.UUID_ENTRY)
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
                    .agentFeeRate(BigDecimal.ZERO)
                    .startsAt(now.minusHours(2))
                    .endsAt(now.minusSeconds(1))
                    .originalEndsAt(now.minusSeconds(1))
                    .endedAt(now.minusSeconds(1))
                    .endOutcome(AuctionEndOutcome.SOLD)
                    .winnerUserId(bidder.getId())
                    .finalBidAmount(finalBid)
                    .build());

            Escrow.EscrowBuilder escrowBuilder = Escrow.builder()
                    .auction(auction)
                    .state(startingState)
                    .finalBidAmount(finalBid)
                    .commissionAmt(commissionCalculator.commission(finalBid))
                    .payoutAmt(commissionCalculator.payout(finalBid))
                    .paymentDeadline(now.plusHours(48))
                    .consecutiveWorldApiFailures(0);

            if (funded || startingState == EscrowState.TRANSFER_PENDING
                    || startingState == EscrowState.COMPLETED) {
                escrowBuilder.fundedAt(now.minusMinutes(10));
                escrowBuilder.transferDeadline(now.plusHours(72));
            }
            if (startingState == EscrowState.COMPLETED) {
                escrowBuilder.transferConfirmedAt(now.minusMinutes(5));
                escrowBuilder.completedAt(now.minusMinutes(1));
            }
            Escrow escrow = escrowRepo.save(escrowBuilder.build());

            seededSellerId = seller.getId();
            seededBidderId = bidder.getId();
            seededParcelId = parcel.getId();
            seededAuctionId = auction.getId();
            seededEscrowId = escrow.getId();
        });
    }
}
