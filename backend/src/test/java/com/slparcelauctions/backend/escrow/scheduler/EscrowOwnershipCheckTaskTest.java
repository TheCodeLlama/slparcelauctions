package com.slparcelauctions.backend.escrow.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowService;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.escrow.FreezeReason;
import com.slparcelauctions.backend.escrow.terminal.EscrowConfigProperties;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroup;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroupRepository;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.region.dto.RegionPageData;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.dto.ParcelPageData;
import com.slparcelauctions.backend.sl.exception.ExternalApiTimeoutException;
import com.slparcelauctions.backend.sl.exception.ParcelNotFoundInSlException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import reactor.core.publisher.Mono;

/**
 * Unit coverage for {@link EscrowOwnershipCheckTask}. Mirrors the structure
 * of {@code OwnershipCheckTaskTest} for the auction-level monitor, but
 * covers the three escrow-specific outcomes (winner-match, seller-still-owns,
 * unknown third party) plus the two failure branches and the sub-/at-threshold
 * freeze pivot.
 */
@ExtendWith(MockitoExtension.class)
class EscrowOwnershipCheckTaskTest {

    private static final Long ESCROW_ID = 501L;
    private static final Long AUCTION_ID = 42L;
    private static final Long SELLER_ID = 7L;
    private static final Long WINNER_ID = 8L;
    private static final UUID PARCEL_UUID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SELLER_AVATAR = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID WINNER_AVATAR = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID STRANGER_AVATAR = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID GROUP_UUID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final Long REALTY_GROUP_SL_GROUP_ID = 99L;
    private static final int FAILURE_THRESHOLD = 5;

    @Mock EscrowRepository escrowRepo;
    @Mock EscrowService escrowService;
    @Mock SlWorldApiClient worldApi;
    @Mock UserRepository userRepo;
    @Mock RealtyGroupSlGroupRepository slGroupRepo;
    @Mock EscrowConfigProperties props;

    EscrowOwnershipCheckTask task;
    Clock fixed;

    @BeforeEach
    void setUp() {
        fixed = Clock.fixed(Instant.parse("2026-04-20T12:00:00Z"), ZoneOffset.UTC);
        task = new EscrowOwnershipCheckTask(
                escrowRepo, escrowService, worldApi, userRepo, slGroupRepo, props, fixed);
        lenient().when(props.ownershipApiFailureThreshold()).thenReturn(FAILURE_THRESHOLD);
        lenient().when(props.ownershipReminderDelay()).thenReturn(Duration.ofHours(24));
    }

    @Test
    void winnerMatch_delegatesToConfirmTransfer_noOtherSideEffects() {
        Escrow escrow = buildPending();
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
        when(worldApi.fetchParcelPage(PARCEL_UUID))
                .thenReturn(
                Mono.just(new ParcelPageData(meta(WINNER_AVATAR, "agent"), java.util.UUID.randomUUID())));
        stubWinner();

        task.checkOne(ESCROW_ID);

        OffsetDateTime now = OffsetDateTime.now(fixed);
        verify(escrowService).confirmTransfer(escrow, now);
        verify(escrowService, never()).freezeForFraud(any(), any(), any(), any());
        verify(escrowService, never()).stampChecked(any(), any());
        verify(escrowService, never()).incrementWorldApiFailure(any(), any());
    }

    @Test
    void sellerStillOwns_withinReminderDelay_stampsChecked_noFreeze() {
        Escrow escrow = buildPending();
        // Funded 2h ago â€” under the 24h reminder delay.
        escrow.setFundedAt(OffsetDateTime.now(fixed).minusHours(2));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
        when(worldApi.fetchParcelPage(PARCEL_UUID))
                .thenReturn(
                Mono.just(new ParcelPageData(meta(SELLER_AVATAR, "agent"), java.util.UUID.randomUUID())));
        stubWinner();

        task.checkOne(ESCROW_ID);

        verify(escrowService).stampChecked(escrow, OffsetDateTime.now(fixed));
        verify(escrowService, never()).confirmTransfer(any(), any());
        verify(escrowService, never()).freezeForFraud(any(), any(), any(), any());
    }

