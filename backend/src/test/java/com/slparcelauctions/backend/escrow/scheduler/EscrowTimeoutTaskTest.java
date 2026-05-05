package com.slparcelauctions.backend.escrow.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.escrow.command.TerminalCommandRepository;
import com.slparcelauctions.backend.user.User;

/**
 * Unit coverage for {@link EscrowTimeoutTask}. Exercises the per-escrow
 * pessimistic-lock re-validation that runs between the sweep's repo query
 * and the service-level expire* call: state must match the expected
 * ESCROW_PENDING / TRANSFER_PENDING gate, and the deadline must still be
 * past the now we carry from the sweep. Both guards exist to neutralise
 * races between the sweep's snapshot read and the per-escrow transaction
 * (e.g. a concurrent payment acceptance flipping ESCROW_PENDING to
 * TRANSFER_PENDING between the two calls). The payout-in-flight guard is
 * also re-checked inside the lock (Task 8 follow-up): the happy-path
 * expireTransfer case stubs {@code countActivePayoutCommands} to zero, and
 * {@link #expireTransfer_withActivePayoutRacingIn_skipsAndLogs()} covers
 * the race where a PAYOUT was queued between the sweep query and this lock.
 */
@ExtendWith(MockitoExtension.class)
class EscrowTimeoutTaskTest {

    private static final Long ESCROW_ID = 701L;
    private static final Long AUCTION_ID = 42L;
    private static final Long SELLER_ID = 7L;
    private static final Long WINNER_ID = 8L;
    private static final UUID SELLER_AVATAR =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID WINNER_AVATAR =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID PARCEL_UUID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock EscrowRepository escrowRepo;
    @Mock EscrowService escrowService;
    @Mock TerminalCommandRepository terminalCommandRepo;

    EscrowTimeoutTask task;
    Clock fixed;
    OffsetDateTime now;

    @BeforeEach
    void setUp() {
        fixed = Clock.fixed(Instant.parse("2026-04-22T12:00:00Z"), ZoneOffset.UTC);
        now = OffsetDateTime.now(fixed);
        task = new EscrowTimeoutTask(escrowRepo, escrowService, terminalCommandRepo);
    }

    // -------------------------------------------------------------------------
    // expirePayment
    // -------------------------------------------------------------------------

