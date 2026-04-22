package com.slparcelauctions.backend.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;
import com.slparcelauctions.backend.auction.monitoring.SuspensionService;
import com.slparcelauctions.backend.bot.dto.BotMonitorResultRequest;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowService;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.escrow.FreezeReason;

/**
 * Unit coverage for {@link BotMonitorDispatcher}. Pins the dispatch tables
 * for both MONITOR_AUCTION and MONITOR_ESCROW paths across all
 * {@link MonitorOutcome} values, plus streak-tracking semantics for
 * ACCESS_DENIED.
 */
@ExtendWith(MockitoExtension.class)
class BotMonitorDispatcherTest {

    @Mock private SuspensionService suspensionService;
    @Mock private EscrowService escrowService;

    private BotMonitorDispatcher dispatcher;

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-04-22T12:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        dispatcher = new BotMonitorDispatcher(suspensionService, escrowService, clock);
    }

    // ---------- MONITOR_AUCTION ----------

    @Test
    void monitorAuction_allGood_reArms_noServiceCalls() {
        BotTask task = auctionTask();
        DispatchOutcome out = dispatcher.dispatch(
                task, new BotMonitorResultRequest(MonitorOutcome.ALL_GOOD,
                        null, null, null, null));
        assertThat(out.shouldReArm()).isTrue();
        assertThat(out.logAction()).isEqualTo("ALL_GOOD");
        verify(suspensionService, never()).suspendForBotObservation(
                any(), any(), any());
    }

    @Test
    void monitorAuction_authBuyerChanged_suspends_noReArm() {
        BotTask task = auctionTask();
        DispatchOutcome out = dispatcher.dispatch(
                task, new BotMonitorResultRequest(MonitorOutcome.AUTH_BUYER_CHANGED,
                        UUID.randomUUID(), UUID.randomUUID(), 0L, null));
        assertThat(out.shouldReArm()).isFalse();
        verify(suspensionService, times(1)).suspendForBotObservation(
                eq(task.getAuction()),
                eq(FraudFlagReason.BOT_AUTH_BUYER_REVOKED),
                any());
    }

    @Test
    void monitorAuction_priceMismatch_suspends_noReArm() {
        BotTask task = auctionTask();
        DispatchOutcome out = dispatcher.dispatch(
                task, new BotMonitorResultRequest(MonitorOutcome.PRICE_MISMATCH,
                        UUID.randomUUID(), UUID.randomUUID(), 1L, null));
        assertThat(out.shouldReArm()).isFalse();
        verify(suspensionService, times(1)).suspendForBotObservation(
                eq(task.getAuction()),
                eq(FraudFlagReason.BOT_PRICE_DRIFT),
                any());
    }

    @Test
    void monitorAuction_ownerChanged_suspends_noReArm() {
        BotTask task = auctionTask();
        DispatchOutcome out = dispatcher.dispatch(
                task, new BotMonitorResultRequest(MonitorOutcome.OWNER_CHANGED,
                        UUID.randomUUID(), null, null, null));
        assertThat(out.shouldReArm()).isFalse();
        verify(suspensionService, times(1)).suspendForBotObservation(
                eq(task.getAuction()),
                eq(FraudFlagReason.BOT_OWNERSHIP_CHANGED),
                any());
    }

    @Test
    void monitorAuction_accessDeniedBelowThreshold_reArms_noSuspend() {
        BotTask task = auctionTask();
        Map<String, Object> data = new HashMap<>();
        data.put("accessDeniedStreak", 1);
        task.setResultData(data);
        DispatchOutcome out = dispatcher.dispatch(
                task, new BotMonitorResultRequest(MonitorOutcome.ACCESS_DENIED,
                        null, null, null, null));
        assertThat(out.shouldReArm()).isTrue();
        assertThat(task.getResultData().get("accessDeniedStreak")).isEqualTo(2);
        verify(suspensionService, never()).suspendForBotObservation(
                any(), any(), any());
    }

    @Test
    void monitorAuction_accessDeniedAtThreshold_suspends_noReArm() {
        BotTask task = auctionTask();
        Map<String, Object> data = new HashMap<>();
        data.put("accessDeniedStreak", 2);
        task.setResultData(data);
        DispatchOutcome out = dispatcher.dispatch(
                task, new BotMonitorResultRequest(MonitorOutcome.ACCESS_DENIED,
                        null, null, null, null));
        assertThat(out.shouldReArm()).isFalse();
        verify(suspensionService, times(1)).suspendForBotObservation(
                eq(task.getAuction()),
                eq(FraudFlagReason.BOT_ACCESS_REVOKED),
                any());
    }

    @Test
    void monitorAuction_streakResetsOnNonDenial() {
        BotTask task = auctionTask();
        Map<String, Object> data = new HashMap<>();
        data.put("accessDeniedStreak", 2);
        task.setResultData(data);
        dispatcher.dispatch(task, new BotMonitorResultRequest(
                MonitorOutcome.ALL_GOOD, null, null, null, null));
        assertThat(task.getResultData().get("accessDeniedStreak")).isEqualTo(0);
    }

    // ---------- MONITOR_ESCROW ----------

    @Test
    void monitorEscrow_stillWaiting_reArms_noServiceCalls() {
        BotTask task = escrowTask();
        DispatchOutcome out = dispatcher.dispatch(
                task, new BotMonitorResultRequest(MonitorOutcome.STILL_WAITING,
                        task.getExpectedSellerUuid(), null, null, null));
        assertThat(out.shouldReArm()).isTrue();
        verify(escrowService, never()).confirmTransfer(any(), any());
        verify(escrowService, never()).freezeForFraud(any(), any(), any(), any());
    }

    @Test
    void monitorEscrow_transferComplete_callsConfirmTransfer_noReArm() {
        BotTask task = escrowTask();
        UUID winner = task.getExpectedWinnerUuid();
        DispatchOutcome out = dispatcher.dispatch(
                task, new BotMonitorResultRequest(MonitorOutcome.TRANSFER_COMPLETE,
                        winner, null, null, null));
        assertThat(out.shouldReArm()).isFalse();
        verify(escrowService, times(1)).confirmTransfer(eq(task.getEscrow()), any());
    }

    @Test
    void monitorEscrow_ownerChangedToWinner_treatedAsTransferComplete() {
        BotTask task = escrowTask();
        UUID winner = task.getExpectedWinnerUuid();
        DispatchOutcome out = dispatcher.dispatch(
                task, new BotMonitorResultRequest(MonitorOutcome.OWNER_CHANGED,
                        winner, null, null, null));
        assertThat(out.shouldReArm()).isFalse();
        verify(escrowService, times(1)).confirmTransfer(eq(task.getEscrow()), any());
        verify(escrowService, never()).freezeForFraud(any(), any(), any(), any());
    }

    @Test
    void monitorEscrow_ownerChangedToThirdParty_freezes_noReArm() {
        BotTask task = escrowTask();
        DispatchOutcome out = dispatcher.dispatch(
                task, new BotMonitorResultRequest(MonitorOutcome.OWNER_CHANGED,
                        UUID.randomUUID(), null, null, null));
        assertThat(out.shouldReArm()).isFalse();
        ArgumentCaptor<FreezeReason> reason = ArgumentCaptor.forClass(FreezeReason.class);
        verify(escrowService, times(1)).freezeForFraud(
                eq(task.getEscrow()), reason.capture(), any(), any());
        assertThat(reason.getValue()).isEqualTo(FreezeReason.BOT_OWNERSHIP_CHANGED);
    }

    @Test
    void monitorEscrow_transferReady_publishesBroadcastOnFirstTransition() {
        BotTask task = escrowTask();
        DispatchOutcome out = dispatcher.dispatch(
                task, new BotMonitorResultRequest(MonitorOutcome.TRANSFER_READY,
                        task.getExpectedSellerUuid(), task.getExpectedWinnerUuid(),
                        0L, null));
        assertThat(out.shouldReArm()).isTrue();
        assertThat(task.getResultData().get("transferReady")).isEqualTo(true);
        verify(escrowService, times(1)).publishTransferReadyObserved(task.getEscrow());
    }

    @Test
    void monitorEscrow_accessDeniedAtThreshold_marksReviewRequired_noReArm() {
        BotTask task = escrowTask();
        Map<String, Object> data = new HashMap<>();
        data.put("accessDeniedStreak", 2);
        task.setResultData(data);
        DispatchOutcome out = dispatcher.dispatch(
                task, new BotMonitorResultRequest(MonitorOutcome.ACCESS_DENIED,
                        null, null, null, null));
        assertThat(out.shouldReArm()).isFalse();
        verify(escrowService, times(1)).markReviewRequired(task.getEscrow());
    }

    // ---------- helpers ----------

    private BotTask auctionTask() {
        return BotTask.builder()
                .id(1L)
                .taskType(BotTaskType.MONITOR_AUCTION)
                .status(BotTaskStatus.IN_PROGRESS)
                .auction(Auction.builder().id(42L).build())
                .parcelUuid(UUID.randomUUID())
                .sentinelPrice(999_999_999L)
                .expectedOwnerUuid(UUID.randomUUID())
                .expectedAuthBuyerUuid(UUID.randomUUID())
                .expectedSalePriceLindens(999_999_999L)
                .recurrenceIntervalSeconds(1800)
                .resultData(new HashMap<>())
                .build();
    }

    private BotTask escrowTask() {
        Escrow escrow = Escrow.builder().id(100L).state(EscrowState.FUNDED).build();
        return BotTask.builder()
                .id(2L)
                .taskType(BotTaskType.MONITOR_ESCROW)
                .status(BotTaskStatus.IN_PROGRESS)
                .auction(Auction.builder().id(42L).build())
                .escrow(escrow)
                .parcelUuid(UUID.randomUUID())
                .sentinelPrice(999_999_999L)
                .expectedSellerUuid(UUID.randomUUID())
                .expectedWinnerUuid(UUID.randomUUID())
                .expectedMaxSalePriceLindens(1L)
                .recurrenceIntervalSeconds(900)
                .resultData(new HashMap<>())
                .build();
    }
}
