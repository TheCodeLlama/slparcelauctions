package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.admin.ban.BanCheckService;
import com.slparcelauctions.backend.auction.broadcast.AuctionBroadcastPublisher;
import com.slparcelauctions.backend.auction.dto.AuctionCancelledEnvelope;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.testsupport.TestRegions;

/**
 * Boundary coverage for the cancellation-penalty ladder
 * (Epic 08 sub-spec 2 §2). Each test pins a single ladder index and
 * asserts the snapshot on the {@link CancellationLog} row plus the live
 * consequence applied to {@link User} (debt, suspension, ban) and
 * {@link Auction#getPostCancelWatchUntil()}. The {@code countPriorOffensesWithBids}
 * stub drives the ladder index — the test deliberately runs against the
 * real {@link CancellationService#cancel} so the COUNT-before-INSERT
 * ordering and the snapshot semantics are both exercised.
 */
@ExtendWith(MockitoExtension.class)
class CancellationServiceLadderTest {

    @Mock AuctionRepository auctionRepo;
    @Mock BidRepository bidRepo;
    @Mock CancellationLogRepository logRepo;
    @Mock ListingFeeRefundRepository refundRepo;
    @Mock UserRepository userRepo;
    @Mock com.slparcelauctions.backend.bot.BotMonitorLifecycleService monitorLifecycle;
    @Mock AuctionBroadcastPublisher broadcastPublisher;
    @Mock NotificationPublisher notificationPublisher;
    @Mock BanCheckService banCheckService;

    CancellationService service;

    private User seller;
    private Clock fixed;
    private OffsetDateTime now;
    private CancellationPenaltyProperties penaltyProps;

