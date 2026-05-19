package com.slparcelauctions.backend.escrow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.slparcelauctions.backend.bot.BotTask;
import com.slparcelauctions.backend.bot.BotTaskRepository;
import com.slparcelauctions.backend.bot.BotTaskStatus;
import com.slparcelauctions.backend.bot.BotTaskType;
import com.slparcelauctions.backend.escrow.dto.EscrowStatusResponse;
import com.slparcelauctions.backend.escrow.exception.EscrowAccessDeniedException;
import com.slparcelauctions.backend.escrow.exception.EscrowManualAttemptsExhaustedException;
import com.slparcelauctions.backend.escrow.exception.EscrowStepNotReadyException;
import com.slparcelauctions.backend.escrow.review.EscrowManualReview;
import com.slparcelauctions.backend.escrow.review.EscrowManualReviewRepository;
import com.slparcelauctions.backend.escrow.review.ManualReviewReason;
import com.slparcelauctions.backend.escrow.review.ManualReviewRole;
import com.slparcelauctions.backend.escrow.review.ManualReviewStatus;
import com.slparcelauctions.backend.escrow.review.ManualReviewStep;
import com.slparcelauctions.backend.escrow.terminal.EscrowConfigProperties;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Unit coverage for {@link EscrowManualActionService}: the seller/buyer
 * manual verify-sell-to, verify-transfer and request-manual-review paths
 * (spec §5.4, §6.1, §7). Mockito-mock harness mirroring
 * {@code EscrowOwnershipCheckTaskTest}.
 */
@ExtendWith(MockitoExtension.class)
class EscrowManualActionServiceTest {

    private static final Long ESCROW_ID = 501L;
    private static final Long AUCTION_ID = 42L;
    private static final Long SELLER_ID = 7L;
    private static final Long WINNER_ID = 8L;
    private static final Long STRANGER_ID = 9L;
    private static final UUID PARCEL_UUID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SELLER_AVATAR = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID WINNER_AVATAR = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final int CAP = 3;

    @Mock EscrowRepository escrowRepo;
    @Mock EscrowService escrowService;
    @Mock BotTaskRepository botTaskRepo;
    @Mock EscrowManualReviewRepository manualReviewRepo;
    @Mock UserRepository userRepo;
    @Mock EscrowConfigProperties props;

    EscrowManualActionService service;
    Clock fixed;

    @BeforeEach
    void setUp() {
        fixed = Clock.fixed(Instant.parse("2026-05-17T12:00:00Z"), ZoneOffset.UTC);
        service = new EscrowManualActionService(
                escrowRepo, escrowService, botTaskRepo, manualReviewRepo,
                userRepo, props, fixed);
        lenient().when(props.manualVerifyAttempts()).thenReturn(CAP);
        lenient().when(escrowService.getStatus(eq(AUCTION_ID), any()))
                .thenReturn(stubStatus());
    }

    // ----- verifySellTo -----

    @Test
    void verifySellTo_sellerWithAttemptsLeft_expeditesBotTask_setsPendingFlag_noAttemptConsumed() {
        Escrow escrow = buildSetSellTo();
        when(escrowRepo.findByAuctionId(AUCTION_ID)).thenReturn(Optional.of(escrow));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
        BotTask task = BotTask.builder()
                .id(900L).taskType(BotTaskType.VERIFY_SELL_TO)
                .status(BotTaskStatus.PENDING)
                .auction(escrow.getAuction()).escrow(escrow)
                .parcelUuid(PARCEL_UUID).sentinelPrice(0L)
                .nextRunAt(OffsetDateTime.now(fixed).plusMinutes(25))
                .build();
        when(botTaskRepo.findOpenByEscrowAndType(ESCROW_ID, BotTaskType.VERIFY_SELL_TO))
                .thenReturn(List.of(task));

        EscrowStatusResponse out = service.verifySellTo(AUCTION_ID, SELLER_ID);

        assertThat(escrow.getManualVerifyPending()).isTrue();
        assertThat(escrow.getSellToVerifyAttempts()).isZero(); // not consumed yet
        assertThat(task.getNextRunAt()).isEqualTo(OffsetDateTime.now(fixed));
        verify(escrowRepo).save(escrow);
        verify(botTaskRepo).save(task);
        assertThat(out).isNotNull();
    }

