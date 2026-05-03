package com.slparcelauctions.backend.escrow.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.escrow.EscrowTransaction;
import com.slparcelauctions.backend.escrow.EscrowTransactionRepository;
import com.slparcelauctions.backend.escrow.EscrowTransactionStatus;
import com.slparcelauctions.backend.escrow.EscrowTransactionType;
import com.slparcelauctions.backend.escrow.broadcast.EscrowBroadcastPublisher;
import com.slparcelauctions.backend.escrow.scheduler.TerminalCommandDispatcherTask;
import com.slparcelauctions.backend.escrow.terminal.EscrowConfigProperties;
import com.slparcelauctions.backend.escrow.terminal.Terminal;
import com.slparcelauctions.backend.escrow.terminal.TerminalRepository;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.user.User;

/**
 * Mockito unit coverage for {@link TerminalCommandDispatcherTask}. Exercises
 * the five decision branches: no live terminal, happy POST (ACK), transport
 * failure with backoff, transport failure at the retry cap (stall), and
 * IN_FLIGHT staleness recovery.
 */
@ExtendWith(MockitoExtension.class)
class TerminalCommandDispatcherTaskTest {

    private static final Long CMD_ID = 701L;
    private static final Long ESCROW_ID = 42L;
    private static final String TERMINAL_ID = "terminal-dispatch-unit";
    private static final String HTTP_IN_URL = "https://sim.agni.lindenlab.com:12043/cap/abc";
    private static final String SHARED_SECRET = "dev-escrow-secret-do-not-use-in-prod";
    private static final Duration LIVE_WINDOW = Duration.ofMinutes(15);
    private static final Duration IN_FLIGHT_TIMEOUT = Duration.ofMinutes(5);

    @Mock TerminalCommandRepository cmdRepo;
    @Mock TerminalRepository terminalRepo;
    @Mock EscrowRepository escrowRepo;
    @Mock EscrowTransactionRepository ledgerRepo;
    @Mock TerminalHttpClient terminalHttp;
    @Mock EscrowBroadcastPublisher broadcastPublisher;
    @Mock EscrowConfigProperties props;
    @Mock com.slparcelauctions.backend.wallet.WalletWithdrawalCallbackHandler walletWithdrawalCallbackHandler;

    TerminalCommandDispatcherTask task;
    Clock fixed;

    @BeforeEach
    void setUp() {
        fixed = Clock.fixed(Instant.parse("2026-04-21T14:00:00Z"), ZoneOffset.UTC);
        task = new TerminalCommandDispatcherTask(
                cmdRepo, terminalRepo, escrowRepo, ledgerRepo, terminalHttp,
                broadcastPublisher, props, walletWithdrawalCallbackHandler, fixed);
        lenient().when(props.terminalLiveWindow()).thenReturn(LIVE_WINDOW);
        lenient().when(props.commandInFlightTimeout()).thenReturn(IN_FLIGHT_TIMEOUT);
        lenient().when(props.terminalSharedSecret()).thenReturn(SHARED_SECRET);
    }

