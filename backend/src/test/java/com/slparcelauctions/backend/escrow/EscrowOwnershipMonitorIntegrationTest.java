package com.slparcelauctions.backend.escrow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import com.slparcelauctions.backend.auction.fraud.FraudFlag;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.auth.RefreshTokenRepository;
import com.slparcelauctions.backend.escrow.broadcast.CapturingEscrowBroadcastPublisher;
import com.slparcelauctions.backend.escrow.broadcast.EscrowBroadcastPublisher;
import com.slparcelauctions.backend.escrow.broadcast.EscrowFrozenEnvelope;
import com.slparcelauctions.backend.escrow.broadcast.EscrowTransferConfirmedEnvelope;
import com.slparcelauctions.backend.escrow.scheduler.EscrowOwnershipMonitorJob;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.exception.ParcelNotFoundInSlException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.verification.VerificationCodeRepository;
import com.slparcelauctions.backend.verification.VerificationCodeType;

import reactor.core.publisher.Mono;

/**
 * End-to-end coverage of {@link EscrowOwnershipMonitorJob}. Unlike the
 * auction-side integration test (which uses WireMock to exercise the real
 * {@code SlWorldApiClient}), this test uses {@link MockitoBean} to stub
 * {@code SlWorldApiClient.fetchParcel} — the underlying client is already
 * covered at that level in {@code OwnershipMonitorIntegrationTest} and the
 * escrow monitor's value-add is the per-outcome delegation to
 * {@link EscrowService#confirmTransfer} /
 * {@link EscrowService#freezeForFraud}, which is what this test validates.
 *
 * <p>Three scenarios:
 * <ol>
 *   <li>Winner UUID returned — escrow stamps {@code transferConfirmedAt},
 *       state stays {@code TRANSFER_PENDING}, {@code ESCROW_TRANSFER_CONFIRMED}
 *       envelope captured.</li>
 *   <li>Stranger UUID returned — escrow moves to {@code FROZEN},
 *       {@code FraudFlag} with {@code ESCROW_UNKNOWN_OWNER} created,
 *       {@code ESCROW_FROZEN} envelope captured.</li>
 *   <li>{@link ParcelNotFoundInSlException} — escrow moves to
 *       {@code FROZEN}, {@code FraudFlag} with {@code ESCROW_PARCEL_DELETED}
 *       created.</li>
 * </ol>
 *
 * <p>The {@code @Scheduled} cron is disabled via
 * {@code slpa.escrow.ownership-monitor-job.enabled=false} and we invoke
 * {@link EscrowOwnershipMonitorJob#sweep()} explicitly so the test owns the
 * timing. The default scheduler would otherwise race with our explicit
 * sweep on context startup.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.auction-end.enabled=false",
        "slpa.ownership-monitor.enabled=false",
        // Keep the escrow monitor bean eligible for autowiring but disable the
        // @Scheduled tick so the sweep only runs when we call it explicitly.
        "slpa.escrow.ownership-monitor-job.enabled=true",
        "slpa.escrow.ownership-monitor-job.fixed-delay=PT24H",
        // Low threshold is irrelevant for these scenarios — none exercise
        // the World API failure counter branch.
        "slpa.escrow.ownership-api-failure-threshold=5"
})
@Import(EscrowOwnershipMonitorIntegrationTest.CapturingConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EscrowOwnershipMonitorIntegrationTest {

    @TestConfiguration
    static class CapturingConfig {
        @Bean
        @Primary
        CapturingEscrowBroadcastPublisher capturingEscrowPublisher() {
            return new CapturingEscrowBroadcastPublisher();
        }
    }

    @MockitoBean SlWorldApiClient worldApi;

    @Autowired EscrowOwnershipMonitorJob job;
    @Autowired EscrowRepository escrowRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired BidRepository bidRepo;
    @Autowired ProxyBidRepository proxyBidRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired UserRepository userRepo;
    @Autowired EscrowTransactionRepository escrowTxRepo;
    @Autowired EscrowCommissionCalculator commissionCalculator;
    @Autowired FraudFlagRepository fraudFlagRepo;
    @Autowired RefreshTokenRepository refreshTokenRepo;
    @Autowired VerificationCodeRepository verificationCodeRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired CapturingEscrowBroadcastPublisher capturingEscrowPublisher;

    private Long seededAuctionId;
    private Long seededEscrowId;
    private Long seededParcelId;
    private Long seededSellerId;
    private Long seededBidderId;
    private UUID seededParcelUuid;
    private UUID seededSellerAvatar;
    private UUID seededWinnerAvatar;

    @BeforeEach
    void resetCapture() {
        capturingEscrowPublisher.reset();
    }

    @AfterEach
    void cleanUp() {
        if (seededAuctionId == null) return;
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            fraudFlagRepo.findByAuctionId(seededAuctionId).forEach(fraudFlagRepo::delete);
            escrowTxRepo.findByEscrowIdOrderByCreatedAtAsc(seededEscrowId)
                    .forEach(escrowTxRepo::delete);
            escrowRepo.findByAuctionId(seededAuctionId).ifPresent(escrowRepo::delete);
            bidRepo.deleteAllByAuctionId(seededAuctionId);
            proxyBidRepo.deleteAllByAuctionId(seededAuctionId);
            auctionRepo.findById(seededAuctionId).ifPresent(auctionRepo::delete);
            if (seededParcelId != null) {
                parcelRepo.findById(seededParcelId).ifPresent(parcelRepo::delete);
            }
            for (Long userId : new Long[]{seededBidderId, seededSellerId}) {
                if (userId == null) continue;
                refreshTokenRepo.findAllByUserId(userId).forEach(refreshTokenRepo::delete);
                verificationCodeRepo.findByUserIdAndTypeAndUsedFalse(userId,
                        VerificationCodeType.PLAYER).forEach(verificationCodeRepo::delete);
                verificationCodeRepo.findByUserIdAndTypeAndUsedFalse(userId,
                        VerificationCodeType.PARCEL).forEach(verificationCodeRepo::delete);
                userRepo.findById(userId).ifPresent(userRepo::delete);
            }
        });
        seededAuctionId = null;
        seededEscrowId = null;
        seededParcelId = null;
        seededSellerId = null;
        seededBidderId = null;
        seededParcelUuid = null;
        seededSellerAvatar = null;
        seededWinnerAvatar = null;
    }

    @Test
    void winnerOwnsParcel_stampsTransferConfirmed_broadcastsConfirmation() {
        seedTransferPendingEscrow();
        when(worldApi.fetchParcel(seededParcelUuid))
                .thenReturn(Mono.just(meta(seededWinnerAvatar, "agent")));

        OffsetDateTime before = OffsetDateTime.now();
        job.sweep();
        OffsetDateTime after = OffsetDateTime.now();

        Escrow refreshed = escrowRepo.findById(seededEscrowId).orElseThrow();
        assertThat(refreshed.getState()).isEqualTo(EscrowState.TRANSFER_PENDING);
        assertThat(refreshed.getTransferConfirmedAt()).isNotNull();
        assertThat(refreshed.getTransferConfirmedAt()).isBetween(before, after);
        assertThat(refreshed.getConsecutiveWorldApiFailures()).isZero();
        assertThat(refreshed.getLastCheckedAt()).isNotNull();

        assertThat(capturingEscrowPublisher.transferConfirmed).hasSize(1);
        EscrowTransferConfirmedEnvelope env = capturingEscrowPublisher.transferConfirmed.get(0);
        assertThat(env.type()).isEqualTo("ESCROW_TRANSFER_CONFIRMED");
        assertThat(env.auctionId()).isEqualTo(seededAuctionId);
        assertThat(env.escrowId()).isEqualTo(seededEscrowId);
        assertThat(env.state()).isEqualTo(EscrowState.TRANSFER_PENDING);

        assertThat(capturingEscrowPublisher.frozen).isEmpty();
        assertThat(fraudFlagRepo.findByAuctionId(seededAuctionId)).isEmpty();
    }

    @Test
    void strangerOwnsParcel_freezesWithUnknownOwnerFraud_broadcastsFreeze() {
        seedTransferPendingEscrow();
        UUID stranger = UUID.randomUUID();
        when(worldApi.fetchParcel(seededParcelUuid))
                .thenReturn(Mono.just(meta(stranger, "agent")));

        job.sweep();

        Escrow refreshed = escrowRepo.findById(seededEscrowId).orElseThrow();
        assertThat(refreshed.getState()).isEqualTo(EscrowState.FROZEN);
        assertThat(refreshed.getFrozenAt()).isNotNull();
        assertThat(refreshed.getFreezeReason()).isEqualTo("UNKNOWN_OWNER");

        List<FraudFlag> flags = fraudFlagRepo.findByAuctionId(seededAuctionId);
        assertThat(flags).hasSize(1);
        FraudFlag flag = flags.get(0);
        assertThat(flag.getReason()).isEqualTo(FraudFlagReason.ESCROW_UNKNOWN_OWNER);
        assertThat(flag.isResolved()).isFalse();
        assertThat(flag.getEvidenceJson())
                .containsEntry("observedOwnerUuid", stranger.toString())
                .containsEntry("expectedWinnerUuid", seededWinnerAvatar.toString())
                .containsEntry("expectedSellerUuid", seededSellerAvatar.toString())
                .containsEntry("observedOwnerType", "agent");

        assertThat(capturingEscrowPublisher.frozen).hasSize(1);
        EscrowFrozenEnvelope env = capturingEscrowPublisher.frozen.get(0);
        assertThat(env.type()).isEqualTo("ESCROW_FROZEN");
        assertThat(env.auctionId()).isEqualTo(seededAuctionId);
        assertThat(env.escrowId()).isEqualTo(seededEscrowId);
        assertThat(env.state()).isEqualTo(EscrowState.FROZEN);
        assertThat(env.reason()).isEqualTo("UNKNOWN_OWNER");

        assertThat(capturingEscrowPublisher.transferConfirmed).isEmpty();
    }

    @Test
    void parcelDeleted_freezesWithParcelDeletedFraud_broadcastsFreeze() {
        seedTransferPendingEscrow();
        when(worldApi.fetchParcel(any(UUID.class)))
                .thenReturn(Mono.error(new ParcelNotFoundInSlException(seededParcelUuid)));

        job.sweep();

        Escrow refreshed = escrowRepo.findById(seededEscrowId).orElseThrow();
        assertThat(refreshed.getState()).isEqualTo(EscrowState.FROZEN);
        assertThat(refreshed.getFreezeReason()).isEqualTo("PARCEL_DELETED");

        List<FraudFlag> flags = fraudFlagRepo.findByAuctionId(seededAuctionId);
        assertThat(flags).hasSize(1);
        assertThat(flags.get(0).getReason()).isEqualTo(FraudFlagReason.ESCROW_PARCEL_DELETED);
        assertThat(flags.get(0).getEvidenceJson())
                .containsEntry("parcelUuid", seededParcelUuid.toString());

        assertThat(capturingEscrowPublisher.frozen).hasSize(1);
        assertThat(capturingEscrowPublisher.frozen.get(0).reason()).isEqualTo("PARCEL_DELETED");
    }

    // -------------------------------------------------------------------------
    // Seeding
    // -------------------------------------------------------------------------

    private void seedTransferPendingEscrow() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            UUID sellerAvatar = UUID.randomUUID();
            UUID winnerAvatar = UUID.randomUUID();
            UUID parcelUuid = UUID.randomUUID();

            User seller = userRepo.save(User.builder()
                    .email("escrow-monitor-seller-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("Escrow Monitor Seller")
                    .slAvatarUuid(sellerAvatar)
                    .verified(true)
                    .build());
            User bidder = userRepo.save(User.builder()
                    .email("escrow-monitor-bidder-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("Escrow Monitor Bidder")
                    .slAvatarUuid(winnerAvatar)
                    .verified(true)
                    .build());
            Parcel parcel = parcelRepo.save(Parcel.builder()
                    .slParcelUuid(parcelUuid)
                    .ownerUuid(sellerAvatar)
                    .ownerType("agent")
                    .regionName("EscrowMonitorRegion")
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
                    .startsAt(now.minusHours(3))
                    .endsAt(now.minusHours(1))
                    .originalEndsAt(now.minusHours(1))
                    .endedAt(now.minusHours(1))
                    .endOutcome(AuctionEndOutcome.SOLD)
                    .winnerUserId(bidder.getId())
                    .finalBidAmount(finalBid)
                    .build());
            Escrow escrow = escrowRepo.save(Escrow.builder()
                    .auction(auction)
                    .state(EscrowState.TRANSFER_PENDING)
                    .finalBidAmount(finalBid)
                    .commissionAmt(commissionCalculator.commission(finalBid))
                    .payoutAmt(commissionCalculator.payout(finalBid))
                    .paymentDeadline(now.minusMinutes(30))
                    .transferDeadline(now.plusHours(71))
                    .fundedAt(now.minusMinutes(30))
                    .consecutiveWorldApiFailures(0)
                    .build());

            seededSellerId = seller.getId();
            seededBidderId = bidder.getId();
            seededParcelId = parcel.getId();
            seededAuctionId = auction.getId();
            seededEscrowId = escrow.getId();
            seededParcelUuid = parcelUuid;
            seededSellerAvatar = sellerAvatar;
            seededWinnerAvatar = winnerAvatar;
        });
    }

    private ParcelMetadata meta(UUID owner, String ownerType) {
        return new ParcelMetadata(
                seededParcelUuid, owner, ownerType,
                "Test Parcel", "EscrowMonitorRegion",
                1024, "desc", "http://example.com/snap.jpg", "MODERATE",
                128.0, 64.0, 22.0);
    }
}