    @Test
    void expirePayment_pendingWithPastDeadline_delegatesToService() {
        Escrow escrow = buildEscrow(EscrowState.ESCROW_PENDING);
        escrow.setPaymentDeadline(now.minusHours(1));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));

        task.expirePayment(ESCROW_ID, now);

        verify(escrowService).expirePayment(escrow, now);
        verify(escrowService, never()).expireTransfer(any(), any());
    }

    @Test
    void expirePayment_wrongState_shortCircuits_noServiceCall() {
        Escrow escrow = buildEscrow(EscrowState.TRANSFER_PENDING);
        escrow.setPaymentDeadline(now.minusHours(1));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));

        task.expirePayment(ESCROW_ID, now);

        verifyNoInteractions(escrowService);
    }

    @Test
    void expirePayment_fundedState_shortCircuits_noServiceCall() {
        Escrow escrow = buildEscrow(EscrowState.FUNDED);
        escrow.setPaymentDeadline(now.minusHours(1));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));

        task.expirePayment(ESCROW_ID, now);

        verifyNoInteractions(escrowService);
    }

    @Test
    void expirePayment_missingEscrow_shortCircuits_noServiceCall() {
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.empty());

        task.expirePayment(ESCROW_ID, now);

        verifyNoInteractions(escrowService);
    }

    @Test
    void expirePayment_deadlineNotYetPast_shortCircuits_noServiceCall() {
        // Race window: sweep picked this escrow up but a concurrent
        // mutation already pushed paymentDeadline into the future (e.g. an
        // admin tool).  The per-task lock caught the state correctly but
        // the deadline re-check defuses the stale decision.
        Escrow escrow = buildEscrow(EscrowState.ESCROW_PENDING);
        escrow.setPaymentDeadline(now.plusHours(1));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));

        task.expirePayment(ESCROW_ID, now);

        verify(escrowService, never()).expirePayment(any(), any());
    }

    @Test
    void expirePayment_nullDeadline_shortCircuits_noServiceCall() {
        // Defensive: an ESCROW_PENDING row with a null paymentDeadline is
        // a data integrity bug but must not crash the sweep.
        Escrow escrow = buildEscrow(EscrowState.ESCROW_PENDING);
        escrow.setPaymentDeadline(null);
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));

        task.expirePayment(ESCROW_ID, now);

        verify(escrowService, never()).expirePayment(any(), any());
    }

    // -------------------------------------------------------------------------
    // expireTransfer
    // -------------------------------------------------------------------------

    @Test
    void expireTransfer_pendingWithPastDeadline_delegatesToService() {
        Escrow escrow = buildEscrow(EscrowState.TRANSFER_PENDING);
        escrow.setTransferDeadline(now.minusHours(1));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
        when(terminalCommandRepo.countActivePayoutCommands(ESCROW_ID)).thenReturn(0L);

        task.expireTransfer(ESCROW_ID, now);

        verify(escrowService).expireTransfer(escrow, now);
        verify(escrowService, never()).expirePayment(any(), any());
    }

    @Test
    void expireTransfer_withActivePayoutRacingIn_skipsAndLogs() {
        // Race window: findExpiredTransferPendingIds filtered out escrows
        // with active payouts at sweep time, but the ownership monitor
        // queued a PAYOUT between that query and this lock. Re-checking
        // under the lock is authoritative (the ownership confirm path
        // takes the same row lock we hold here), and must short-circuit
        // to prevent a REFUND racing the in-flight PAYOUT (double-spend).
        Escrow escrow = buildEscrow(EscrowState.TRANSFER_PENDING);
        escrow.setTransferDeadline(now.minusHours(1));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
        when(terminalCommandRepo.countActivePayoutCommands(ESCROW_ID)).thenReturn(1L);

        task.expireTransfer(ESCROW_ID, now);

        verify(escrowService, never()).expireTransfer(any(), any());
        verify(escrowService, never()).expirePayment(any(), any());
    }

    @Test
    void expireTransfer_wrongState_shortCircuits_noServiceCall() {
        Escrow escrow = buildEscrow(EscrowState.ESCROW_PENDING);
        escrow.setTransferDeadline(now.minusHours(1));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));

        task.expireTransfer(ESCROW_ID, now);

        verifyNoInteractions(escrowService);
    }

    @Test
    void expireTransfer_completedState_shortCircuits_noServiceCall() {
        Escrow escrow = buildEscrow(EscrowState.COMPLETED);
        escrow.setTransferDeadline(now.minusHours(1));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));

        task.expireTransfer(ESCROW_ID, now);

        verifyNoInteractions(escrowService);
    }

    @Test
    void expireTransfer_missingEscrow_shortCircuits_noServiceCall() {
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.empty());

        task.expireTransfer(ESCROW_ID, now);

        verifyNoInteractions(escrowService);
    }

    @Test
    void expireTransfer_deadlineNotYetPast_shortCircuits_noServiceCall() {
        Escrow escrow = buildEscrow(EscrowState.TRANSFER_PENDING);
        escrow.setTransferDeadline(now.plusHours(1));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));

        task.expireTransfer(ESCROW_ID, now);

        verify(escrowService, never()).expireTransfer(any(), any());
    }

    @Test
    void expireTransfer_nullDeadline_shortCircuits_noServiceCall() {
        Escrow escrow = buildEscrow(EscrowState.TRANSFER_PENDING);
        escrow.setTransferDeadline(null);
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));

        task.expireTransfer(ESCROW_ID, now);

        verify(escrowService, never()).expireTransfer(any(), any());
    }

    @Test
    void lockEntryPath_usesFindByIdForUpdate_notFindById() {
        Escrow escrow = buildEscrow(EscrowState.ESCROW_PENDING);
        escrow.setPaymentDeadline(now.minusHours(1));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));

        task.expirePayment(ESCROW_ID, now);

        verify(escrowRepo).findByIdForUpdate(ESCROW_ID);
        verify(escrowRepo, never()).findById(ESCROW_ID);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Escrow buildEscrow(EscrowState state) {
        User seller = User.builder().id(SELLER_ID).email("seller@example.com").username("seller")
                .slAvatarUuid(SELLER_AVATAR).verified(true).build();
        Auction auction = Auction.builder()
                .title("Test listing")
                .id(AUCTION_ID).seller(seller).slParcelUuid(PARCEL_UUID)
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
        auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(PARCEL_UUID)
                .ownerUuid(SELLER_AVATAR)
                .ownerType("agent")
                .parcelName("Test Parcel")
                .regionName("Coniston")
                .regionMaturityRating("GENERAL")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
        // Use a bare in-memory User stub for winner-side references if needed;
        // the task does not read it.
        User.builder().id(WINNER_ID).email("winner@example.com").username("winner")
                .slAvatarUuid(WINNER_AVATAR).verified(true).build();
        return Escrow.builder()
                .id(ESCROW_ID)
                .auction(auction)
                .state(state)
                .finalBidAmount(5000L)
                .commissionAmt(250L)
                .payoutAmt(4750L)
                .consecutiveWorldApiFailures(0)
                .build();
    }
}