    @Test
    void noLiveTerminal_defersOneMinute_noHttpCall() {
        TerminalCommand cmd = buildQueued();
        when(cmdRepo.findByIdForUpdate(CMD_ID)).thenReturn(Optional.of(cmd));
        when(terminalRepo.findAnyLive(any())).thenReturn(Optional.empty());
        when(cmdRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        task.dispatchOne(CMD_ID);

        // Status unchanged (still QUEUED), nextAttemptAt pushed 1m, no HTTP call.
        OffsetDateTime now = OffsetDateTime.now(fixed);
        assertThat(cmd.getStatus()).isEqualTo(TerminalCommandStatus.QUEUED);
        assertThat(cmd.getNextAttemptAt()).isEqualTo(now.plusMinutes(1));
        assertThat(cmd.getAttemptCount()).isZero();
        assertThat(cmd.getDispatchedAt()).isNull();
        verifyNoInteractions(terminalHttp);
    }

    @Test
    void happyPost_flipsToInFlight_stampsDispatchedAt_bumpsAttemptCount() {
        TerminalCommand cmd = buildQueued();
        Terminal terminal = buildTerminal();
        when(cmdRepo.findByIdForUpdate(CMD_ID)).thenReturn(Optional.of(cmd));
        when(terminalRepo.findAnyLive(any())).thenReturn(Optional.of(terminal));
        when(cmdRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(terminalHttp.post(anyString(), any()))
                .thenReturn(TerminalHttpClient.TerminalHttpResult.ok());

        task.dispatchOne(CMD_ID);

        OffsetDateTime now = OffsetDateTime.now(fixed);
        assertThat(cmd.getStatus()).isEqualTo(TerminalCommandStatus.IN_FLIGHT);
        assertThat(cmd.getAttemptCount()).isEqualTo(1);
        assertThat(cmd.getDispatchedAt()).isEqualTo(now);
        assertThat(cmd.getTerminalId()).isEqualTo(TERMINAL_ID);
        // Body is posted with the configured shared secret echoed in.
        ArgumentCaptor<TerminalCommandBody> body =
                ArgumentCaptor.forClass(TerminalCommandBody.class);
        verify(terminalHttp).post(anyString(), body.capture());
        assertThat(body.getValue().action()).isEqualTo("PAYOUT");
        assertThat(body.getValue().purpose()).isEqualTo("AUCTION_ESCROW");
        assertThat(body.getValue().idempotencyKey()).isEqualTo("ESC-42-PAYOUT-1");
        assertThat(body.getValue().sharedSecret()).isEqualTo(SHARED_SECRET);
    }

    @Test
    void httpFailureBelowCap_flipsToFailed_withOneMinuteBackoff() {
        TerminalCommand cmd = buildQueued();
        Terminal terminal = buildTerminal();
        when(cmdRepo.findByIdForUpdate(CMD_ID)).thenReturn(Optional.of(cmd));
        when(terminalRepo.findAnyLive(any())).thenReturn(Optional.of(terminal));
        when(cmdRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(terminalHttp.post(anyString(), any()))
                .thenReturn(TerminalHttpClient.TerminalHttpResult.fail("connect timeout"));

        task.dispatchOne(CMD_ID);

        OffsetDateTime now = OffsetDateTime.now(fixed);
        assertThat(cmd.getStatus()).isEqualTo(TerminalCommandStatus.FAILED);
        assertThat(cmd.getAttemptCount()).isEqualTo(1);
        assertThat(cmd.getLastError()).isEqualTo("connect timeout");
        assertThat(cmd.getRequiresManualReview()).isFalse();
        assertThat(cmd.getNextAttemptAt()).isEqualTo(now.plusMinutes(1));
        verifyNoInteractions(broadcastPublisher);
    }

    @Test
    void httpFailureAtCap_stallsAndBroadcasts() {
        // attemptCount = 3 going in; after the POST it becomes 4 = MAX_ATTEMPTS.
        TerminalCommand cmd = buildQueued();
        cmd.setStatus(TerminalCommandStatus.FAILED);
        cmd.setAttemptCount(3);
        cmd.setRequiresManualReview(false);

        Terminal terminal = buildTerminal();
        Escrow escrow = buildEscrow();
        when(cmdRepo.findByIdForUpdate(CMD_ID)).thenReturn(Optional.of(cmd));
        when(terminalRepo.findAnyLive(any())).thenReturn(Optional.of(terminal));
        when(cmdRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(terminalHttp.post(anyString(), any()))
                .thenReturn(TerminalHttpClient.TerminalHttpResult.fail("5xx from terminal"));
        when(escrowRepo.findById(ESCROW_ID)).thenReturn(Optional.of(escrow));

        task.dispatchOne(CMD_ID);

        assertThat(cmd.getStatus()).isEqualTo(TerminalCommandStatus.FAILED);
        assertThat(cmd.getAttemptCount()).isEqualTo(4);
        assertThat(cmd.getRequiresManualReview()).isTrue();
        assertThat(cmd.getLastError()).isEqualTo("5xx from terminal");
        // Envelope publication is registered afterCommit — the mock publisher is
        // called only if the transaction commits. We can at least assert the
        // escrow lookup happened (so the envelope factory has its inputs).
        verify(escrowRepo).findById(ESCROW_ID);
    }

    @Test
    void httpFailureAtCap_writesFailedLedgerRow() {
        // Spec follow-up: transport-failure stalls now write a FAILED
        // EscrowTransaction row mirroring the terminal-reported-failure path
        // in TerminalCommandService.applyCallback. The dispute timeline can
        // surface both shapes uniformly.
        TerminalCommand cmd = buildQueued();
        cmd.setStatus(TerminalCommandStatus.FAILED);
        cmd.setAttemptCount(3);
        cmd.setRequiresManualReview(false);

        Terminal terminal = buildTerminal();
        Escrow escrow = buildEscrow();
        when(cmdRepo.findByIdForUpdate(CMD_ID)).thenReturn(Optional.of(cmd));
        when(terminalRepo.findAnyLive(any())).thenReturn(Optional.of(terminal));
        when(cmdRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(terminalHttp.post(anyString(), any()))
                .thenReturn(TerminalHttpClient.TerminalHttpResult.fail("connection refused"));
        when(escrowRepo.findById(ESCROW_ID)).thenReturn(Optional.of(escrow));

        task.dispatchOne(CMD_ID);

        ArgumentCaptor<EscrowTransaction> ledgerCaptor =
                ArgumentCaptor.forClass(EscrowTransaction.class);
        verify(ledgerRepo).save(ledgerCaptor.capture());
        EscrowTransaction row = ledgerCaptor.getValue();
        assertThat(row.getStatus()).isEqualTo(EscrowTransactionStatus.FAILED);
        assertThat(row.getType()).isEqualTo(EscrowTransactionType.AUCTION_ESCROW_PAYOUT);
        assertThat(row.getAmount()).isEqualTo(cmd.getAmount());
        assertThat(row.getEscrow()).isEqualTo(escrow);
        assertThat(row.getAuction()).isEqualTo(escrow.getAuction());
        assertThat(row.getErrorMessage()).isEqualTo("connection refused");
        assertThat(row.getTerminalId()).isEqualTo(TERMINAL_ID);
    }

    @Test
    void httpFailureBelowCap_doesNotWriteFailedLedgerRow() {
        // Below-cap retries leave the row in FAILED with backoff but do NOT
        // write a per-attempt ledger row — that would balloon the ledger for
        // every transient hiccup. Only the cap-exhaust transition writes one.
        TerminalCommand cmd = buildQueued();
        Terminal terminal = buildTerminal();
        when(cmdRepo.findByIdForUpdate(CMD_ID)).thenReturn(Optional.of(cmd));
        when(terminalRepo.findAnyLive(any())).thenReturn(Optional.of(terminal));
        when(cmdRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(terminalHttp.post(anyString(), any()))
                .thenReturn(TerminalHttpClient.TerminalHttpResult.fail("connect timeout"));

        task.dispatchOne(CMD_ID);

        verifyNoInteractions(ledgerRepo);
    }

    @Test
    void staleInFlight_markStaleAndRequeue_flipsToFailed_nextAttemptNow() {
        TerminalCommand cmd = buildQueued();
        cmd.setStatus(TerminalCommandStatus.IN_FLIGHT);
        cmd.setDispatchedAt(OffsetDateTime.now(fixed).minusMinutes(10));
        cmd.setAttemptCount(1);
        when(cmdRepo.findByIdForUpdate(CMD_ID)).thenReturn(Optional.of(cmd));
        when(cmdRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        task.markStaleAndRequeue(CMD_ID);

        OffsetDateTime now = OffsetDateTime.now(fixed);
        assertThat(cmd.getStatus()).isEqualTo(TerminalCommandStatus.FAILED);
        assertThat(cmd.getLastError()).isEqualTo("IN_FLIGHT timeout without callback");
        assertThat(cmd.getNextAttemptAt()).isEqualTo(now);
        // attempt_count NOT incremented by the stale path (that was the
        // previous dispatch's bump).
        assertThat(cmd.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void markStaleAndRequeue_ignoresNonInFlight() {
        TerminalCommand cmd = buildQueued();
        cmd.setStatus(TerminalCommandStatus.COMPLETED);
        when(cmdRepo.findByIdForUpdate(CMD_ID)).thenReturn(Optional.of(cmd));

        task.markStaleAndRequeue(CMD_ID);

        // No save should happen for a non-IN_FLIGHT row.
        verify(cmdRepo, never()).save(any());
    }

    @Test
    void markStaleAndRequeue_atAttemptCap_stallsAndDoesNotRequeue() {
        // Reproduces the production bug: an IN_FLIGHT command with
        // attemptCount = MAX_ATTEMPTS used to keep looping via this method
        // because the cap check only existed on the transport-failure path.
        TerminalCommand cmd = buildQueued();
        cmd.setStatus(TerminalCommandStatus.IN_FLIGHT);
        cmd.setDispatchedAt(OffsetDateTime.now(fixed).minusMinutes(10));
        cmd.setAttemptCount(com.slparcelauctions.backend.escrow.command.EscrowRetryPolicy.MAX_ATTEMPTS);
        when(cmdRepo.findByIdForUpdate(CMD_ID)).thenReturn(Optional.of(cmd));
        when(cmdRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        task.markStaleAndRequeue(CMD_ID);

        assertThat(cmd.getStatus()).isEqualTo(TerminalCommandStatus.FAILED);
        assertThat(cmd.getRequiresManualReview()).isTrue();
        // Stalled — must NOT schedule another retry.
        assertThat(cmd.getNextAttemptAt())
                .isEqualTo(OffsetDateTime.now(fixed).minusSeconds(1));
    }

    @Test
    void markStaleAndRequeue_atAttemptCap_walletWithdrawalRefundsUser() {
        TerminalCommand cmd = TerminalCommand.builder()
                .id(CMD_ID)
                .action(TerminalCommandAction.WITHDRAW)
                .purpose(com.slparcelauctions.backend.escrow.command.TerminalCommandPurpose.WALLET_WITHDRAWAL)
                .recipientUuid(UUID.randomUUID().toString())
                .amount(100L)
                .status(TerminalCommandStatus.IN_FLIGHT)
                .attemptCount(com.slparcelauctions.backend.escrow.command.EscrowRetryPolicy.MAX_ATTEMPTS)
                .dispatchedAt(OffsetDateTime.now(fixed).minusMinutes(10))
                .idempotencyKey("WAL-99")
                .requiresManualReview(false)
                .build();
        when(cmdRepo.findByIdForUpdate(CMD_ID)).thenReturn(Optional.of(cmd));
        when(cmdRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        task.markStaleAndRequeue(CMD_ID);

        // Reverses the wallet debit + IMs the user.
        verify(walletWithdrawalCallbackHandler)
                .onStall(eq(cmd), eq("IN_FLIGHT timeout without callback"));
        assertThat(cmd.getStatus()).isEqualTo(TerminalCommandStatus.FAILED);
        assertThat(cmd.getRequiresManualReview()).isTrue();
    }

    @Test
    void manualReviewCommand_shortCircuits_noHttpCall() {
        TerminalCommand cmd = buildQueued();
        cmd.setStatus(TerminalCommandStatus.FAILED);
        cmd.setRequiresManualReview(true);
        when(cmdRepo.findByIdForUpdate(CMD_ID)).thenReturn(Optional.of(cmd));

        task.dispatchOne(CMD_ID);

        verifyNoInteractions(terminalHttp, terminalRepo);
        verify(cmdRepo, never()).save(any());
    }

    @Test
    void nextAttemptInFuture_shortCircuits_noHttpCall() {
        TerminalCommand cmd = buildQueued();
        cmd.setNextAttemptAt(OffsetDateTime.now(fixed).plusMinutes(5));
        when(cmdRepo.findByIdForUpdate(CMD_ID)).thenReturn(Optional.of(cmd));

        task.dispatchOne(CMD_ID);

        verifyNoInteractions(terminalHttp, terminalRepo);
        verify(cmdRepo, never()).save(any());
    }

    @Test
    void missingCommand_shortCircuits_noEffects() {
        when(cmdRepo.findByIdForUpdate(CMD_ID)).thenReturn(Optional.empty());

        task.dispatchOne(CMD_ID);

        verifyNoInteractions(terminalHttp, terminalRepo, broadcastPublisher);
        verify(cmdRepo, times(1)).findByIdForUpdate(CMD_ID);
        verify(cmdRepo, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private TerminalCommand buildQueued() {
        return TerminalCommand.builder()
                .id(CMD_ID)
                .escrowId(ESCROW_ID)
                .action(TerminalCommandAction.PAYOUT)
                .purpose(TerminalCommandPurpose.AUCTION_ESCROW)
                .recipientUuid(UUID.randomUUID().toString())
                .amount(4750L)
                .status(TerminalCommandStatus.QUEUED)
                .attemptCount(0)
                .nextAttemptAt(OffsetDateTime.now(fixed).minusSeconds(1))
                .idempotencyKey("ESC-42-PAYOUT-1")
                .requiresManualReview(false)
                .build();
    }

    private Terminal buildTerminal() {
        return Terminal.builder()
                .terminalId(TERMINAL_ID)
                .httpInUrl(HTTP_IN_URL)
                .regionName("DispatchRegion")
                .active(true)
                .lastSeenAt(OffsetDateTime.now(fixed))
                .build();
    }

    private Escrow buildEscrow() {
        User seller = User.builder().id(1L).email("s@e.com")
                .slAvatarUuid(UUID.randomUUID()).verified(true).build();
        UUID parcelUuid = UUID.randomUUID();
        Auction auction = Auction.builder()
                .title("Test listing")
                .id(1001L).seller(seller).slParcelUuid(parcelUuid)
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
                .winnerUserId(2L)
                .build();
        auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid)
                .ownerUuid(seller.getSlAvatarUuid())
                .ownerType("agent")
                .parcelName("Test Parcel")
                .regionName("Coniston")
                .regionMaturityRating("GENERAL")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
        return Escrow.builder()
                .id(ESCROW_ID).auction(auction)
                .state(EscrowState.TRANSFER_PENDING)
                .finalBidAmount(5000L).commissionAmt(250L).payoutAmt(4750L)
                .paymentDeadline(OffsetDateTime.now(fixed).minusHours(1))
                .transferDeadline(OffsetDateTime.now(fixed).plusHours(70))
                .fundedAt(OffsetDateTime.now(fixed).minusHours(2))
                .consecutiveWorldApiFailures(0)
                .build();
    }
}