    @Test
    void verifySellTo_attemptsExhausted_throws409() {
        Escrow escrow = buildSetSellTo();
        escrow.setSellToVerifyAttempts(CAP);
        when(escrowRepo.findByAuctionId(AUCTION_ID)).thenReturn(Optional.of(escrow));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));

        assertThatThrownBy(() -> service.verifySellTo(AUCTION_ID, SELLER_ID))
                .isInstanceOf(EscrowManualAttemptsExhaustedException.class);
        verify(escrowRepo, never()).save(any());
    }

    @Test
    void verifySellTo_calledByBuyer_throws403() {
        Escrow escrow = buildSetSellTo();
        when(escrowRepo.findByAuctionId(AUCTION_ID)).thenReturn(Optional.of(escrow));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));

        assertThatThrownBy(() -> service.verifySellTo(AUCTION_ID, WINNER_ID))
                .isInstanceOf(EscrowAccessDeniedException.class);
    }

    // ----- verifyTransfer (bot-dispatch refactor 2026-05-18) -----

    @Test
    void verifyTransfer_beforeSellToConfirmed_throws409() {
        Escrow escrow = buildSetSellTo(); // sellToConfirmedAt == null
        when(escrowRepo.findByAuctionId(AUCTION_ID)).thenReturn(Optional.of(escrow));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));

        assertThatThrownBy(() -> service.verifyTransfer(AUCTION_ID, WINNER_ID))
                .isInstanceOf(EscrowStepNotReadyException.class);
    }

    @Test
    void verifyTransfer_noOpenTask_createsBotTask_setsPendingFlag_noAttemptConsumed() {
        Escrow escrow = buildBuyParcel();
        when(escrowRepo.findByAuctionId(AUCTION_ID)).thenReturn(Optional.of(escrow));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
        stubWinner();
        when(botTaskRepo.findOpenByEscrowAndType(ESCROW_ID, BotTaskType.VERIFY_BUY_OWNER))
                .thenReturn(List.of());

        service.verifyTransfer(AUCTION_ID, WINNER_ID);

        ArgumentCaptor<BotTask> taskCap = ArgumentCaptor.forClass(BotTask.class);
        verify(botTaskRepo).save(taskCap.capture());
        BotTask saved = taskCap.getValue();
        assertThat(saved.getTaskType()).isEqualTo(BotTaskType.VERIFY_BUY_OWNER);
        assertThat(saved.getStatus()).isEqualTo(BotTaskStatus.PENDING);
        assertThat(saved.getEscrow()).isEqualTo(escrow);
        assertThat(saved.getParcelUuid()).isEqualTo(PARCEL_UUID);
        assertThat(saved.getNextRunAt()).isEqualTo(OffsetDateTime.now(fixed));
        assertThat(saved.getResultData())
                .containsEntry(EscrowManualActionService.REQUESTING_ROLE_KEY,
                        EscrowManualActionService.REQUESTING_ROLE_BUYER);
        assertThat(saved.getExpectedWinnerUuid()).isEqualTo(WINNER_AVATAR);

        assertThat(escrow.getManualVerifyPending()).isTrue();
        assertThat(escrow.getBuyVerifyBuyerAttempts()).isZero();
        assertThat(escrow.getBuyVerifySellerAttempts()).isZero();
        verify(escrowService, never()).confirmTransfer(any(), any());
        verify(escrowService, never()).stampChecked(any(), any());
        verify(escrowService, never()).freezeForFraud(any(), any(), any(), any());
    }

    @Test
    void verifyTransfer_sellerCaller_stampsSellerRoleOnTask() {
        Escrow escrow = buildBuyParcel();
        when(escrowRepo.findByAuctionId(AUCTION_ID)).thenReturn(Optional.of(escrow));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
        stubWinner();
        when(botTaskRepo.findOpenByEscrowAndType(ESCROW_ID, BotTaskType.VERIFY_BUY_OWNER))
                .thenReturn(List.of());

        service.verifyTransfer(AUCTION_ID, SELLER_ID);

        ArgumentCaptor<BotTask> taskCap = ArgumentCaptor.forClass(BotTask.class);
        verify(botTaskRepo).save(taskCap.capture());
        assertThat(taskCap.getValue().getResultData())
                .containsEntry(EscrowManualActionService.REQUESTING_ROLE_KEY,
                        EscrowManualActionService.REQUESTING_ROLE_SELLER);
        assertThat(escrow.getManualVerifyPending()).isTrue();
    }

    @Test
    void verifyTransfer_openTaskExists_expedites_updatesRolePayload() {
        Escrow escrow = buildBuyParcel();
        when(escrowRepo.findByAuctionId(AUCTION_ID)).thenReturn(Optional.of(escrow));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
        Map<String, Object> existingPayload = new java.util.HashMap<>();
        existingPayload.put(EscrowManualActionService.REQUESTING_ROLE_KEY,
                EscrowManualActionService.REQUESTING_ROLE_SELLER);
        BotTask existing = BotTask.builder()
                .id(901L).taskType(BotTaskType.VERIFY_BUY_OWNER)
                .status(BotTaskStatus.PENDING)
                .auction(escrow.getAuction()).escrow(escrow)
                .parcelUuid(PARCEL_UUID).sentinelPrice(0L)
                .resultData(existingPayload)
                .nextRunAt(OffsetDateTime.now(fixed).plusMinutes(20))
                .build();
        when(botTaskRepo.findOpenByEscrowAndType(ESCROW_ID, BotTaskType.VERIFY_BUY_OWNER))
                .thenReturn(List.of(existing));

        service.verifyTransfer(AUCTION_ID, WINNER_ID);

        assertThat(existing.getNextRunAt()).isEqualTo(OffsetDateTime.now(fixed));
        assertThat(existing.getResultData())
                .containsEntry(EscrowManualActionService.REQUESTING_ROLE_KEY,
                        EscrowManualActionService.REQUESTING_ROLE_BUYER);
        verify(botTaskRepo).save(existing);
        assertThat(escrow.getManualVerifyPending()).isTrue();
    }

    @Test
    void verifyTransfer_attemptsExhausted_throws409() {
        Escrow escrow = buildBuyParcel();
        escrow.setBuyVerifyBuyerAttempts(CAP);
        when(escrowRepo.findByAuctionId(AUCTION_ID)).thenReturn(Optional.of(escrow));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));

        assertThatThrownBy(() -> service.verifyTransfer(AUCTION_ID, WINNER_ID))
                .isInstanceOf(EscrowManualAttemptsExhaustedException.class);
        verify(botTaskRepo, never()).save(any());
    }

    @Test
    void verifyTransfer_calledByStranger_throws403() {
        Escrow escrow = buildBuyParcel();
        when(escrowRepo.findByAuctionId(AUCTION_ID)).thenReturn(Optional.of(escrow));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));

        assertThatThrownBy(() -> service.verifyTransfer(AUCTION_ID, STRANGER_ID))
                .isInstanceOf(EscrowAccessDeniedException.class);
    }

    // ----- requestManualReview -----

    @Test
    void requestManualReview_noOpenReview_createsOne() {
        Escrow escrow = buildSetSellTo();
        when(escrowRepo.findByAuctionId(AUCTION_ID)).thenReturn(Optional.of(escrow));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
        when(manualReviewRepo.findByEscrowIdAndStatus(ESCROW_ID, ManualReviewStatus.OPEN))
                .thenReturn(Optional.empty());

        service.requestManualReview(AUCTION_ID, SELLER_ID, "please look");

        ArgumentCaptor<EscrowManualReview> cap = ArgumentCaptor.forClass(EscrowManualReview.class);
        verify(manualReviewRepo).save(cap.capture());
        EscrowManualReview r = cap.getValue();
        assertThat(r.getEscrow()).isEqualTo(escrow);
        assertThat(r.getRequestedByUserId()).isEqualTo(SELLER_ID);
        assertThat(r.getRequestedRole()).isEqualTo(ManualReviewRole.SELLER);
        assertThat(r.getStep()).isEqualTo(ManualReviewStep.SET_SELL_TO);
        assertThat(r.getReason()).isEqualTo(ManualReviewReason.USER_REQUESTED);
        assertThat(r.getStatus()).isEqualTo(ManualReviewStatus.OPEN);
    }

    @Test
    void requestManualReview_existingOpenReview_idempotent_noNewRow() {
        Escrow escrow = buildBuyParcel();
        when(escrowRepo.findByAuctionId(AUCTION_ID)).thenReturn(Optional.of(escrow));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
        EscrowManualReview existing = EscrowManualReview.builder()
                .escrow(escrow).requestedByUserId(WINNER_ID)
                .requestedRole(ManualReviewRole.BUYER).step(ManualReviewStep.BUY_PARCEL)
                .reason(ManualReviewReason.USER_REQUESTED).status(ManualReviewStatus.OPEN)
                .build();
        when(manualReviewRepo.findByEscrowIdAndStatus(ESCROW_ID, ManualReviewStatus.OPEN))
                .thenReturn(Optional.of(existing));

        service.requestManualReview(AUCTION_ID, WINNER_ID, "again");
        service.requestManualReview(AUCTION_ID, SELLER_ID, "and again");

        verify(manualReviewRepo, never()).save(any());
    }

    @Test
    void requestManualReview_calledByStranger_throws403() {
        Escrow escrow = buildBuyParcel();
        when(escrowRepo.findByAuctionId(AUCTION_ID)).thenReturn(Optional.of(escrow));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));

        assertThatThrownBy(() -> service.requestManualReview(AUCTION_ID, STRANGER_ID, null))
                .isInstanceOf(EscrowAccessDeniedException.class);
    }

    @Test
    void requestManualReview_buyParcelPhase_stepIsBuyParcel() {
        Escrow escrow = buildBuyParcel();
        when(escrowRepo.findByAuctionId(AUCTION_ID)).thenReturn(Optional.of(escrow));
        when(escrowRepo.findByIdForUpdate(ESCROW_ID)).thenReturn(Optional.of(escrow));
        when(manualReviewRepo.findByEscrowIdAndStatus(ESCROW_ID, ManualReviewStatus.OPEN))
                .thenReturn(Optional.empty());

        service.requestManualReview(AUCTION_ID, WINNER_ID, null);

        ArgumentCaptor<EscrowManualReview> cap = ArgumentCaptor.forClass(EscrowManualReview.class);
        verify(manualReviewRepo, times(1)).save(cap.capture());
        assertThat(cap.getValue().getStep()).isEqualTo(ManualReviewStep.BUY_PARCEL);
        assertThat(cap.getValue().getRequestedRole()).isEqualTo(ManualReviewRole.BUYER);
    }

    // ----- helpers -----

    private void stubWinner() {
        User winner = User.builder()
                .id(WINNER_ID).email("winner@example.com").username("winner")
                .slAvatarUuid(WINNER_AVATAR).verified(true).build();
        lenient().when(userRepo.findById(WINNER_ID)).thenReturn(Optional.of(winner));
    }

    private EscrowStatusResponse stubStatus() {
        return new EscrowStatusResponse(
                UUID.randomUUID(), UUID.randomUUID(), "Winner Resident",
                EscrowState.TRANSFER_PENDING, 5000L, 250L, 4750L,
                null, null, null, null, null, null, null, null, null, null,
                List.of(), null, null, 3, 3, 3, null, null, null, null, false);
    }

    private Escrow buildSetSellTo() {
        return buildEscrow(null);
    }

    private Escrow buildBuyParcel() {
        return buildEscrow(OffsetDateTime.now(fixed).minusMinutes(10));
    }

    private Escrow buildEscrow(OffsetDateTime sellToConfirmedAt) {
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
                .parcelName("Test Parcel")
                .regionName("EscrowRegion")
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
                .transferDeadline(OffsetDateTime.now(fixed).plusHours(20))
                .fundedAt(OffsetDateTime.now(fixed).minusHours(2))
                .sellToConfirmedAt(sellToConfirmedAt)
                .consecutiveWorldApiFailures(0)
                .build();
    }

}
