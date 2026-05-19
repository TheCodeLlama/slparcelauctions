package com.slparcelauctions.backend.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.HashMap;
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
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.bot.dto.BotTaskResultRequest;
import com.slparcelauctions.backend.bot.dto.BuyOwnerResultRequest;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowManualActionService;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowService;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.escrow.FreezeReason;
import com.slparcelauctions.backend.escrow.review.EscrowManualReview;
import com.slparcelauctions.backend.escrow.review.EscrowManualReviewRepository;
import com.slparcelauctions.backend.escrow.review.ManualReviewReason;
import com.slparcelauctions.backend.escrow.review.ManualReviewStatus;
import com.slparcelauctions.backend.escrow.review.ManualReviewStep;
import com.slparcelauctions.backend.escrow.terminal.EscrowConfigProperties;
import com.slparcelauctions.backend.user.User;

/**
 * Unit coverage for {@link BotTaskResultService#apply} — the bot
 * {@code VERIFY_SELL_TO} result callback outcome matrix (spec §5.3, plan
 * Task 3.2). Mockito-mock harness; the locked escrow + escrow-service
 * transitions are mocked, so this asserts the orchestration decisions
 * (which escrow-service call, attempt consumption, streak handling,
 * reschedule vs. cancel, idempotency) rather than the downstream state.
 */
@ExtendWith(MockitoExtension.class)
class BotTaskResultServiceTest {

    private static final long TASK_ID = 900L;
    private static final long ESCROW_ID = 501L;
    private static final long AUCTION_ID = 42L;
    private static final int THRESHOLD = 5;

    @Mock BotTaskRepository botTaskRepo;
    @Mock EscrowRepository escrowRepo;
    @Mock EscrowService escrowService;
    @Mock EscrowManualReviewRepository manualReviewRepo;
    @Mock EscrowConfigProperties props;

    BotTaskResultService service;
    Clock fixed;
    OffsetDateTime now;

