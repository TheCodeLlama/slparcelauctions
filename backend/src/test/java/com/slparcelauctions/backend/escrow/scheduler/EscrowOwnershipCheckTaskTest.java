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
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowService;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.escrow.FreezeReason;
import com.slparcelauctions.backend.escrow.terminal.EscrowConfigProperties;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
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
    private static final int FAILURE_THRESHOLD = 5;

    @Mock EscrowRepository escrowRepo;
    @Mock EscrowService escrowService;
    @Mock SlWorldApiClient worldApi;
    @Mock UserRepository userRepo;
    @Mock EscrowConfigProperties props;

    EscrowOwnershipCheckTask task;
    Clock fixed;

    @BeforeEach
    void setUp() {
        fixed = Clock.fixed(Instant.parse("2026-04-20T12:00:00Z"), ZoneOffset.UTC);
        task = new EscrowOwnershipCheckTask(escrowRepo, escrowService, worldApi, userRepo, props, fixed);
        lenient().when(props.ownershipApiFailureThreshold()).thenReturn(FAILURE_THRESHOLD);
        lenient().when(props.ownershipReminderDelay()).thenReturn(Duration.ofHours(24));
    }

    @Test
    void winnerMatch_delegatesToConfirmTransfer_noOtherSideEffects() {
        Escrow escrow = buildPending();
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
        when(worldApi.fetchParcel(PARCEL_UUID))
                .thenReturn(Mono.just(meta(WINNER_AVATAR, "agent")));
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
        // Funded 2h ago — under the 24h reminder delay.
        escrow.setFundedAt(OffsetDateTime.now(fixed).minusHours(2));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
        when(worldApi.fetchParcel(PARCEL_UUID))
                .thenReturn(Mono.just(meta(SELLER_AVATAR, "agent")));
        stubWinner();

        task.checkOne(ESCROW_ID);

        verify(escrowService).stampChecked(escrow, OffsetDateTime.now(fixed));
        verify(escrowService, never()).confirmTransfer(any(), any());
        verify(escrowService, never()).freezeForFraud(any(), any(), any(), any());
    }

    @Test
    void sellerStillOwns_pastReminderDelay_stampsChecked_logsReminder_noFreeze() {
        Escrow escrow = buildPending();
        // Funded 30h ago — well past the 24h reminder delay.
        escrow.setFundedAt(OffsetDateTime.now(fixed).minusHours(30));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
        when(worldApi.fetchParcel(PARCEL_UUID))
                .thenReturn(Mono.just(meta(SELLER_AVATAR, "agent")));
        stubWinner();

        task.checkOne(ESCROW_ID);

        // Log assertion skipped intentionally (per plan) — the important
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
        when(worldApi.fetchParcel(PARCEL_UUID)).thenReturn(Mono.just(stranger));
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
        when(worldApi.fetchParcel(PARCEL_UUID))
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
        // Counter one short of threshold — the about-to-happen failure pushes it over.
        escrow.setConsecutiveWorldApiFailures(FAILURE_THRESHOLD - 1);
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
        when(worldApi.fetchParcel(PARCEL_UUID))
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
        // saves it, so the persisted frozen row matches the evidence JSON —
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
        when(worldApi.fetchParcel(PARCEL_UUID))
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
    void lockEntryPath_usesFindByIdForUpdate_notFindById() {
        Escrow escrow = buildPending();
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
        when(worldApi.fetchParcel(PARCEL_UUID))
                .thenReturn(Mono.just(meta(WINNER_AVATAR, "agent")));
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
                .email("winner@example.com")
                .slAvatarUuid(WINNER_AVATAR)
                .verified(true)
                .build();
        when(userRepo.findById(WINNER_ID)).thenReturn(Optional.of(winner));
    }

    private Escrow buildPending() {
        User seller = User.builder().id(SELLER_ID).email("seller@example.com")
                .slAvatarUuid(SELLER_AVATAR).verified(true).build();
        Parcel parcel = Parcel.builder().id(99L).slParcelUuid(PARCEL_UUID)
                .ownerUuid(SELLER_AVATAR).ownerType("agent")
                .regionName("EscrowMonitorRegion").continentName("Sansara")
                .verified(true).build();
        Auction auction = Auction.builder()
                .id(AUCTION_ID).seller(seller).parcel(parcel)
                .status(AuctionStatus.ENDED)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .startingBid(1000L).durationHours(168)
                .snipeProtect(false).listingFeePaid(true)
                .currentBid(5000L).bidCount(2)
                .consecutiveWorldApiFailures(0)
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .tags(new HashSet<>())
                .finalBidAmount(5000L)
                .endOutcome(AuctionEndOutcome.SOLD)
                .winnerUserId(WINNER_ID)
                .build();
        return Escrow.builder()
                .id(ESCROW_ID)
                .auction(auction)
                .state(EscrowState.TRANSFER_PENDING)
                .finalBidAmount(5000L)
                .commissionAmt(250L)
                .payoutAmt(4750L)
                .paymentDeadline(OffsetDateTime.now(fixed).minusHours(1))
                .transferDeadline(OffsetDateTime.now(fixed).plusHours(70))
                .fundedAt(OffsetDateTime.now(fixed).minusHours(2))
                .consecutiveWorldApiFailures(0)
                .build();
    }

    private ParcelMetadata meta(UUID owner, String ownerType) {
        return new ParcelMetadata(
                PARCEL_UUID, owner, ownerType,
                "Test Parcel", "EscrowMonitorRegion",
                1024, "desc", "http://example.com/snap.jpg", "MATURE",
                128.0, 64.0, 22.0);
    }
}
