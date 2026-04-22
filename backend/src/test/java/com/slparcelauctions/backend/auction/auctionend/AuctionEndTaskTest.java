package com.slparcelauctions.backend.auction.auctionend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.ProxyBidRepository;
import com.slparcelauctions.backend.auction.broadcast.AuctionBroadcastPublisher;
import com.slparcelauctions.backend.auction.broadcast.AuctionEndedEnvelope;
import com.slparcelauctions.backend.escrow.EscrowService;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Mockito coverage for {@link AuctionEndTask#closeOne}. Drives the three-gate
 * re-check, the {@link AuctionEndOutcome} classification matrix, the proxy-
 * exhaust call, and the afterCommit envelope publish.
 *
 * <p>Tests manually bind + unbind Spring's
 * {@link TransactionSynchronizationManager} because the task registers a
 * {@link org.springframework.transaction.support.TransactionSynchronization}
 * inside its {@code @Transactional} boundary. Without an active
 * synchronization context, {@link TransactionSynchronizationManager#registerSynchronization}
 * throws. We do NOT invoke {@code triggerAfterCommit()} — the unit tests
 * assert the envelope values and the publish-call wiring separately via a
 * direct publisher verification in a companion test that manually fires
 * {@code afterCommit}.
 */
@ExtendWith(MockitoExtension.class)
class AuctionEndTaskTest {

    @Mock AuctionRepository auctionRepo;
    @Mock ProxyBidRepository proxyBidRepo;
    @Mock UserRepository userRepo;
    @Mock AuctionBroadcastPublisher publisher;
    @Mock EscrowService escrowService;

    Clock fixed;
    AuctionEndTask task;

    @BeforeEach
    void setUp() {
        fixed = Clock.fixed(Instant.parse("2026-04-20T12:00:00Z"), ZoneOffset.UTC);
        task = new AuctionEndTask(auctionRepo, proxyBidRepo, userRepo, publisher, escrowService, fixed);
        // Manually init synchronization so registerSynchronization inside
        // closeOne does not blow up (the @Transactional proxy normally
        // handles this but the unit test invokes closeOne directly).
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // -------------------------------------------------------------------------
    // Outcome classification
    // -------------------------------------------------------------------------

    @Test
    void sold_whenBidAboveReserve_setsWinnerAndFinalBid_publishesEnvelopeWithDisplayName() {
        User winner = User.builder().id(7L).displayName("Top Bidder").build();
        Auction auction = Auction.builder()
                .id(100L)
                .status(AuctionStatus.ACTIVE)
                .endsAt(OffsetDateTime.now(fixed).minusSeconds(1))
                .startingBid(500L)
                .currentBid(1500L)
                .currentBidderId(7L)
                .reservePrice(1000L)
                .bidCount(3)
                .build();
        when(auctionRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(auction));
        when(userRepo.findById(7L)).thenReturn(Optional.of(winner));

        task.closeOne(100L);

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.ENDED);
        assertThat(auction.getEndOutcome()).isEqualTo(AuctionEndOutcome.SOLD);
        assertThat(auction.getWinnerUserId()).isEqualTo(7L);
        assertThat(auction.getFinalBidAmount()).isEqualTo(1500L);
        assertThat(auction.getEndedAt()).isEqualTo(OffsetDateTime.now(fixed));
        verify(auctionRepo).save(auction);
        verify(proxyBidRepo).exhaustAllActiveByAuctionId(100L);
        // Escrow row creation is delegated — the SOLD branch must call the
        // service with the same `now` used for auction.endedAt so the 48h
        // payment deadline anchors to the same instant.
        verify(escrowService).createForEndedAuction(auction, OffsetDateTime.now(fixed));

        // Fire the synchronization manually — the unit test's fake tx is
        // not actually committing, so we trigger afterCommit ourselves to
        // exercise the publish path and assert the envelope contents.
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCommit());

        ArgumentCaptor<AuctionEndedEnvelope> cap =
                ArgumentCaptor.forClass(AuctionEndedEnvelope.class);
        verify(publisher).publishEnded(cap.capture());
        AuctionEndedEnvelope env = cap.getValue();
        assertThat(env.auctionId()).isEqualTo(100L);
        assertThat(env.endOutcome()).isEqualTo(AuctionEndOutcome.SOLD);
        assertThat(env.finalBid()).isEqualTo(1500L);
        assertThat(env.winnerUserId()).isEqualTo(7L);
        assertThat(env.winnerDisplayName()).isEqualTo("Top Bidder");
        assertThat(env.bidCount()).isEqualTo(3);
        // Scheduler path must stamp envelope.serverTime from the same
        // OffsetDateTime it persisted to auction.endedAt — otherwise two
        // separate OffsetDateTime.now(clock) calls can drift microseconds
        // under Clock.systemUTC() and break client-side event ordering.
        assertThat(env.serverTime()).isEqualTo(auction.getEndedAt());
    }

    @Test
    void reserveNotMet_whenBidBelowReserve_leavesWinnerAndFinalBidNull() {
        Auction auction = Auction.builder()
                .id(101L)
                .status(AuctionStatus.ACTIVE)
                .endsAt(OffsetDateTime.now(fixed).minusSeconds(1))
                .startingBid(500L)
                .currentBid(750L)
                .currentBidderId(8L)
                .reservePrice(1000L)
                .bidCount(1)
                .build();
        when(auctionRepo.findByIdForUpdate(101L)).thenReturn(Optional.of(auction));

        task.closeOne(101L);

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.ENDED);
        assertThat(auction.getEndOutcome()).isEqualTo(AuctionEndOutcome.RESERVE_NOT_MET);
        assertThat(auction.getWinnerUserId()).isNull();
        assertThat(auction.getFinalBidAmount()).isNull();
        verify(proxyBidRepo).exhaustAllActiveByAuctionId(101L);
        // userRepo.findById must NOT be called on non-SOLD outcomes — the
        // envelope elides the display name.
        verifyNoInteractions(userRepo);
        // RESERVE_NOT_MET is a no-escrow outcome — no payout to orchestrate.
        verifyNoInteractions(escrowService);

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCommit());

        ArgumentCaptor<AuctionEndedEnvelope> cap =
                ArgumentCaptor.forClass(AuctionEndedEnvelope.class);
        verify(publisher).publishEnded(cap.capture());
        AuctionEndedEnvelope env = cap.getValue();
        assertThat(env.endOutcome()).isEqualTo(AuctionEndOutcome.RESERVE_NOT_MET);
        assertThat(env.winnerUserId()).isNull();
        assertThat(env.winnerDisplayName()).isNull();
        assertThat(env.finalBid()).isNull();
    }

    @Test
    void noBids_whenBidCountZero_elidesReserveCheck() {
        Auction auction = Auction.builder()
                .id(102L)
                .status(AuctionStatus.ACTIVE)
                .endsAt(OffsetDateTime.now(fixed).minusSeconds(1))
                .startingBid(500L)
                .currentBid(0L)
                .reservePrice(1000L)
                .bidCount(0)
                .build();
        when(auctionRepo.findByIdForUpdate(102L)).thenReturn(Optional.of(auction));

        task.closeOne(102L);

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.ENDED);
        assertThat(auction.getEndOutcome()).isEqualTo(AuctionEndOutcome.NO_BIDS);
        assertThat(auction.getWinnerUserId()).isNull();
        assertThat(auction.getFinalBidAmount()).isNull();
        verify(proxyBidRepo).exhaustAllActiveByAuctionId(102L);
        verifyNoInteractions(userRepo);
        // NO_BIDS is a no-escrow outcome.
        verifyNoInteractions(escrowService);

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCommit());

        ArgumentCaptor<AuctionEndedEnvelope> cap =
                ArgumentCaptor.forClass(AuctionEndedEnvelope.class);
        verify(publisher).publishEnded(cap.capture());
        assertThat(cap.getValue().endOutcome()).isEqualTo(AuctionEndOutcome.NO_BIDS);
        assertThat(cap.getValue().bidCount()).isZero();
    }

    @Test
    void sold_whenNoReserveSet_andBidsExist() {
        // No reserve_price at all + bidCount > 0 must land on SOLD (not
        // RESERVE_NOT_MET) — the null-guard short-circuits the comparison.
        User winner = User.builder().id(9L).displayName("Any Bidder").build();
        Auction auction = Auction.builder()
                .id(103L)
                .status(AuctionStatus.ACTIVE)
                .endsAt(OffsetDateTime.now(fixed).minusSeconds(1))
                .startingBid(500L)
                .currentBid(800L)
                .currentBidderId(9L)
                .reservePrice(null)
                .bidCount(1)
                .build();
        when(auctionRepo.findByIdForUpdate(103L)).thenReturn(Optional.of(auction));
        when(userRepo.findById(9L)).thenReturn(Optional.of(winner));

        task.closeOne(103L);

        assertThat(auction.getEndOutcome()).isEqualTo(AuctionEndOutcome.SOLD);
        assertThat(auction.getFinalBidAmount()).isEqualTo(800L);
    }

    // -------------------------------------------------------------------------
    // Three-gate re-check
    // -------------------------------------------------------------------------

    @Test
    void skipIfStatusNotActive_noMutationNoPublish() {
        for (AuctionStatus status : new AuctionStatus[]{
                AuctionStatus.ENDED, AuctionStatus.CANCELLED, AuctionStatus.SUSPENDED}) {
            Auction auction = Auction.builder()
                    .id(200L)
                    .status(status)
                    .endsAt(OffsetDateTime.now(fixed).minusSeconds(1))
                    .bidCount(1)
                    .currentBid(1000L)
                    .build();
            when(auctionRepo.findByIdForUpdate(200L)).thenReturn(Optional.of(auction));

            task.closeOne(200L);

            assertThat(auction.getStatus())
                    .as("status unchanged for %s", status)
                    .isEqualTo(status);
            assertThat(auction.getEndOutcome()).isNull();
        }
        verify(auctionRepo, never()).save(any());
        verify(proxyBidRepo, never()).exhaustAllActiveByAuctionId(anyLong());
        verifyNoInteractions(publisher);
        verifyNoInteractions(escrowService);
    }

    @Test
    void skipIfEndsAtInFuture_simulatesSnipeExtensionAfterQuery() {
        // Scheduler saw endsAt=PAST, but a bid extended the auction between
        // the query and the lock acquisition. The re-check must let it run.
        Auction auction = Auction.builder()
                .id(201L)
                .status(AuctionStatus.ACTIVE)
                .endsAt(OffsetDateTime.now(fixed).plusMinutes(5))
                .bidCount(1)
                .currentBid(1000L)
                .build();
        when(auctionRepo.findByIdForUpdate(201L)).thenReturn(Optional.of(auction));

        task.closeOne(201L);

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(auction.getEndOutcome()).isNull();
        verify(auctionRepo, never()).save(any());
        verify(proxyBidRepo, never()).exhaustAllActiveByAuctionId(anyLong());
        verifyNoInteractions(publisher);
        verifyNoInteractions(escrowService);
    }

    @Test
    void skipIfAuctionMissing_noErrorNoPublish() {
        when(auctionRepo.findByIdForUpdate(404L)).thenReturn(Optional.empty());

        task.closeOne(404L);

        verify(auctionRepo, never()).save(any());
        verify(proxyBidRepo, never()).exhaustAllActiveByAuctionId(anyLong());
        verifyNoInteractions(publisher, userRepo, escrowService);
    }

    @Test
    void exhaustsActiveProxies_evenWhenNoneToExhaust() {
        // The task always calls exhaust; the query is cheap and its
        // idempotence is part of the contract. Mockito default return of 0
        // confirms the path does not branch on prior presence.
        Auction auction = Auction.builder()
                .id(300L)
                .status(AuctionStatus.ACTIVE)
                .endsAt(OffsetDateTime.now(fixed).minusSeconds(1))
                .startingBid(500L)
                .currentBid(0L)
                .bidCount(0)
                .build();
        when(auctionRepo.findByIdForUpdate(300L)).thenReturn(Optional.of(auction));

        task.closeOne(300L);

        verify(proxyBidRepo).exhaustAllActiveByAuctionId(300L);
    }

    @Test
    void winnerUserMissing_onSold_leavesDisplayNameNull() {
        // Defensive path — the winner id is populated but the User row has
        // been deleted. The envelope must still publish with a null display
        // name rather than throwing.
        Auction auction = Auction.builder()
                .id(104L)
                .status(AuctionStatus.ACTIVE)
                .endsAt(OffsetDateTime.now(fixed).minusSeconds(1))
                .startingBid(500L)
                .currentBid(1500L)
                .currentBidderId(99L)
                .reservePrice(1000L)
                .bidCount(1)
                .build();
        when(auctionRepo.findByIdForUpdate(104L)).thenReturn(Optional.of(auction));
        when(userRepo.findById(99L)).thenReturn(Optional.empty());

        task.closeOne(104L);

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCommit());

        ArgumentCaptor<AuctionEndedEnvelope> cap =
                ArgumentCaptor.forClass(AuctionEndedEnvelope.class);
        verify(publisher).publishEnded(cap.capture());
        AuctionEndedEnvelope env = cap.getValue();
        assertThat(env.endOutcome()).isEqualTo(AuctionEndOutcome.SOLD);
        assertThat(env.winnerUserId()).isEqualTo(99L);
        assertThat(env.winnerDisplayName()).isNull();
    }
}