    @BeforeEach
    void setUp() {
        fixed = Clock.fixed(Instant.parse("2026-05-17T12:00:00Z"), ZoneOffset.UTC);
        now = OffsetDateTime.now(fixed);
        service = new BotTaskResultService(
                botTaskRepo, escrowRepo, escrowService, manualReviewRepo, props, fixed);
        lenient().when(props.sellToBotRecurrence()).thenReturn(Duration.ofMinutes(30));
        lenient().when(props.sellToBotRetryBackoff()).thenReturn(Duration.ofMinutes(2));
        lenient().when(props.sellToBotFailureThreshold()).thenReturn(THRESHOLD);
        lenient().when(botTaskRepo.save(any(BotTask.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(escrowRepo.save(any(Escrow.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ---- positive terminal outcomes ----

    @Test
    void sellToOk_confirmsSellTo_completesTask() {
        BotTask task = pendingTask();
        Escrow escrow = transferPending(now.plusHours(70));
        primeLoad(task, escrow);

        service.apply(TASK_ID, req(SellToOutcome.SELL_TO_OK));

        verify(escrowService).confirmSellTo(escrow, now);
        verify(escrowService, never()).confirmTransfer(any(), any());
        assertThat(task.getStatus()).isEqualTo(BotTaskStatus.COMPLETED);
        verify(botTaskRepo).save(task);
    }

    @Test
    void ownerAlreadyWinner_confirmsSellToThenTransfer_completesTask() {
        BotTask task = pendingTask();
        Escrow escrow = transferPending(now.plusHours(70));
        primeLoad(task, escrow);

        service.apply(TASK_ID, req(SellToOutcome.OWNER_ALREADY_WINNER));

        verify(escrowService).confirmSellTo(escrow, now);
        verify(escrowService).confirmTransfer(escrow, now);
        assertThat(task.getStatus()).isEqualTo(BotTaskStatus.COMPLETED);
    }

    // ---- definitive negatives ----

    @Test
    void definitiveNegative_manualPending_consumesAttempt_clearsFlag_reschedules() {
        BotTask task = pendingTask();
        Escrow escrow = transferPending(now.plusHours(70));
        escrow.setManualVerifyPending(true);
        escrow.setSellToVerifyAttempts(1);
        primeLoad(task, escrow);

        service.apply(TASK_ID, req(SellToOutcome.WRONG_BUYER));

        assertThat(escrow.getSellToVerifyAttempts()).isEqualTo(2);
        assertThat(escrow.getManualVerifyPending()).isFalse();
        assertThat(escrow.getSellToLastResult()).isEqualTo("WRONG_BUYER");
        assertThat(escrow.getSellToLastCheckedAt()).isEqualTo(now);
        assertThat(task.getStatus()).isEqualTo(BotTaskStatus.PENDING);
        assertThat(task.getNextRunAt()).isEqualTo(now.plusMinutes(30));
        verify(escrowService, never()).confirmSellTo(any(), any());
    }

    @Test
    void definitiveNegative_noManualPending_noAttempt_stillRecords_reschedules() {
        BotTask task = pendingTask();
        Escrow escrow = transferPending(now.plusHours(70));
        escrow.setManualVerifyPending(false);
        escrow.setSellToVerifyAttempts(1);
        primeLoad(task, escrow);

        service.apply(TASK_ID, req(SellToOutcome.SELL_TO_NOT_SET));

        assertThat(escrow.getSellToVerifyAttempts()).isEqualTo(1); // not consumed
        assertThat(escrow.getSellToLastResult()).isEqualTo("SELL_TO_NOT_SET");
        assertThat(escrow.getSellToLastCheckedAt()).isEqualTo(now);
        assertThat(task.getStatus()).isEqualTo(BotTaskStatus.PENDING);
        assertThat(task.getNextRunAt()).isEqualTo(now.plusMinutes(30));
    }

    @Test
    void definitiveNegative_pastDeadline_cancelsTask() {
        BotTask task = pendingTask();
        Escrow escrow = transferPending(now.minusHours(1)); // deadline elapsed
        primeLoad(task, escrow);

        service.apply(TASK_ID, req(SellToOutcome.PRICE_NOT_ZERO));

        assertThat(escrow.getSellToLastResult()).isEqualTo("PRICE_NOT_ZERO");
        assertThat(task.getStatus()).isEqualTo(BotTaskStatus.CANCELLED);
    }

    // ---- infra failures ----

    @Test
    void botError_incrementsStreak_noAttempt_clearsPending_belowThreshold_fastRetry() {
        BotTask task = pendingTask();
        Escrow escrow = transferPending(now.plusHours(70));
        escrow.setManualVerifyPending(true);
        escrow.setConsecutiveSellToBotFailures(1);
        escrow.setSellToVerifyAttempts(0);
        primeLoad(task, escrow);

        service.apply(TASK_ID, req(SellToOutcome.BOT_ERROR));

        assertThat(escrow.getConsecutiveSellToBotFailures()).isEqualTo(2);
        assertThat(escrow.getSellToVerifyAttempts()).isZero();
        assertThat(escrow.getManualVerifyPending()).isFalse();
        assertThat(task.getStatus()).isEqualTo(BotTaskStatus.PENDING);
        // Transient infra failure retries on the fast backoff, not the 30m
        // recurrence — the parcel is fine, the bot just couldn't observe it.
        assertThat(task.getNextRunAt()).isEqualTo(now.plusMinutes(2));
        verify(manualReviewRepo, never()).save(any());
    }

    @Test
    void accessDenied_belowThreshold_fastRetry() {
        BotTask task = pendingTask();
        Escrow escrow = transferPending(now.plusHours(70));
        escrow.setConsecutiveSellToBotFailures(0);
        primeLoad(task, escrow);

        service.apply(TASK_ID, req(SellToOutcome.ACCESS_DENIED));

        assertThat(escrow.getConsecutiveSellToBotFailures()).isEqualTo(1);
        assertThat(task.getStatus()).isEqualTo(BotTaskStatus.PENDING);
        assertThat(task.getNextRunAt()).isEqualTo(now.plusMinutes(2));
        verify(manualReviewRepo, never()).save(any());
    }

    @Test
    void botError_pastDeadline_cancelsTask_noFastRetry() {
        BotTask task = pendingTask();
        Escrow escrow = transferPending(now.minusHours(1)); // deadline elapsed
        escrow.setConsecutiveSellToBotFailures(0);
        primeLoad(task, escrow);

        service.apply(TASK_ID, req(SellToOutcome.BOT_ERROR));

        assertThat(escrow.getConsecutiveSellToBotFailures()).isEqualTo(1);
        assertThat(task.getStatus()).isEqualTo(BotTaskStatus.CANCELLED);
    }

    @Test
    void accessDenied_atThreshold_opensManualReview_idempotent() {
        BotTask task = pendingTask();
        Escrow escrow = transferPending(now.plusHours(70));
        escrow.setConsecutiveSellToBotFailures(THRESHOLD - 1);
        primeLoad(task, escrow);
        when(manualReviewRepo.findByEscrowIdAndStatus(ESCROW_ID, ManualReviewStatus.OPEN))
                .thenReturn(Optional.empty());

        service.apply(TASK_ID, req(SellToOutcome.ACCESS_DENIED));

        assertThat(escrow.getConsecutiveSellToBotFailures()).isEqualTo(THRESHOLD);
        ArgumentCaptor<EscrowManualReview> cap = ArgumentCaptor.forClass(EscrowManualReview.class);
        verify(manualReviewRepo).save(cap.capture());
        assertThat(cap.getValue().getReason()).isEqualTo(ManualReviewReason.BOT_PERSISTENT_FAILURE);
        assertThat(cap.getValue().getStatus()).isEqualTo(ManualReviewStatus.OPEN);
        assertThat(cap.getValue().getStep()).isEqualTo(ManualReviewStep.SET_SELL_TO);
    }

    @Test
    void accessDenied_atThreshold_existingOpenReview_noDuplicate() {
        BotTask task = pendingTask();
        Escrow escrow = transferPending(now.plusHours(70));
        escrow.setConsecutiveSellToBotFailures(THRESHOLD - 1);
        primeLoad(task, escrow);
        when(manualReviewRepo.findByEscrowIdAndStatus(ESCROW_ID, ManualReviewStatus.OPEN))
                .thenReturn(Optional.of(new EscrowManualReview()));

        service.apply(TASK_ID, req(SellToOutcome.ACCESS_DENIED));

        verify(manualReviewRepo, never()).save(any());
    }

    @Test
    void parcelNotFound_atThreshold_freezesForFraudParcelDeleted() {
        BotTask task = pendingTask();
        Escrow escrow = transferPending(now.plusHours(70));
        escrow.setConsecutiveSellToBotFailures(THRESHOLD - 1);
        primeLoad(task, escrow);

        service.apply(TASK_ID, req(SellToOutcome.PARCEL_NOT_FOUND));

        assertThat(escrow.getConsecutiveSellToBotFailures()).isEqualTo(THRESHOLD);
        verify(escrowService).freezeForFraud(
                eq(escrow), eq(FreezeReason.PARCEL_DELETED), any(), eq(now));
        verify(manualReviewRepo, never()).save(any());
    }

    @Test
    void parcelNotFound_belowThreshold_noFreeze_reschedules() {
        BotTask task = pendingTask();
        Escrow escrow = transferPending(now.plusHours(70));
        escrow.setConsecutiveSellToBotFailures(0);
        primeLoad(task, escrow);

        service.apply(TASK_ID, req(SellToOutcome.PARCEL_NOT_FOUND));

        assertThat(escrow.getConsecutiveSellToBotFailures()).isEqualTo(1);
        verify(escrowService, never()).freezeForFraud(any(), any(), any(), any());
        assertThat(task.getStatus()).isEqualTo(BotTaskStatus.PENDING);
        // PARCEL_NOT_FOUND keeps the existing 30m recurrence — the fast
        // retry is scoped to BOT_ERROR/ACCESS_DENIED only.
        assertThat(task.getNextRunAt()).isEqualTo(now.plusMinutes(30));
    }

    // ---- idempotency ----

    @Test
    void terminalTask_isNoOp() {
        BotTask task = pendingTask();
        task.setStatus(BotTaskStatus.COMPLETED);
        when(botTaskRepo.findById(TASK_ID)).thenReturn(Optional.of(task));

        service.apply(TASK_ID, req(SellToOutcome.SELL_TO_OK));

        verifyNoInteractions(escrowService);
        verify(escrowRepo, never()).findByIdForUpdate(any());
        verify(botTaskRepo, never()).save(any());
    }

    @Test
    void cancelledTask_isNoOp() {
        BotTask task = pendingTask();
        task.setStatus(BotTaskStatus.CANCELLED);
        when(botTaskRepo.findById(TASK_ID)).thenReturn(Optional.of(task));

        service.apply(TASK_ID, req(SellToOutcome.WRONG_BUYER));

        verifyNoInteractions(escrowService);
        verify(botTaskRepo, never()).save(any());
    }

    @Test
    void missingTask_isNoOp() {
        when(botTaskRepo.findById(TASK_ID)).thenReturn(Optional.empty());

        service.apply(TASK_ID, req(SellToOutcome.SELL_TO_OK));

        verifyNoInteractions(escrowService);
        verify(escrowRepo, never()).findByIdForUpdate(any());
    }

    // ---- applyVerifyBuyOwnerResult (bot-dispatch verify-purchase 2026-05-18) ----

    @Test
    void buyOwner_ownerIsWinner_confirmsTransfer_completesTask_clearsPending() {
        BotTask task = pendingBuyOwnerTask(EscrowManualActionService.REQUESTING_ROLE_BUYER);
        Escrow escrow = transferPending(now.plusHours(70));
        escrow.setManualVerifyPending(true);
        primeLoad(task, escrow);

        service.applyVerifyBuyOwnerResult(TASK_ID,
                new BuyOwnerResultRequest(BuyOwnerOutcome.OWNER_IS_WINNER, UUID.randomUUID(), "agent"));

        verify(escrowService).confirmTransfer(escrow, now);
        verify(escrowService, never()).stampChecked(any(), any());
        verify(escrowService, never()).freezeForFraud(any(), any(), any(), any());
        assertThat(escrow.getManualVerifyPending()).isFalse();
        assertThat(task.getStatus()).isEqualTo(BotTaskStatus.COMPLETED);
    }

    @Test
    void buyOwner_ownerStillPreTransfer_sellerRequest_consumesSellerCounter() {
        BotTask task = pendingBuyOwnerTask(EscrowManualActionService.REQUESTING_ROLE_SELLER);
        Escrow escrow = transferPending(now.plusHours(70));
        escrow.setManualVerifyPending(true);
        escrow.setBuyVerifySellerAttempts(0);
        escrow.setBuyVerifyBuyerAttempts(0);
        primeLoad(task, escrow);

        service.applyVerifyBuyOwnerResult(TASK_ID,
                new BuyOwnerResultRequest(BuyOwnerOutcome.OWNER_STILL_PRE_TRANSFER, UUID.randomUUID(), "agent"));

        assertThat(escrow.getBuyVerifySellerAttempts()).isEqualTo(1);
        assertThat(escrow.getBuyVerifyBuyerAttempts()).isZero();
        assertThat(escrow.getManualVerifyPending()).isFalse();
        verify(escrowService).stampChecked(escrow, now);
        verify(escrowService, never()).confirmTransfer(any(), any());
        assertThat(task.getStatus()).isEqualTo(BotTaskStatus.COMPLETED);
    }

    @Test
    void buyOwner_ownerStillPreTransfer_buyerRequest_consumesBuyerCounter() {
        BotTask task = pendingBuyOwnerTask(EscrowManualActionService.REQUESTING_ROLE_BUYER);
        Escrow escrow = transferPending(now.plusHours(70));
        escrow.setManualVerifyPending(true);
        escrow.setBuyVerifySellerAttempts(0);
        escrow.setBuyVerifyBuyerAttempts(0);
        primeLoad(task, escrow);

        service.applyVerifyBuyOwnerResult(TASK_ID,
                new BuyOwnerResultRequest(BuyOwnerOutcome.OWNER_STILL_PRE_TRANSFER, UUID.randomUUID(), "agent"));

        assertThat(escrow.getBuyVerifyBuyerAttempts()).isEqualTo(1);
        assertThat(escrow.getBuyVerifySellerAttempts()).isZero();
        assertThat(escrow.getManualVerifyPending()).isFalse();
        verify(escrowService).stampChecked(escrow, now);
    }

    @Test
    void buyOwner_ownerIsStranger_freezesUnknownOwner_clearsPending() {
        BotTask task = pendingBuyOwnerTask(EscrowManualActionService.REQUESTING_ROLE_BUYER);
        Escrow escrow = transferPending(now.plusHours(70));
        escrow.setManualVerifyPending(true);
        primeLoad(task, escrow);
        UUID strangerUuid = UUID.randomUUID();

        service.applyVerifyBuyOwnerResult(TASK_ID,
                new BuyOwnerResultRequest(BuyOwnerOutcome.OWNER_IS_STRANGER, strangerUuid, "agent"));

        verify(escrowService).freezeForFraud(
                eq(escrow), eq(FreezeReason.UNKNOWN_OWNER), any(), eq(now));
        assertThat(escrow.getManualVerifyPending()).isFalse();
        assertThat(escrow.getBuyVerifyBuyerAttempts()).isZero();
        assertThat(task.getStatus()).isEqualTo(BotTaskStatus.COMPLETED);
    }

    @Test
    void buyOwner_parcelDeleted_freezesParcelDeleted_clearsPending() {
        BotTask task = pendingBuyOwnerTask(EscrowManualActionService.REQUESTING_ROLE_BUYER);
        Escrow escrow = transferPending(now.plusHours(70));
        escrow.setManualVerifyPending(true);
        primeLoad(task, escrow);

        service.applyVerifyBuyOwnerResult(TASK_ID,
                new BuyOwnerResultRequest(BuyOwnerOutcome.PARCEL_DELETED, null, null));

        verify(escrowService).freezeForFraud(
                eq(escrow), eq(FreezeReason.PARCEL_DELETED), any(), eq(now));
        assertThat(escrow.getManualVerifyPending()).isFalse();
        assertThat(task.getStatus()).isEqualTo(BotTaskStatus.COMPLETED);
    }

    @Test
    void buyOwner_worldApiFailure_clearsPending_noAttempt_taskFailed() {
        BotTask task = pendingBuyOwnerTask(EscrowManualActionService.REQUESTING_ROLE_BUYER);
        Escrow escrow = transferPending(now.plusHours(70));
        escrow.setManualVerifyPending(true);
        escrow.setBuyVerifyBuyerAttempts(0);
        primeLoad(task, escrow);

        service.applyVerifyBuyOwnerResult(TASK_ID,
                new BuyOwnerResultRequest(BuyOwnerOutcome.WORLD_API_FAILURE, null, null));

        assertThat(escrow.getManualVerifyPending()).isFalse();
        assertThat(escrow.getBuyVerifyBuyerAttempts()).isZero();
        verify(escrowService, never()).confirmTransfer(any(), any());
        verify(escrowService, never()).stampChecked(any(), any());
        verify(escrowService, never()).freezeForFraud(any(), any(), any(), any());
        assertThat(task.getStatus()).isEqualTo(BotTaskStatus.FAILED);
    }

    @Test
    void buyOwner_unknownError_sameTransientHandling_asWorldApiFailure() {
        BotTask task = pendingBuyOwnerTask(EscrowManualActionService.REQUESTING_ROLE_BUYER);
        Escrow escrow = transferPending(now.plusHours(70));
        escrow.setManualVerifyPending(true);
        primeLoad(task, escrow);

        service.applyVerifyBuyOwnerResult(TASK_ID,
                new BuyOwnerResultRequest(BuyOwnerOutcome.UNKNOWN_ERROR, null, null));

        assertThat(escrow.getManualVerifyPending()).isFalse();
        verify(escrowService, never()).confirmTransfer(any(), any());
        assertThat(task.getStatus()).isEqualTo(BotTaskStatus.FAILED);
    }

    @Test
    void buyOwner_terminalTask_isNoOp() {
        BotTask task = pendingBuyOwnerTask(EscrowManualActionService.REQUESTING_ROLE_BUYER);
        task.setStatus(BotTaskStatus.COMPLETED);
        when(botTaskRepo.findById(TASK_ID)).thenReturn(Optional.of(task));

        service.applyVerifyBuyOwnerResult(TASK_ID,
                new BuyOwnerResultRequest(BuyOwnerOutcome.OWNER_IS_WINNER, null, null));

        verifyNoInteractions(escrowService);
        verify(escrowRepo, never()).findByIdForUpdate(any());
    }

    @Test
    void buyOwner_wrongTaskType_isNoOp() {
        BotTask task = pendingTask(); // VERIFY_SELL_TO type
        when(botTaskRepo.findById(TASK_ID)).thenReturn(Optional.of(task));

        service.applyVerifyBuyOwnerResult(TASK_ID,
                new BuyOwnerResultRequest(BuyOwnerOutcome.OWNER_IS_WINNER, null, null));

        verifyNoInteractions(escrowService);
        verify(escrowRepo, never()).findByIdForUpdate(any());
    }

    private BotTask pendingBuyOwnerTask(String requestingRole) {
        Escrow escrow = transferPending(now.plusHours(70));
        Map<String, Object> payload = new HashMap<>();
        if (requestingRole != null) {
            payload.put(EscrowManualActionService.REQUESTING_ROLE_KEY, requestingRole);
        }
        return BotTask.builder()
                .id(TASK_ID)
                .taskType(BotTaskType.VERIFY_BUY_OWNER)
                .status(BotTaskStatus.PENDING)
                .auction(escrow.getAuction())
                .escrow(escrow)
                .parcelUuid(UUID.randomUUID())
                .sentinelPrice(0L)
                .resultData(payload)
                .nextRunAt(now.minusMinutes(1))
                .build();
    }

    // ---- helpers ----

    private void primeLoad(BotTask task, Escrow escrow) {
        when(botTaskRepo.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
    }

    private BotTaskResultRequest req(SellToOutcome outcome) {
        return new BotTaskResultRequest(outcome, null, null, null, null);
    }

    private BotTask pendingTask() {
        Escrow escrow = transferPending(now.plusHours(70));
        BotTask task = BotTask.builder()
                .id(TASK_ID)
                .taskType(BotTaskType.VERIFY_SELL_TO)
                .status(BotTaskStatus.PENDING)
                .auction(escrow.getAuction())
                .escrow(escrow)
                .parcelUuid(UUID.randomUUID())
                .sentinelPrice(0L)
                .nextRunAt(now.minusMinutes(5))
                .recurrenceIntervalSeconds(1800)
                .build();
        return task;
    }

    private Escrow transferPending(OffsetDateTime deadline) {
        User seller = User.builder().id(7L).email("s@example.com").username("s")
                .verified(true).build();
        Auction auction = Auction.builder()
                .title("Test listing").id(AUCTION_ID).seller(seller)
                .status(AuctionStatus.TRANSFER_PENDING)
                .startingBid(1000L).durationHours(168)
                .snipeProtect(false).listingFeePaid(true)
                .currentBid(5000L).bidCount(2)
                .consecutiveWorldApiFailures(0)
                .commissionRate(new BigDecimal("0.05"))
                .tags(new HashSet<>())
                .finalBidAmount(5000L)
                .endOutcome(AuctionEndOutcome.SOLD)
                .winnerUserId(8L)
                .build();
        auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(UUID.randomUUID())
                .parcelName("Test Parcel").regionName("EscrowRegion")
                .areaSqm(1024).positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
        Escrow escrow = Escrow.builder()
                .id(ESCROW_ID)
                .auction(auction)
                .state(EscrowState.TRANSFER_PENDING)
                .finalBidAmount(5000L)
                .commissionAmt(250L)
                .payoutAmt(4750L)
                .transferDeadline(deadline)
                .fundedAt(now.minusHours(2))
                .consecutiveWorldApiFailures(0)
                .build();
        return escrow;
    }
}
