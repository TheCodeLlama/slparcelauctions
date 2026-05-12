package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
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
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.auction.monitoring.ListingSuspension;
import com.slparcelauctions.backend.auction.monitoring.ListingSuspensionCause;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.testsupport.TestRegions;

@ExtendWith(MockitoExtension.class)
class CancellationServiceTest {

    @Mock AuctionRepository auctionRepo;
    @Mock BidRepository bidRepo;
    @Mock CancellationLogRepository logRepo;
    @Mock ListingFeeRefundRepository refundRepo;
    @Mock UserRepository userRepo;
    @Mock com.slparcelauctions.backend.bot.BotMonitorLifecycleService monitorLifecycle;
    @Mock AuctionBroadcastPublisher broadcastPublisher;
    @Mock NotificationPublisher notificationPublisher;
    @Mock BanCheckService banCheckService;
    @Mock com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer realtyGroupAuthorizer;
    @Mock com.slparcelauctions.backend.auction.monitoring.ListingSuspensionRepository listingSuspensionRepo;

    CancellationService service;

    private User seller;
    private Clock fixed;
    private CancellationPenaltyProperties penaltyProps;

    @BeforeEach
    void setUp() {
        fixed = Clock.fixed(Instant.parse("2026-04-16T12:00:00Z"), ZoneOffset.UTC);
        penaltyProps = new CancellationPenaltyProperties(
                new CancellationPenaltyProperties.Penalty(1000L, 2500L, 30),
                48);
        service = new CancellationService(
                auctionRepo, bidRepo, logRepo, refundRepo, userRepo, monitorLifecycle,
                broadcastPublisher, notificationPublisher, penaltyProps, banCheckService,
                realtyGroupAuthorizer, listingSuspensionRepo, fixed);
        seller = User.builder().id(42L).email("s@example.com").username("s")
                .cancelledWithBids(0)
                .penaltyBalanceOwed(0L)
                .bannedFromListing(false)
                .build();
        lenient().when(auctionRepo.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(userRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(seller));
    }

    /**
     * The service re-fetches via {@code findByIdForUpdate} for the pessimistic
     * lock. The tests pre-build an {@link Auction} and route it through the
     * stubbed repository so each {@code cancel} call exercises the real
     * pessimistic-lock entry path.
     */
    private Auction cancel(Auction a, String reason) {
        lenient().when(auctionRepo.findByIdForUpdate(anyLong())).thenReturn(Optional.of(a));
        return service.cancel(a.getId(), reason, null);
    }

    // -------------------------------------------------------------------------
    // Pre-live cancellation: allowed. Refund iff fee paid.
    // -------------------------------------------------------------------------

    @Test
    void cancel_draft_noFee_noBids_succeedsWithLogOnly() {
        Auction a = build(AuctionStatus.DRAFT, false, 0);

        Auction out = cancel(a, "changed my mind");

        assertThat(out.getStatus()).isEqualTo(AuctionStatus.CANCELLED);
        verify(logRepo).save(any(CancellationLog.class));
        verify(refundRepo, never()).save(any());
        verify(userRepo, never()).save(any());
        assertThat(seller.getCancelledWithBids()).isZero();
    }

    @Test
    void cancel_draftPaid_createsPendingRefund() {
        Auction a = build(AuctionStatus.DRAFT_PAID, true, 0);
        a.setListingFeeAmt(100L);

        cancel(a, null);

        ArgumentCaptor<ListingFeeRefund> cap = ArgumentCaptor.forClass(ListingFeeRefund.class);
        verify(refundRepo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(cap.getValue().getAmount()).isEqualTo(100L);
    }

    @Test
    void cancel_verificationPending_withFee_createsRefund() {
        Auction a = build(AuctionStatus.VERIFICATION_PENDING, true, 0);
        a.setListingFeeAmt(100L);

        cancel(a, null);

        verify(refundRepo).save(any(ListingFeeRefund.class));
    }

    @Test
    void cancel_verificationFailed_withFee_createsRefund() {
        Auction a = build(AuctionStatus.VERIFICATION_FAILED, true, 0);
        a.setListingFeeAmt(100L);

        cancel(a, null);

        verify(refundRepo).save(any(ListingFeeRefund.class));
    }

    // -------------------------------------------------------------------------
    // ACTIVE cancellation: no refund. Counter only if bids.
    // -------------------------------------------------------------------------

    @Test
    void cancel_active_noBids_noRefund_noCounterBump() {
        Auction a = build(AuctionStatus.ACTIVE, true, 0);
        a.setListingFeeAmt(100L);
        a.setEndsAt(OffsetDateTime.now(fixed).plusHours(10));

        cancel(a, null);

        verify(refundRepo, never()).save(any());
        verify(userRepo, never()).save(any());
        assertThat(seller.getCancelledWithBids()).isZero();
    }

    @Test
    void cancel_active_withBids_bumpsCounter_noRefund() {
        Auction a = build(AuctionStatus.ACTIVE, true, 5);
        a.setListingFeeAmt(100L);
        a.setEndsAt(OffsetDateTime.now(fixed).plusHours(10));

        cancel(a, null);

        verify(refundRepo, never()).save(any());
        verify(userRepo).save(seller);
        assertThat(seller.getCancelledWithBids()).isEqualTo(1);
    }

    @Test
    void cancel_activeAfterEndsAt_throwsInvalidState() {
        Auction a = build(AuctionStatus.ACTIVE, false, 0);
        a.setEndsAt(OffsetDateTime.now(fixed).minusMinutes(1));

        assertThatThrownBy(() -> cancel(a, null))
                .isInstanceOf(InvalidAuctionStateException.class);
    }

    // -------------------------------------------------------------------------
    // Disallowed states: throw 409
    // -------------------------------------------------------------------------

    @Test
    void cancel_ended_throwsInvalidState() {
        Auction a = build(AuctionStatus.ENDED, false, 0);

        assertThatThrownBy(() -> cancel(a, null))
                .isInstanceOf(InvalidAuctionStateException.class);
    }

    @Test
    void cancel_escrowPending_throwsInvalidState() {
        Auction a = build(AuctionStatus.ESCROW_PENDING, false, 0);

        assertThatThrownBy(() -> cancel(a, null))
                .isInstanceOf(InvalidAuctionStateException.class);
    }

    @Test
    void cancel_completed_throwsInvalidState() {
        Auction a = build(AuctionStatus.COMPLETED, false, 0);

        assertThatThrownBy(() -> cancel(a, null))
                .isInstanceOf(InvalidAuctionStateException.class);
    }

    @Test
    void cancel_alreadyCancelled_throwsInvalidState() {
        Auction a = build(AuctionStatus.CANCELLED, false, 0);

        assertThatThrownBy(() -> cancel(a, null))
                .isInstanceOf(InvalidAuctionStateException.class);
    }

    @Test
    void cancel_expired_throwsInvalidState() {
        Auction a = build(AuctionStatus.EXPIRED, false, 0);

        assertThatThrownBy(() -> cancel(a, null))
                .isInstanceOf(InvalidAuctionStateException.class);
    }

    @Test
    void cancel_disputed_throwsInvalidState() {
        Auction a = build(AuctionStatus.DISPUTED, false, 0);

        assertThatThrownBy(() -> cancel(a, null))
                .isInstanceOf(InvalidAuctionStateException.class);
    }

    @Test
    void cancel_missingAuction_throwsNotFound() {
        lenient().when(auctionRepo.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(999L, "gone", null))
                .isInstanceOf(AuctionNotFoundException.class);
    }

    @Test
    void cancel_cancellationLogCapturesFromStatus() {
        Auction a = build(AuctionStatus.DRAFT, false, 0);

        cancel(a, "manual test reason");

        ArgumentCaptor<CancellationLog> cap = ArgumentCaptor.forClass(CancellationLog.class);
        verify(logRepo).save(cap.capture());
        CancellationLog log = cap.getValue();
        assertThat(log.getCancelledFromStatus()).isEqualTo("DRAFT");
        assertThat(log.getHadBids()).isFalse();
        assertThat(log.getReason()).isEqualTo("manual test reason");
    }

    // -------------------------------------------------------------------------
    // adminCancelExpiredBulkSuspend -- Sub-project F §10.2
    // -------------------------------------------------------------------------

    /**
     * Routes a {@link CancellationService#adminCancelExpiredBulkSuspend} call
     * through the mocked repositories. Builds a SUSPENDED auction and a matching
     * ListingSuspension row so the method observes the same shape its real
     * caller ({@code BulkSuspendedListingExpiryTask}) would feed it.
     */
    private Auction suspendedAuction(int bidCount) {
        Auction a = build(AuctionStatus.SUSPENDED, true, bidCount);
        a.setListingFeeAmt(100L);
        a.setEndsAt(OffsetDateTime.now(fixed).plusHours(10));
        return a;
    }

    private ListingSuspension stubListingSuspension(Long id, Auction auction) {
        ListingSuspension ls = ListingSuspension.builder()
                .auction(auction)
                .cause(ListingSuspensionCause.ADMIN_GROUP_BULK)
                .bulkActionId(UUID.randomUUID())
                .reason("BULK_SUSPEND")
                .suspendedAt(OffsetDateTime.now(fixed).minusHours(49))
                .build();
        // Mocked find returns this row; cancelledAt mutates in-place on the
        // returned reference so the test asserts directly on it.
        lenient().when(listingSuspensionRepo.findById(id)).thenReturn(Optional.of(ls));
        return ls;
    }

    @Test
    void adminCancelExpiredBulkSuspend_refundsAllReservedBids() {
        Auction a = suspendedAuction(3);
        stubListingSuspension(7L, a);
        lenient().when(auctionRepo.findByIdForUpdate(a.getId())).thenReturn(Optional.of(a));
        when(bidRepo.findDistinctBidderUserIdsByAuctionId(a.getId()))
                .thenReturn(List.of(101L, 102L, 103L));

        service.adminCancelExpiredBulkSuspend(a.getId(), 7L);

        // Bidder fan-out is the cause-neutral mechanism that releases proxy
        // intent on the bidder side -- mirroring how cancel() and cancelByAdmin
        // surface "your bid is no longer held" to bidders. Verifying the
        // fan-out is the unit-test-level proxy for the refund pathway.
        verify(notificationPublisher).listingCancelledBySellerFanout(
                eq(a.getId()), eq(List.of(101L, 102L, 103L)), eq(a.getTitle()), isNull());
    }

    @Test
    void adminCancelExpiredBulkSuspend_setsAuctionStatusCancelled() {
        Auction a = suspendedAuction(0);
        stubListingSuspension(7L, a);
        lenient().when(auctionRepo.findByIdForUpdate(a.getId())).thenReturn(Optional.of(a));

        service.adminCancelExpiredBulkSuspend(a.getId(), 7L);

        assertThat(a.getStatus()).isEqualTo(AuctionStatus.CANCELLED);
        verify(auctionRepo).save(a);
    }

    @Test
    void adminCancelExpiredBulkSuspend_writesCancellationLogWithAdminBulkExpired() {
        Auction a = suspendedAuction(2);
        stubListingSuspension(7L, a);
        lenient().when(auctionRepo.findByIdForUpdate(a.getId())).thenReturn(Optional.of(a));

        service.adminCancelExpiredBulkSuspend(a.getId(), 7L);

        ArgumentCaptor<CancellationLog> cap = ArgumentCaptor.forClass(CancellationLog.class);
        verify(logRepo).save(cap.capture());
        CancellationLog row = cap.getValue();
        assertThat(row.getPenaltyKind()).isEqualTo(CancellationOffenseKind.ADMIN_BULK_EXPIRED);
        assertThat(row.getPenaltyAmountL()).isNull();
        assertThat(row.getCancelledFromStatus()).isEqualTo("SUSPENDED");
        assertThat(row.getHadBids()).isTrue();
        // No seller ladder advance -- counter untouched, no userRepo.save.
        assertThat(seller.getCancelledWithBids()).isZero();
        verify(userRepo, never()).save(any());
    }

    @Test
    void adminCancelExpiredBulkSuspend_setsListingSuspensionCancelledAt() {
        Auction a = suspendedAuction(0);
        ListingSuspension ls = stubListingSuspension(7L, a);
        lenient().when(auctionRepo.findByIdForUpdate(a.getId())).thenReturn(Optional.of(a));

        service.adminCancelExpiredBulkSuspend(a.getId(), 7L);

        assertThat(ls.getCancelledAt()).isEqualTo(OffsetDateTime.now(fixed));
        assertThat(ls.getLiftedAt()).isNull();
    }

    @Test
    void adminCancelExpiredBulkSuspend_publishesBidderFanoutAndSellerNotification() {
        Auction a = suspendedAuction(2);
        stubListingSuspension(7L, a);
        lenient().when(auctionRepo.findByIdForUpdate(a.getId())).thenReturn(Optional.of(a));
        when(bidRepo.findDistinctBidderUserIdsByAuctionId(a.getId()))
                .thenReturn(List.of(201L, 202L));

        service.adminCancelExpiredBulkSuspend(a.getId(), 7L);

        // Bidder fan-out is cause-neutral (null reason) so bidders never see
        // admin attribution per FOOTGUNS §F.104.
        verify(notificationPublisher).listingCancelledBySellerFanout(
                eq(a.getId()), eq(List.of(201L, 202L)), eq(a.getTitle()), isNull());
        // Seller gets the F-specific helper with BULK_SUSPEND_TIMER_EXPIRED reason.
        verify(notificationPublisher).listingAutoCancelledFromBulkSuspend(
                seller.getId(), a.getId(), a.getTitle());
    }

    @Test
    void adminCancelExpiredBulkSuspend_isIdempotentAcrossDuplicateCalls() {
        Auction a = suspendedAuction(1);
        stubListingSuspension(7L, a);
        lenient().when(auctionRepo.findByIdForUpdate(a.getId())).thenReturn(Optional.of(a));

        service.adminCancelExpiredBulkSuspend(a.getId(), 7L);
        // After the first call the auction is CANCELLED. The mocked
        // findByIdForUpdate still returns the same instance; the method must
        // observe status != SUSPENDED and return without writing a second log
        // row or stamping cancelled_at again.
        service.adminCancelExpiredBulkSuspend(a.getId(), 7L);

        verify(logRepo, times(1)).save(any(CancellationLog.class));
        verify(auctionRepo, times(1)).save(any(Auction.class));
        // Second call returned before touching the listing suspension repo a
        // second time -- assert by counting findById calls.
        verify(listingSuspensionRepo, times(1)).findById(7L);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
                .createdAt(OffsetDateTime.now(fixed))
                .updatedAt(OffsetDateTime.now(fixed))
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
