package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import com.slparcelauctions.backend.auction.exception.BrokerCancelNotApplicableException;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.exception.RealtyGroupPermissionDeniedException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.testsupport.TestRegions;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.wallet.BidReservationReleaseReason;
import com.slparcelauctions.backend.wallet.WalletService;

/**
 * Mockito-only unit tests for
 * {@link CancellationService#brokerCancel(Long, Long, String, String)} -- the
 * sub-project E Â§11.4 broker-cancel path. The brokerCancel method must:
 *
 * <ul>
 *   <li>Flip auction status to CANCELLED.</li>
 *   <li>Write a {@link CancellationLog} row with kind=BROKER_CANCEL,
 *       actor_user_id=broker, realty_group_id=group -- and NO penalty amount.</li>
 *   <li>Skip the seller penalty ladder entirely: never lock the seller row,
 *       never read {@code countPriorOffensesWithBids}, never save the seller,
 *       never increment {@code cancelledWithBids}, never arm
 *       {@code postCancelWatchUntil}.</li>
 *   <li>Authorize via MANAGE_ALL_LISTINGS on the auction's realty group.</li>
 *   <li>Reject case-1 (individual) and legacy auctions that don't carry a
 *       realty_group_sl_group_id with {@link BrokerCancelNotApplicableException}.</li>
 *   <li>Reject ACTIVE auctions whose endsAt has passed with
 *       {@link InvalidAuctionStateException}.</li>
 *   <li>Create a PENDING listing-fee refund when listingFeePaid=true (case-3
 *       refunds always issue, including from-ACTIVE; the group must be made
 *       whole).</li>
 *   <li>Broadcast {@link AuctionCancelledEnvelope} and notify the listing agent.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CancellationServiceBrokerCancelTest {

    @Mock AuctionRepository auctionRepo;
    @Mock BidRepository bidRepo;
    @Mock CancellationLogRepository logRepo;
    @Mock ListingFeeRefundRepository refundRepo;
    @Mock UserRepository userRepo;
    @Mock AuctionBroadcastPublisher broadcastPublisher;
    @Mock NotificationPublisher notificationPublisher;
    @Mock BanCheckService banCheckService;
    @Mock RealtyGroupAuthorizer realtyGroupAuthorizer;
    @Mock com.slparcelauctions.backend.auction.monitoring.ListingSuspensionRepository listingSuspensionRepo;
    @Mock WalletService walletService;
    @Mock com.slparcelauctions.backend.escrow.EscrowService escrowService;
    @Mock com.slparcelauctions.backend.escrow.EscrowRepository escrowRepo;

    CancellationService service;

    private User seller;
    private User listingAgent;
    private Clock fixed;
    private OffsetDateTime now;
    private CancellationPenaltyProperties penaltyProps;

    private static final Long SELLER_ID = 42L;
    private static final Long LISTING_AGENT_ID = 99L;
    private static final Long BROKER_ID = 77L;
    private static final Long GROUP_ID = 1234L;
    private static final Long SL_GROUP_ROW_ID = 5678L;
    private static final Long AUCTION_ID = 1L;

    @BeforeEach
    void setUp() {
        fixed = Clock.fixed(Instant.parse("2026-05-12T12:00:00Z"), ZoneOffset.UTC);
        now = OffsetDateTime.now(fixed);
        penaltyProps = new CancellationPenaltyProperties(
                new CancellationPenaltyProperties.Penalty(1000L, 2500L, 30),
                48);
        service = new CancellationService(
                auctionRepo, bidRepo, logRepo, refundRepo, userRepo,
                broadcastPublisher, notificationPublisher, penaltyProps, banCheckService,
                realtyGroupAuthorizer, listingSuspensionRepo, walletService,
                escrowService, escrowRepo, fixed);

        seller = User.builder().id(SELLER_ID).email("s@example.com").username("s")
                .cancelledWithBids(0)
                .penaltyBalanceOwed(0L)
                .bannedFromListing(false)
                .build();
        listingAgent = User.builder().id(LISTING_AGENT_ID).email("agent@example.com").username("agent")
                .cancelledWithBids(0)
                .penaltyBalanceOwed(0L)
                .bannedFromListing(false)
                .build();

        lenient().when(auctionRepo.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // Happy path
    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void brokerCancel_happyPath_noPenaltyLadderHit() {
        Auction a = case3Active(5);
        lenient().when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));

        Auction out = service.brokerCancel(BROKER_ID, AUCTION_ID, "agent removed listing", null);

        // Auction flipped to CANCELLED.
        assertThat(out.getStatus()).isEqualTo(AuctionStatus.CANCELLED);

        // Log row carries kind=BROKER_CANCEL + actor + group.
        CancellationLog log = capturedLog();
        assertThat(log.getPenaltyKind()).isEqualTo(CancellationOffenseKind.BROKER_CANCEL);
        assertThat(log.getPenaltyAmountL()).isNull();
        assertThat(log.getActorUserId()).isEqualTo(BROKER_ID);
        assertThat(log.getRealtyGroupId()).isEqualTo(GROUP_ID);
        assertThat(log.getCancelledFromStatus()).isEqualTo("ACTIVE");
        assertThat(log.getHadBids()).isTrue();
        assertThat(log.getCancelledByAdminId()).isNull();

        // Penalty ladder NOT exercised: seller row never locked, never saved,
        // never counter-bumped, watch window never armed, ban-check never called.
        verifyNoInteractions(userRepo);
        verify(logRepo, never()).countPriorOffensesWithBids(anyLong());
        verifyNoInteractions(banCheckService);
        assertThat(seller.getCancelledWithBids()).isZero();
        assertThat(seller.getPenaltyBalanceOwed()).isZero();
        assertThat(seller.getBannedFromListing()).isFalse();
        assertThat(out.getPostCancelWatchUntil()).isNull();

        // Notification + broadcast both fired.
        verify(notificationPublisher).brokerCancelled(
                eq(LISTING_AGENT_ID), eq(AUCTION_ID), eq("Case 3 Test Lot"),
                eq(BROKER_ID), eq("agent removed listing"));
        verify(broadcastPublisher).publishCancelled(any(AuctionCancelledEnvelope.class));

        // Wallet reservation release runs on every cancel path -- spec Â§10.2
        // step 2 / Epic 08 sub-spec 2 acceptance criterion #4.
        verify(walletService).releaseReservationsForAuction(
                eq(AUCTION_ID), eq(BidReservationReleaseReason.AUCTION_CANCELLED));
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // Authorization
    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void brokerCancel_authorizesViaManageAllListings() {
        Auction a = case3Active(0);
        lenient().when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));

        service.brokerCancel(BROKER_ID, AUCTION_ID, "permitted action", null);

        verify(realtyGroupAuthorizer).assertCan(
                BROKER_ID, GROUP_ID, RealtyGroupPermission.MANAGE_ALL_LISTINGS);
    }

    @Test
    void brokerCancel_permissionDenied_propagates() {
        Auction a = case3Active(0);
        lenient().when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));
        doThrow(new RealtyGroupPermissionDeniedException(RealtyGroupPermission.MANAGE_ALL_LISTINGS))
                .when(realtyGroupAuthorizer).assertCan(
                        BROKER_ID, GROUP_ID, RealtyGroupPermission.MANAGE_ALL_LISTINGS);

        assertThatThrownBy(() ->
                service.brokerCancel(BROKER_ID, AUCTION_ID, "blocked", null))
                .isInstanceOf(RealtyGroupPermissionDeniedException.class);

        // No state mutation occurred.
        verify(logRepo, never()).save(any());
        verify(auctionRepo, never()).save(any());
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // Eligibility â€” case discrimination
    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void brokerCancel_individualAuction_throws_BrokerCancelNotApplicable() {
        // Case-1: realty_group_sl_group_id is null.
        Auction a = buildActive(5);
        a.setRealtyGroupSlGroupId(null);
        a.setRealtyGroupId(null);
        a.setListingAgent(null);
        lenient().when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));

        assertThatThrownBy(() ->
                service.brokerCancel(BROKER_ID, AUCTION_ID, "shouldn't apply", null))
                .isInstanceOf(BrokerCancelNotApplicableException.class);

        verifyNoInteractions(realtyGroupAuthorizer);
        verify(logRepo, never()).save(any());
    }

    @Test
    void brokerCancel_case1Auction_throws_BrokerCancelNotApplicable() {
        // "Case 1" -- legacy individual listing with no realty group affiliation
        // at all (no listing agent, no group). Same eligibility outcome as
        // individual auctions above; reaffirms BrokerCancelNotApplicable is
        // raised for any non-case-3 row.
        Auction a = buildActive(0);
        a.setRealtyGroupSlGroupId(null);
        a.setRealtyGroupId(null);
        a.setListingAgent(null);
        lenient().when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));

        assertThatThrownBy(() ->
                service.brokerCancel(BROKER_ID, AUCTION_ID, "case-1 reject", null))
                .isInstanceOf(BrokerCancelNotApplicableException.class);
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // State preconditions
    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void brokerCancel_alreadyEnded_throws_InvalidState() {
        Auction a = case3Active(0);
        a.setEndsAt(now.minusMinutes(5)); // past endsAt â†’ ACTIVE but expired
        lenient().when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));

        assertThatThrownBy(() ->
                service.brokerCancel(BROKER_ID, AUCTION_ID, "too late", null))
                .isInstanceOf(InvalidAuctionStateException.class);

        verifyNoInteractions(realtyGroupAuthorizer);
        verify(logRepo, never()).save(any());
    }

    @Test
    void brokerCancel_terminalStatus_throws_InvalidState() {
        Auction a = build(AuctionStatus.COMPLETED, true, 0);
        wireCase3Fields(a);
        lenient().when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));

        assertThatThrownBy(() ->
                service.brokerCancel(BROKER_ID, AUCTION_ID, "wrong state", null))
                .isInstanceOf(InvalidAuctionStateException.class);

        verifyNoInteractions(realtyGroupAuthorizer);
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // Refund behaviour
    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void brokerCancel_refundCreatedForCase3WithListingFeePaid() {
        Auction a = case3Active(0);
        a.setListingFeePaid(true);
        a.setListingFeeAmt(150L);
        lenient().when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));

        service.brokerCancel(BROKER_ID, AUCTION_ID, "refund flow", null);

        ArgumentCaptor<ListingFeeRefund> cap = ArgumentCaptor.forClass(ListingFeeRefund.class);
        verify(refundRepo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(cap.getValue().getAmount()).isEqualTo(150L);
        assertThat(cap.getValue().getAuction()).isSameAs(a);
    }

    @Test
    void brokerCancel_noRefundWhenListingFeeUnpaid() {
        Auction a = case3Active(0);
        a.setListingFeePaid(false);
        lenient().when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));

        service.brokerCancel(BROKER_ID, AUCTION_ID, "no fee", null);

        verify(refundRepo, never()).save(any());
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // Helpers
    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private CancellationLog capturedLog() {
        ArgumentCaptor<CancellationLog> cap = ArgumentCaptor.forClass(CancellationLog.class);
        verify(logRepo).save(cap.capture());
        return cap.getValue();
    }

    /** Active auction in a case-3 setup. */
    private Auction case3Active(int bidCount) {
        Auction a = buildActive(bidCount);
        wireCase3Fields(a);
        return a;
    }

    private Auction buildActive(int bidCount) {
        Auction a = build(AuctionStatus.ACTIVE, true, bidCount);
        a.setEndsAt(now.plusHours(24));
        return a;
    }

    private void wireCase3Fields(Auction a) {
        a.setRealtyGroupId(GROUP_ID);
        a.setRealtyGroupSlGroupId(SL_GROUP_ROW_ID);
        a.setListingAgent(listingAgent);
        a.setAgentCommissionRate(new BigDecimal("0.10"));
    }

    private static final UUID PARCEL_UUID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private Auction build(AuctionStatus status, boolean listingFeePaid, int bidCount) {
        Auction a = Auction.builder()
                .title("Case 3 Test Lot")
                .id(AUCTION_ID).seller(seller).slParcelUuid(PARCEL_UUID).status(status)

                .startingBid(1000L).durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(listingFeePaid)
                .listingFeeAmt(100L)
                .currentBid(bidCount > 0 ? 1500L : 0L).bidCount(bidCount)
                .commissionRate(new BigDecimal("0.05"))
                .tags(new HashSet<>())
                .createdAt(now)
                .updatedAt(now)
                .build();
        a.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(PARCEL_UUID)
                .ownerUuid(UUID.fromString("44444444-4444-4444-4444-444444444444"))
                .ownerType("group")
                .parcelName("Case 3 Parcel")
                .region(TestRegions.mainland())
                .regionName("Coniston").regionMaturityRating("MODERATE")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
        return a;
    }
}