    @Test
    void sellerStillOwns_pastReminderDelay_stampsChecked_logsReminder_noFreeze() {
        Escrow escrow = buildPending();
        // Funded 30h ago â€” well past the 24h reminder delay.
        escrow.setFundedAt(OffsetDateTime.now(fixed).minusHours(30));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
        when(worldApi.fetchParcelPage(PARCEL_UUID))
                .thenReturn(
                Mono.just(new ParcelPageData(meta(SELLER_AVATAR, "agent"), java.util.UUID.randomUUID())));
        stubWinner();

        task.checkOne(ESCROW_ID);

        // Log assertion skipped intentionally (per plan) â€” the important
        // state-machine side effects are stampChecked + no freeze.
        verify(escrowService).stampChecked(escrow, OffsetDateTime.now(fixed));
        verify(escrowService, never()).confirmTransfer(any(), any());
        verify(escrowService, never()).freezeForFraud(any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void unknownOwner_delegatesToFreezeForFraud_withEvidence() {
        Escrow escrow = buildPending();
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
        ParcelMetadata stranger = meta(STRANGER_AVATAR, "agent");
        when(worldApi.fetchParcelPage(PARCEL_UUID)).thenReturn(
                Mono.just(new ParcelPageData(stranger, java.util.UUID.randomUUID())));
        stubWinner();

        task.checkOne(ESCROW_ID);

        ArgumentCaptor<Map<String, Object>> ev = ArgumentCaptor.forClass(Map.class);
        OffsetDateTime now = OffsetDateTime.now(fixed);
        verify(escrowService).freezeForFraud(
                eq(escrow), eq(FreezeReason.UNKNOWN_OWNER), ev.capture(), eq(now));
        Map<String, Object> evidence = ev.getValue();
        assertThat(evidence)
                .containsEntry("observedOwnerUuid", STRANGER_AVATAR.toString())
                .containsEntry("expectedWinnerUuid", WINNER_AVATAR.toString())
                .containsEntry("expectedSellerUuid", SELLER_AVATAR.toString())
                .containsEntry("observedOwnerType", "agent");
        verify(escrowService, never()).confirmTransfer(any(), any());
        verify(escrowService, never()).stampChecked(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void parcelNotFound_delegatesToFreezeForFraud_withParcelDeleted() {
        Escrow escrow = buildPending();
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
        when(worldApi.fetchParcelPage(PARCEL_UUID))
                .thenReturn(Mono.error(new ParcelNotFoundInSlException(PARCEL_UUID)));

        task.checkOne(ESCROW_ID);

        ArgumentCaptor<Map<String, Object>> ev = ArgumentCaptor.forClass(Map.class);
        OffsetDateTime now = OffsetDateTime.now(fixed);
        verify(escrowService).freezeForFraud(
                eq(escrow), eq(FreezeReason.PARCEL_DELETED), ev.capture(), eq(now));
        assertThat(ev.getValue())
                .containsEntry("parcelUuid", PARCEL_UUID.toString());
        verify(escrowService, never()).confirmTransfer(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void timeoutAtThreshold_delegatesToFreezeForFraud_withWorldApiPersistentFailure() {
        Escrow escrow = buildPending();
        // Counter one short of threshold â€” the about-to-happen failure pushes it over.
        escrow.setConsecutiveWorldApiFailures(FAILURE_THRESHOLD - 1);
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
        when(worldApi.fetchParcelPage(PARCEL_UUID))
                .thenReturn(Mono.error(new ExternalApiTimeoutException("World", "connect timeout")));

        task.checkOne(ESCROW_ID);

        ArgumentCaptor<Map<String, Object>> ev = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Escrow> frozen = ArgumentCaptor.forClass(Escrow.class);
        OffsetDateTime now = OffsetDateTime.now(fixed);
        verify(escrowService).freezeForFraud(
                frozen.capture(), eq(FreezeReason.WORLD_API_PERSISTENT_FAILURE), ev.capture(), eq(now));
        assertThat(ev.getValue())
                .containsEntry("consecutiveFailures", FAILURE_THRESHOLD)
                .containsEntry("threshold", FAILURE_THRESHOLD);
        // The counter must be stamped on the entity before freezeForFraud
        // saves it, so the persisted frozen row matches the evidence JSON â€”
        // otherwise the row shows (threshold - 1) while the evidence says
        // (threshold), which misleads incident review. See I1 fixup.
        assertThat(frozen.getValue().getConsecutiveWorldApiFailures())
                .isEqualTo(FAILURE_THRESHOLD);
        verify(escrowService, never()).incrementWorldApiFailure(any(), any());
    }

    @Test
    void timeoutBelowThreshold_incrementsCounter_doesNotFreeze() {
        Escrow escrow = buildPending();
        escrow.setConsecutiveWorldApiFailures(1);
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
        when(worldApi.fetchParcelPage(PARCEL_UUID))
                .thenReturn(Mono.error(new ExternalApiTimeoutException("World", "connect timeout")));

        task.checkOne(ESCROW_ID);

        verify(escrowService).incrementWorldApiFailure(escrow, OffsetDateTime.now(fixed));
        verify(escrowService, never()).freezeForFraud(any(), any(), any(), any());
        verify(escrowService, never()).confirmTransfer(any(), any());
    }

    @Test
    void nonTransferPending_shortCircuits_noWorldApiCall_noDelegation() {
        Escrow escrow = buildPending();
        escrow.setState(EscrowState.FUNDED);
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));

        task.checkOne(ESCROW_ID);

        verifyNoInteractions(worldApi);
        verifyNoInteractions(escrowService);
    }

    @Test
    void missingEscrow_shortCircuits_noWorldApiCall_noDelegation() {
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.empty());

        task.checkOne(ESCROW_ID);

        verifyNoInteractions(worldApi);
        verifyNoInteractions(escrowService);
        verifyNoInteractions(userRepo);
    }

    @Test
    void groupStillOwns_case3_stampsChecked_noFreeze() {
        // Case 3: group listing. The parcel is owned by the registered SL
        // group (not the seller's avatar). Pre-transfer ownership should
        // match the group UUID and the escrow should stay TRANSFER_PENDING
        // rather than freezing on the first sweep.
        Escrow escrow = buildPendingGroup();
        escrow.setFundedAt(OffsetDateTime.now(fixed).minusHours(2));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
        when(worldApi.fetchParcelPage(PARCEL_UUID))
                .thenReturn(Mono.just(new ParcelPageData(
                        meta(GROUP_UUID, "group"), java.util.UUID.randomUUID())));
        RealtyGroupSlGroup reg = RealtyGroupSlGroup.builder()
                .id(REALTY_GROUP_SL_GROUP_ID)
                .slGroupUuid(GROUP_UUID)
                .build();
        when(slGroupRepo.findById(REALTY_GROUP_SL_GROUP_ID)).thenReturn(Optional.of(reg));
        stubWinner();

        task.checkOne(ESCROW_ID);

        verify(escrowService).stampChecked(escrow, OffsetDateTime.now(fixed));
        verify(escrowService, never()).confirmTransfer(any(), any());
        verify(escrowService, never()).freezeForFraud(any(), any(), any(), any());
    }

    @Test
    void groupListing_winnerNowOwns_delegatesToConfirmTransfer() {
        // Case 3: group listing where the parcel has already been deeded to
        // the winning avatar. Should complete the escrow regardless of the
        // group branch taking precedence in expected-owner resolution.
        Escrow escrow = buildPendingGroup();
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
        when(worldApi.fetchParcelPage(PARCEL_UUID))
                .thenReturn(Mono.just(new ParcelPageData(
                        meta(WINNER_AVATAR, "agent"), java.util.UUID.randomUUID())));
        stubWinner();

        task.checkOne(ESCROW_ID);

        verify(escrowService).confirmTransfer(escrow, OffsetDateTime.now(fixed));
        verify(escrowService, never()).freezeForFraud(any(), any(), any(), any());
        verify(escrowService, never()).stampChecked(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void groupListing_unknownThirdParty_freezesWithGroupEvidence() {
        // Case 3: group listing, but parcel reparented to a stranger.
        // Evidence carries the expected GROUP uuid (not the seller's avatar)
        // so incident review reflects what the check actually compared.
        Escrow escrow = buildPendingGroup();
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
        when(worldApi.fetchParcelPage(PARCEL_UUID))
                .thenReturn(Mono.just(new ParcelPageData(
                        meta(STRANGER_AVATAR, "agent"), java.util.UUID.randomUUID())));
        RealtyGroupSlGroup reg = RealtyGroupSlGroup.builder()
                .id(REALTY_GROUP_SL_GROUP_ID)
                .slGroupUuid(GROUP_UUID)
                .build();
        when(slGroupRepo.findById(REALTY_GROUP_SL_GROUP_ID)).thenReturn(Optional.of(reg));
        stubWinner();

        task.checkOne(ESCROW_ID);

        ArgumentCaptor<Map<String, Object>> ev = ArgumentCaptor.forClass(Map.class);
        verify(escrowService).freezeForFraud(
                eq(escrow), eq(FreezeReason.UNKNOWN_OWNER), ev.capture(),
                eq(OffsetDateTime.now(fixed)));
        assertThat(ev.getValue())
                .containsEntry("observedOwnerUuid", STRANGER_AVATAR.toString())
                .containsEntry("expectedWinnerUuid", WINNER_AVATAR.toString())
                .containsEntry("expectedGroupUuid", GROUP_UUID.toString())
                .doesNotContainKey("expectedSellerUuid");
    }

    // -------------------------------------------------------------------------
    // Step-3 variable cadence (spec §6) — still-pending branch re-paces the
    // next owner poll via nextOwnerCheckAt. Inside the fast window
    // (now - sellToConfirmedAt < fastWindow) → +fastCadence; afterwards →
    // +slowCadence. Stamped on the entity before stampChecked's save().
    // -------------------------------------------------------------------------

    @Test
    void sellerStillOwns_withinFastWindow_setsNextOwnerCheckAt_fastCadence() {
        Escrow escrow = buildPending();
        OffsetDateTime now = OffsetDateTime.now(fixed);
        // Sell-To confirmed 10 min ago — well inside the 1h fast window.
        escrow.setSellToConfirmedAt(now.minusMinutes(10));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
        when(worldApi.fetchParcelPage(PARCEL_UUID)).thenReturn(
                Mono.just(new ParcelPageData(meta(SELLER_AVATAR, "agent"), java.util.UUID.randomUUID())));
        when(props.buyParcelFastWindow()).thenReturn(Duration.ofHours(1));
        when(props.buyParcelFastCadence()).thenReturn(Duration.ofMinutes(5));
        stubWinner();

        task.checkOne(ESCROW_ID);

        verify(escrowService).stampChecked(escrow, now);
        assertThat(escrow.getNextOwnerCheckAt()).isEqualTo(now.plusMinutes(5));
        verify(escrowService, never()).confirmTransfer(any(), any());
        verify(escrowService, never()).freezeForFraud(any(), any(), any(), any());
    }

    @Test
    void sellerStillOwns_pastFastWindow_setsNextOwnerCheckAt_slowCadence() {
        Escrow escrow = buildPending();
        OffsetDateTime now = OffsetDateTime.now(fixed);
        // Sell-To confirmed 90 min ago — past the 1h fast window.
        escrow.setSellToConfirmedAt(now.minusMinutes(90));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
        when(worldApi.fetchParcelPage(PARCEL_UUID)).thenReturn(
                Mono.just(new ParcelPageData(meta(SELLER_AVATAR, "agent"), java.util.UUID.randomUUID())));
        when(props.buyParcelFastWindow()).thenReturn(Duration.ofHours(1));
        when(props.buyParcelSlowCadence()).thenReturn(Duration.ofMinutes(30));
        stubWinner();

        task.checkOne(ESCROW_ID);

        verify(escrowService).stampChecked(escrow, now);
        assertThat(escrow.getNextOwnerCheckAt()).isEqualTo(now.plusMinutes(30));
        verify(escrowService, never()).confirmTransfer(any(), any());
        verify(escrowService, never()).freezeForFraud(any(), any(), any(), any());
    }

    @Test
    void sellerStillOwns_nullSellToConfirmedAt_doesNotNpe_noCadenceStamp() {
        // Hard gate: the finder (findBuyPhaseEscrowIdsDue) never returns an
        // escrow with sellToConfirmedAt == null, so step-3 polling is inert
        // before Set-Sell-To is bot-confirmed. If one ever reached this code
        // path defensively, the cadence stamp must be skipped without NPE.
        Escrow escrow = buildPending();
        OffsetDateTime now = OffsetDateTime.now(fixed);
        escrow.setSellToConfirmedAt(null);
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
        when(worldApi.fetchParcelPage(PARCEL_UUID)).thenReturn(
                Mono.just(new ParcelPageData(meta(SELLER_AVATAR, "agent"), java.util.UUID.randomUUID())));
        stubWinner();

        task.checkOne(ESCROW_ID);

        verify(escrowService).stampChecked(escrow, now);
        assertThat(escrow.getNextOwnerCheckAt()).isNull();
    }

    @Test
    void winnerMatch_doesNotStampNextOwnerCheckAt() {
        // Cadence re-pacing is the still-pending branch only — a winner match
        // confirms transfer and must not touch the owner-poll cursor.
        Escrow escrow = buildPending();
        escrow.setSellToConfirmedAt(OffsetDateTime.now(fixed).minusMinutes(10));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
        when(worldApi.fetchParcelPage(PARCEL_UUID)).thenReturn(
                Mono.just(new ParcelPageData(meta(WINNER_AVATAR, "agent"), java.util.UUID.randomUUID())));
        stubWinner();

        task.checkOne(ESCROW_ID);

        verify(escrowService).confirmTransfer(escrow, OffsetDateTime.now(fixed));
        assertThat(escrow.getNextOwnerCheckAt()).isNull();
    }

    @Test
    void lockEntryPath_usesFindByIdForUpdate_notFindById() {
        Escrow escrow = buildPending();
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
        when(worldApi.fetchParcelPage(PARCEL_UUID))
                .thenReturn(
                Mono.just(new ParcelPageData(meta(WINNER_AVATAR, "agent"), java.util.UUID.randomUUID())));
        stubWinner();

        task.checkOne(ESCROW_ID);

        verify(escrowRepo).findByIdForUpdate(ESCROW_ID);
        verify(escrowRepo, never()).findById(ESCROW_ID);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void stubWinner() {
        User winner = User.builder()
                .id(WINNER_ID)
                .email("winner@example.com").username("winner")
                .slAvatarUuid(WINNER_AVATAR)
                .verified(true)
                .build();
        when(userRepo.findById(WINNER_ID)).thenReturn(Optional.of(winner));
    }

    private Escrow buildPending() {
        User seller = User.builder().id(SELLER_ID).email("seller@example.com").username("seller")
                .slAvatarUuid(SELLER_AVATAR).verified(true).build();
        Auction auction = Auction.builder()
                .title("Test listing")
                .id(AUCTION_ID).seller(seller).slParcelUuid(PARCEL_UUID)
                .status(AuctionStatus.TRANSFER_PENDING)

                .startingBid(1000L).durationHours(168)
                .snipeProtect(false).listingFeePaid(true)
                .currentBid(5000L).bidCount(2)
                .consecutiveWorldApiFailures(0)
                .commissionRate(new BigDecimal("0.05"))
                .tags(new HashSet<>())
                .finalBidAmount(5000L)
                .endOutcome(AuctionEndOutcome.SOLD)
                .winnerUserId(WINNER_ID)
                .build();
        auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(PARCEL_UUID)
                .ownerUuid(SELLER_AVATAR)
                .ownerType("agent")
                .parcelName("Test Parcel")
                .regionName("EscrowMonitorRegion")
                .regionMaturityRating("MODERATE")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
        return Escrow.builder()
                .id(ESCROW_ID)
                .auction(auction)
                .state(EscrowState.TRANSFER_PENDING)
                .finalBidAmount(5000L)
                .commissionAmt(250L)
                .payoutAmt(4750L)
                .transferDeadline(OffsetDateTime.now(fixed).plusHours(70))
                .fundedAt(OffsetDateTime.now(fixed).minusHours(2))
                .consecutiveWorldApiFailures(0)
                .build();
    }

    private Escrow buildPendingGroup() {
        Escrow base = buildPending();
        base.getAuction().setRealtyGroupSlGroupId(REALTY_GROUP_SL_GROUP_ID);
        return base;
    }

    private ParcelMetadata meta(UUID owner, String ownerType) {
        return new ParcelMetadata(
                PARCEL_UUID, owner, ownerType, null,
                "Test Parcel", "EscrowMonitorRegion",
                1024, "desc", "http://example.com/snap.jpg", "MODERATE",
                128.0, 64.0, 22.0);
    }
}