    @BeforeEach
    void setUp() {
        fixed = Clock.fixed(Instant.parse("2026-04-16T12:00:00Z"), ZoneOffset.UTC);
        now = OffsetDateTime.now(fixed);
        penaltyProps = new CancellationPenaltyProperties(
                new CancellationPenaltyProperties.Penalty(1000L, 2500L, 30),
                48);
        service = new CancellationService(
                auctionRepo, bidRepo, logRepo, refundRepo, userRepo, monitorLifecycle,
                broadcastPublisher, notificationPublisher, penaltyProps, banCheckService, fixed);
        seller = User.builder().id(42L).email("s@example.com").username("s")
                .cancelledWithBids(0)
                .penaltyBalanceOwed(0L)
                .bannedFromListing(false)
                .build();
        lenient().when(auctionRepo.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(userRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(seller));
    }

    private Auction cancel(Auction a, String reason) {
        lenient().when(auctionRepo.findByIdForUpdate(anyLong())).thenReturn(Optional.of(a));
        return service.cancel(a.getId(), reason, null);
    }

    private CancellationLog capturedLog() {
        ArgumentCaptor<CancellationLog> cap = ArgumentCaptor.forClass(CancellationLog.class);
        verify(logRepo).save(cap.capture());
        return cap.getValue();
    }

    // -------------------------------------------------------------------------
    // Ladder boundaries on ACTIVE-with-bids cancellations
    // -------------------------------------------------------------------------

    @Test
    void cancel_activeWithBids_zeroPriorOffenses_writesWarningKind() {
        when(logRepo.countPriorOffensesWithBids(42L)).thenReturn(0L);
        Auction a = activeWithBids();

        cancel(a, "first");

        CancellationLog log = capturedLog();
        assertThat(log.getPenaltyKind()).isEqualTo(CancellationOffenseKind.WARNING);
        assertThat(log.getPenaltyAmountL()).isNull();
        assertThat(seller.getPenaltyBalanceOwed()).isZero();
        assertThat(seller.getListingSuspensionUntil()).isNull();
        assertThat(seller.getBannedFromListing()).isFalse();
        assertThat(a.getPostCancelWatchUntil()).isEqualTo(now.plusHours(48));
    }

    @Test
    void cancel_activeWithBids_onePriorOffense_writesPenaltyKindAndDebits1000() {
        when(logRepo.countPriorOffensesWithBids(42L)).thenReturn(1L);
        Auction a = activeWithBids();

        cancel(a, "second");

        CancellationLog log = capturedLog();
        assertThat(log.getPenaltyKind()).isEqualTo(CancellationOffenseKind.PENALTY);
        assertThat(log.getPenaltyAmountL()).isEqualTo(1000L);
        assertThat(seller.getPenaltyBalanceOwed()).isEqualTo(1000L);
        assertThat(seller.getListingSuspensionUntil()).isNull();
        assertThat(seller.getBannedFromListing()).isFalse();
        assertThat(a.getPostCancelWatchUntil()).isEqualTo(now.plusHours(48));
    }

    @Test
    void cancel_activeWithBids_twoPriorOffenses_writesPenaltyAnd30dAndDebits2500AndSuspends() {
        when(logRepo.countPriorOffensesWithBids(42L)).thenReturn(2L);
        Auction a = activeWithBids();

        cancel(a, "third");

        CancellationLog log = capturedLog();
        assertThat(log.getPenaltyKind()).isEqualTo(CancellationOffenseKind.PENALTY_AND_30D);
        assertThat(log.getPenaltyAmountL()).isEqualTo(2500L);
        assertThat(seller.getPenaltyBalanceOwed()).isEqualTo(2500L);
        assertThat(seller.getListingSuspensionUntil())
                .isCloseTo(now.plusDays(30),
                        new org.assertj.core.data.TemporalUnitWithinOffset(1, ChronoUnit.SECONDS));
        assertThat(seller.getBannedFromListing()).isFalse();
    }

    @Test
    void cancel_activeWithBids_threePriorOffenses_writesPermanentBan() {
        when(logRepo.countPriorOffensesWithBids(42L)).thenReturn(3L);
        seller.setPenaltyBalanceOwed(3500L);
        OffsetDateTime priorSuspension = now.minusDays(5);
        seller.setListingSuspensionUntil(priorSuspension);
        Auction a = activeWithBids();

        cancel(a, "fourth");

        CancellationLog log = capturedLog();
        assertThat(log.getPenaltyKind()).isEqualTo(CancellationOffenseKind.PERMANENT_BAN);
        assertThat(log.getPenaltyAmountL()).isNull();
        assertThat(seller.getBannedFromListing()).isTrue();
        // Existing debt + suspension are not modified by the PERMANENT_BAN branch.
        assertThat(seller.getPenaltyBalanceOwed()).isEqualTo(3500L);
        assertThat(seller.getListingSuspensionUntil()).isEqualTo(priorSuspension);
    }

    @Test
    void cancel_activeWithBids_fivePriorOffenses_stillWritesPermanentBan() {
        when(logRepo.countPriorOffensesWithBids(42L)).thenReturn(5L);
        seller.setBannedFromListing(true); // already banned
        Auction a = activeWithBids();

        cancel(a, "sixth");

        CancellationLog log = capturedLog();
        assertThat(log.getPenaltyKind()).isEqualTo(CancellationOffenseKind.PERMANENT_BAN);
        // Idempotent — already true.
        assertThat(seller.getBannedFromListing()).isTrue();
    }

    @Test
    void cancel_activeWithBids_existingPenaltyBalance_addsToIt() {
        when(logRepo.countPriorOffensesWithBids(42L)).thenReturn(2L);
        seller.setPenaltyBalanceOwed(1000L); // already owes from a prior PENALTY
        Auction a = activeWithBids();

        cancel(a, "third while owing");

        // 1000 (existing) + 2500 (third-offense) = 3500
        assertThat(seller.getPenaltyBalanceOwed()).isEqualTo(3500L);
    }

    // -------------------------------------------------------------------------
    // Non-laddered paths
    // -------------------------------------------------------------------------

    @Test
    void cancel_activeWithoutBids_writesNoneKind() {
        Auction a = build(AuctionStatus.ACTIVE, true, 0);
        a.setEndsAt(now.plusHours(10));

        cancel(a, "no bids yet");

        CancellationLog log = capturedLog();
        assertThat(log.getPenaltyKind()).isEqualTo(CancellationOffenseKind.NONE);
        assertThat(log.getPenaltyAmountL()).isNull();
        // Seller fields untouched.
        assertThat(seller.getCancelledWithBids()).isZero();
        assertThat(seller.getPenaltyBalanceOwed()).isZero();
        assertThat(seller.getListingSuspensionUntil()).isNull();
        assertThat(seller.getBannedFromListing()).isFalse();
        // Watcher window NOT armed.
        assertThat(a.getPostCancelWatchUntil()).isNull();
        // Counter increment + ladder count must NOT run.
        verify(userRepo, never()).save(any());
        verify(logRepo, never()).countPriorOffensesWithBids(anyLong());
    }

    @Test
    void cancel_preActiveStatus_writesNoneKindAndQueuesRefund() {
        Auction a = build(AuctionStatus.DRAFT_PAID, true, 0);
        a.setListingFeeAmt(100L);

        cancel(a, "changed mind");

        CancellationLog log = capturedLog();
        assertThat(log.getPenaltyKind()).isEqualTo(CancellationOffenseKind.NONE);
        assertThat(log.getPenaltyAmountL()).isNull();
        verify(refundRepo).save(any(ListingFeeRefund.class));
        assertThat(a.getPostCancelWatchUntil()).isNull();
        verify(logRepo, never()).countPriorOffensesWithBids(anyLong());
    }

    // -------------------------------------------------------------------------
    // WS broadcast — afterCommit registration
    // -------------------------------------------------------------------------

    @Test
    void cancel_publishesAuctionCancelledEnvelope() {
        when(logRepo.countPriorOffensesWithBids(42L)).thenReturn(0L);
        Auction a = activeWithBids();

        cancel(a, "broadcast me");

        ArgumentCaptor<AuctionCancelledEnvelope> cap =
                ArgumentCaptor.forClass(AuctionCancelledEnvelope.class);
        // No tx active → service publishes synchronously (mirrors ReviewService).
        verify(broadcastPublisher).publishCancelled(cap.capture());
        AuctionCancelledEnvelope env = cap.getValue();
        assertThat(env.type()).isEqualTo("AUCTION_CANCELLED");
        assertThat(env.auctionPublicId()).isEqualTo(a.getPublicId());
        assertThat(env.hadBids()).isTrue();
        assertThat(env.cancelledAt()).isEqualTo(now);
    }

    @Test
    void cancel_activeWithoutBids_publishesEnvelopeWithHadBidsFalse() {
        Auction a = build(AuctionStatus.ACTIVE, true, 0);
        a.setEndsAt(now.plusHours(10));

        cancel(a, "no bids");

        ArgumentCaptor<AuctionCancelledEnvelope> cap =
                ArgumentCaptor.forClass(AuctionCancelledEnvelope.class);
        verify(broadcastPublisher).publishCancelled(cap.capture());
        assertThat(cap.getValue().hadBids()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Auction activeWithBids() {
        Auction a = build(AuctionStatus.ACTIVE, true, 5);
        a.setEndsAt(now.plusHours(10));
        return a;
    }

    private static final UUID PARCEL_UUID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private Auction build(AuctionStatus status, boolean listingFeePaid, int bidCount) {
        Auction a = Auction.builder()
                .title("Test listing")
                .id(1L).seller(seller).slParcelUuid(PARCEL_UUID).status(status)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .startingBid(1000L).durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(listingFeePaid)
                .currentBid(0L).bidCount(bidCount)
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .tags(new HashSet<>())
                .createdAt(now)
                .updatedAt(now)
                .build();
        a.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(PARCEL_UUID)
                .ownerUuid(UUID.fromString("44444444-4444-4444-4444-444444444444"))
                .ownerType("agent")
                .parcelName("Test Parcel")
                .region(TestRegions.mainland())
                .regionName("Coniston").regionMaturityRating("MODERATE")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
        return a;
    }

}
