package com.slparcelauctions.backend.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
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
import org.springframework.dao.DataIntegrityViolationException;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.AuctionStatusConstants;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.auction.exception.ParcelAlreadyListedException;
import com.slparcelauctions.backend.bot.dto.BotTaskCompleteRequest;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.user.User;

/**
 * Unit coverage for {@link BotTaskService}. Covers Method C completion paths:
 * SUCCESS (auction → ACTIVE/BOT), FAILURE (auction → VERIFICATION_FAILED),
 * parcel-lock conflict (task FAILED + exception), validation errors on
 * escrow UUID / sentinel price, and the timeout sweep.
 */
@ExtendWith(MockitoExtension.class)
class BotTaskServiceTest {

    private static final Long AUCTION_ID = 1L;
    private static final Long PARCEL_ID = 100L;
    private static final Long TASK_ID = 77L;
    private static final UUID PARCEL_UUID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SELLER_AVATAR = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID ESCROW_UUID = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final UUID WRONG_UUID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    private static final long SENTINEL_PRICE = 999999999L;

    @Mock BotTaskRepository botTaskRepo;
    @Mock AuctionRepository auctionRepo;
    @Mock ParcelRepository parcelRepo;

    BotTaskService service;

    private User seller;
    private Parcel parcel;
    private Clock fixed;

    @BeforeEach
    void setUp() throws Exception {
        fixed = Clock.fixed(Instant.parse("2026-04-16T12:00:00Z"), ZoneOffset.UTC);
        service = new BotTaskService(botTaskRepo, auctionRepo, parcelRepo, fixed);
        injectConfig(service, "sentinelPrice", SENTINEL_PRICE);
        injectConfig(service, "primaryEscrowUuid", ESCROW_UUID);

        seller = User.builder().id(42L).email("s@example.com")
                .slAvatarUuid(SELLER_AVATAR).verified(true).build();
        parcel = Parcel.builder().id(PARCEL_ID).slParcelUuid(PARCEL_UUID)
                .ownerUuid(SELLER_AVATAR).ownerType("agent")
                .regionName("Coniston").continentName("Sansara").verified(true).build();

        lenient().when(botTaskRepo.save(any(BotTask.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(auctionRepo.save(any(Auction.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(auctionRepo.saveAndFlush(any(Auction.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(parcelRepo.save(any(Parcel.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(auctionRepo.existsByParcelIdAndStatusInAndIdNot(
                        anyLong(), anyCollection(), anyLong()))
                .thenReturn(false);
        lenient().when(botTaskRepo.findByStatusOrderByCreatedAtAsc(any(BotTaskStatus.class)))
                .thenReturn(List.of());
    }

    // -------------------------------------------------------------------------
    // createForAuction
    // -------------------------------------------------------------------------

    @Test
    void createForAuction_persistsPendingTaskWithCorrectFields() {
        Auction a = build(AuctionStatus.VERIFICATION_PENDING);

        BotTask created = service.createForAuction(a);

        ArgumentCaptor<BotTask> saved = ArgumentCaptor.forClass(BotTask.class);
        verify(botTaskRepo, atLeastOnce()).save(saved.capture());
        BotTask persisted = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertThat(persisted.getStatus()).isEqualTo(BotTaskStatus.PENDING);
        assertThat(persisted.getTaskType()).isEqualTo(BotTaskType.VERIFY);
        assertThat(persisted.getAuction()).isSameAs(a);
        assertThat(persisted.getParcelUuid()).isEqualTo(PARCEL_UUID);
        assertThat(persisted.getRegionName()).isEqualTo("Coniston");
        assertThat(persisted.getSentinelPrice()).isEqualTo(SENTINEL_PRICE);
        assertThat(created).isSameAs(persisted);
    }

    @Test
    void createForAuction_onRetry_cancelsPriorPendingTaskForSameAuction() {
        Auction a = build(AuctionStatus.VERIFICATION_PENDING);
        BotTask prior = botTask(55L, a, BotTaskStatus.PENDING);
        when(botTaskRepo.findByStatusOrderByCreatedAtAsc(BotTaskStatus.PENDING))
                .thenReturn(List.of(prior));
        when(botTaskRepo.findByStatusOrderByCreatedAtAsc(BotTaskStatus.IN_PROGRESS))
                .thenReturn(List.of());

        service.createForAuction(a);

        assertThat(prior.getStatus()).isEqualTo(BotTaskStatus.FAILED);
        assertThat(prior.getFailureReason()).isEqualTo("Superseded by retry");
        assertThat(prior.getCompletedAt()).isEqualTo(OffsetDateTime.now(fixed));
        // Two saves: the superseded task + the new one.
        verify(botTaskRepo, times(2)).save(any(BotTask.class));
    }

    @Test
    void createForAuction_onRetry_alsoCancelsInProgressTask() {
        Auction a = build(AuctionStatus.VERIFICATION_PENDING);
        BotTask claimed = botTask(88L, a, BotTaskStatus.IN_PROGRESS);
        when(botTaskRepo.findByStatusOrderByCreatedAtAsc(BotTaskStatus.PENDING))
                .thenReturn(List.of());
        when(botTaskRepo.findByStatusOrderByCreatedAtAsc(BotTaskStatus.IN_PROGRESS))
                .thenReturn(List.of(claimed));

        service.createForAuction(a);

        assertThat(claimed.getStatus()).isEqualTo(BotTaskStatus.FAILED);
        assertThat(claimed.getFailureReason()).isEqualTo("Superseded by retry");
    }

    @Test
    void createForAuction_doesNotCancelOtherAuctionsTasks() {
        Auction a = build(AuctionStatus.VERIFICATION_PENDING);
        Auction other = build(AuctionStatus.VERIFICATION_PENDING);
        other.setId(999L);
        BotTask foreign = botTask(66L, other, BotTaskStatus.PENDING);
        when(botTaskRepo.findByStatusOrderByCreatedAtAsc(BotTaskStatus.PENDING))
                .thenReturn(List.of(foreign));
        when(botTaskRepo.findByStatusOrderByCreatedAtAsc(BotTaskStatus.IN_PROGRESS))
                .thenReturn(List.of());

        service.createForAuction(a);

        assertThat(foreign.getStatus()).isEqualTo(BotTaskStatus.PENDING);
    }

    // -------------------------------------------------------------------------
    // complete — SUCCESS
    // -------------------------------------------------------------------------

    @Test
    void complete_success_happyPath_transitionsAuctionToActiveBot() {
        Auction a = build(AuctionStatus.VERIFICATION_PENDING);
        BotTask task = botTask(TASK_ID, a, BotTaskStatus.PENDING);
        when(botTaskRepo.findById(TASK_ID)).thenReturn(Optional.of(task));

        BotTaskCompleteRequest req = success();
        BotTask out = service.complete(TASK_ID, req);

        assertThat(out.getStatus()).isEqualTo(BotTaskStatus.COMPLETED);
        assertThat(out.getCompletedAt()).isEqualTo(OffsetDateTime.now(fixed));
        assertThat(out.getResultData())
                .containsEntry("authBuyerId", ESCROW_UUID.toString())
                .containsEntry("salePrice", SENTINEL_PRICE);

        assertThat(a.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(a.getVerificationTier()).isEqualTo(VerificationTier.BOT);
        assertThat(a.getStartsAt()).isEqualTo(OffsetDateTime.now(fixed));
        assertThat(a.getEndsAt()).isEqualTo(OffsetDateTime.now(fixed).plusHours(168));
        assertThat(a.getOriginalEndsAt()).isEqualTo(a.getEndsAt());
        assertThat(a.getVerifiedAt()).isEqualTo(OffsetDateTime.now(fixed));

        // Parcel metadata refreshed from payload.
        assertThat(parcel.getOwnerUuid()).isEqualTo(SELLER_AVATAR);
        assertThat(parcel.getAreaSqm()).isEqualTo(2048);
        assertThat(parcel.getRegionName()).isEqualTo("Coniston");
        assertThat(parcel.getPositionX()).isEqualTo(128.0);
        assertThat(parcel.getLastChecked()).isEqualTo(OffsetDateTime.now(fixed));

        verify(auctionRepo).saveAndFlush(a);
    }

    @Test
    void complete_success_wrongAuthBuyerId_throwsIllegalArgument() {
        Auction a = build(AuctionStatus.VERIFICATION_PENDING);
        BotTask task = botTask(TASK_ID, a, BotTaskStatus.PENDING);
        when(botTaskRepo.findById(TASK_ID)).thenReturn(Optional.of(task));

        BotTaskCompleteRequest req = new BotTaskCompleteRequest(
                "SUCCESS", WRONG_UUID, SENTINEL_PRICE, SELLER_AVATAR,
                "Test", 2048, "Coniston", 128.0, 64.0, 22.0, null);

        assertThatThrownBy(() -> service.complete(TASK_ID, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authBuyerId");
        // Task + auction must be unchanged on rejection.
        assertThat(task.getStatus()).isEqualTo(BotTaskStatus.PENDING);
        assertThat(a.getStatus()).isEqualTo(AuctionStatus.VERIFICATION_PENDING);
    }

    @Test
    void complete_success_wrongSalePrice_throwsIllegalArgument() {
        Auction a = build(AuctionStatus.VERIFICATION_PENDING);
        BotTask task = botTask(TASK_ID, a, BotTaskStatus.PENDING);
        when(botTaskRepo.findById(TASK_ID)).thenReturn(Optional.of(task));

        BotTaskCompleteRequest req = new BotTaskCompleteRequest(
                "SUCCESS", ESCROW_UUID, 100L, SELLER_AVATAR,
                "Test", 2048, "Coniston", 128.0, 64.0, 22.0, null);

        assertThatThrownBy(() -> service.complete(TASK_ID, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("salePrice");
    }

    @Test
    void complete_success_missingAuthBuyerId_throwsIllegalArgument() {
        Auction a = build(AuctionStatus.VERIFICATION_PENDING);
        BotTask task = botTask(TASK_ID, a, BotTaskStatus.PENDING);
        when(botTaskRepo.findById(TASK_ID)).thenReturn(Optional.of(task));

        BotTaskCompleteRequest req = new BotTaskCompleteRequest(
                "SUCCESS", null, SENTINEL_PRICE, SELLER_AVATAR,
                "Test", 2048, "Coniston", 128.0, 64.0, 22.0, null);

        assertThatThrownBy(() -> service.complete(TASK_ID, req))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void complete_success_parcelLockedByAnotherAuction_marksFailedAndThrows() {
        Auction a = build(AuctionStatus.VERIFICATION_PENDING);
        BotTask task = botTask(TASK_ID, a, BotTaskStatus.PENDING);
        when(botTaskRepo.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(auctionRepo.existsByParcelIdAndStatusInAndIdNot(
                PARCEL_ID, AuctionStatusConstants.LOCKING_STATUSES, AUCTION_ID))
                .thenReturn(true);
        Auction blocker = build(AuctionStatus.ACTIVE);
        blocker.setId(999L);
        when(auctionRepo.findFirstByParcelIdAndStatusIn(
                PARCEL_ID, AuctionStatusConstants.LOCKING_STATUSES))
                .thenReturn(Optional.of(blocker));

        assertThatThrownBy(() -> service.complete(TASK_ID, success()))
                .isInstanceOfSatisfying(ParcelAlreadyListedException.class, ex -> {
                    assertThat(ex.getParcelId()).isEqualTo(PARCEL_ID);
                    assertThat(ex.getBlockingAuctionId()).isEqualTo(999L);
                });

        assertThat(task.getStatus()).isEqualTo(BotTaskStatus.FAILED);
        assertThat(task.getFailureReason()).isEqualTo("PARCEL_LOCKED");
        assertThat(a.getStatus()).isEqualTo(AuctionStatus.VERIFICATION_FAILED);
    }

    @Test
    void complete_success_dbRaceOnSaveAndFlush_throwsParcelAlreadyListed() {
        Auction a = build(AuctionStatus.VERIFICATION_PENDING);
        BotTask task = botTask(TASK_ID, a, BotTaskStatus.PENDING);
        when(botTaskRepo.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(auctionRepo.saveAndFlush(any(Auction.class)))
                .thenThrow(new DataIntegrityViolationException("uq_auctions_parcel_locked_status"));

        assertThatThrownBy(() -> service.complete(TASK_ID, success()))
                .isInstanceOfSatisfying(ParcelAlreadyListedException.class, ex -> {
                    assertThat(ex.getParcelId()).isEqualTo(PARCEL_ID);
                    assertThat(ex.getBlockingAuctionId()).isEqualTo(-1L);
                });
    }

    // -------------------------------------------------------------------------
    // complete — FAILURE
    // -------------------------------------------------------------------------

    @Test
    void complete_failure_marksTaskFailedAndAuctionVerificationFailed() {
        Auction a = build(AuctionStatus.VERIFICATION_PENDING);
        BotTask task = botTask(TASK_ID, a, BotTaskStatus.PENDING);
        when(botTaskRepo.findById(TASK_ID)).thenReturn(Optional.of(task));

        BotTaskCompleteRequest req = new BotTaskCompleteRequest(
                "FAILURE", null, null, null, null, null, null, null, null, null,
                "Seller did not list parcel for sale");

        service.complete(TASK_ID, req);

        assertThat(task.getStatus()).isEqualTo(BotTaskStatus.FAILED);
        assertThat(task.getFailureReason()).isEqualTo("Seller did not list parcel for sale");
        assertThat(task.getCompletedAt()).isEqualTo(OffsetDateTime.now(fixed));
        assertThat(a.getStatus()).isEqualTo(AuctionStatus.VERIFICATION_FAILED);
        assertThat(a.getVerificationNotes())
                .startsWith("Bot: ")
                .contains("Seller did not list parcel for sale");
        // No auction state beyond VERIFICATION_FAILED — no refund, no ACTIVE transition.
        assertThat(a.getStartsAt()).isNull();
    }

    @Test
    void complete_failure_withoutReason_usesDefaultReason() {
        Auction a = build(AuctionStatus.VERIFICATION_PENDING);
        BotTask task = botTask(TASK_ID, a, BotTaskStatus.PENDING);
        when(botTaskRepo.findById(TASK_ID)).thenReturn(Optional.of(task));

        service.complete(TASK_ID, new BotTaskCompleteRequest(
                "FAILURE", null, null, null, null, null, null, null, null, null, null));

        assertThat(task.getFailureReason()).isEqualTo("Bot reported failure");
    }

    // -------------------------------------------------------------------------
    // complete — validation failures
    // -------------------------------------------------------------------------

    @Test
    void complete_taskNotFound_throwsIllegalArgument() {
        when(botTaskRepo.findById(TASK_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.complete(TASK_ID, success()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void complete_taskAlreadyCompleted_throwsIllegalArgument() {
        Auction a = build(AuctionStatus.ACTIVE);
        BotTask task = botTask(TASK_ID, a, BotTaskStatus.COMPLETED);
        when(botTaskRepo.findById(TASK_ID)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.complete(TASK_ID, success()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not open");
    }

    @Test
    void complete_taskAlreadyFailed_throwsIllegalArgument() {
        Auction a = build(AuctionStatus.VERIFICATION_FAILED);
        BotTask task = botTask(TASK_ID, a, BotTaskStatus.FAILED);
        when(botTaskRepo.findById(TASK_ID)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.complete(TASK_ID, success()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void complete_auctionNotInVerificationPending_throwsInvalidState() {
        Auction a = build(AuctionStatus.ACTIVE);
        BotTask task = botTask(TASK_ID, a, BotTaskStatus.PENDING);
        when(botTaskRepo.findById(TASK_ID)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.complete(TASK_ID, success()))
                .isInstanceOf(InvalidAuctionStateException.class);
    }

    @Test
    void complete_unknownResultValue_throwsIllegalArgument() {
        Auction a = build(AuctionStatus.VERIFICATION_PENDING);
        BotTask task = botTask(TASK_ID, a, BotTaskStatus.PENDING);
        when(botTaskRepo.findById(TASK_ID)).thenReturn(Optional.of(task));

        BotTaskCompleteRequest req = new BotTaskCompleteRequest(
                "MAYBE", null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.complete(TASK_ID, req))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // findPending / findPendingOlderThan
    // -------------------------------------------------------------------------

    @Test
    void findPending_returnsRepoResults() {
        Auction a = build(AuctionStatus.VERIFICATION_PENDING);
        BotTask task = botTask(TASK_ID, a, BotTaskStatus.PENDING);
        when(botTaskRepo.findByStatusOrderByCreatedAtAsc(BotTaskStatus.PENDING))
                .thenReturn(List.of(task));

        assertThat(service.findPending()).containsExactly(task);
    }

    @Test
    void findPendingOlderThan_computesCutoffFromClock() {
        Duration threshold = Duration.ofHours(48);
        OffsetDateTime expectedCutoff = OffsetDateTime.now(fixed).minus(threshold);
        when(botTaskRepo.findByStatusAndCreatedAtBefore(BotTaskStatus.PENDING, expectedCutoff))
                .thenReturn(List.of());

        service.findPendingOlderThan(threshold);

        verify(botTaskRepo).findByStatusAndCreatedAtBefore(BotTaskStatus.PENDING, expectedCutoff);
    }

    // -------------------------------------------------------------------------
    // markTimedOut
    // -------------------------------------------------------------------------

    @Test
    void markTimedOut_pendingTask_failsAndTransitionsAuctionToVerificationFailed() {
        Auction a = build(AuctionStatus.VERIFICATION_PENDING);
        BotTask task = botTask(TASK_ID, a, BotTaskStatus.PENDING);

        service.markTimedOut(task);

        assertThat(task.getStatus()).isEqualTo(BotTaskStatus.FAILED);
        assertThat(task.getFailureReason()).isEqualTo("TIMEOUT");
        assertThat(task.getCompletedAt()).isEqualTo(OffsetDateTime.now(fixed));
        assertThat(a.getStatus()).isEqualTo(AuctionStatus.VERIFICATION_FAILED);
        assertThat(a.getVerificationNotes()).contains("48-hour window");
    }

    @Test
    void markTimedOut_doesNotMoveAuctionIfAlreadyCancelled() {
        // Defensive guard: if the seller cancelled the auction between the queue
        // query and the timeout call, don't clobber CANCELLED → VERIFICATION_FAILED.
        Auction a = build(AuctionStatus.CANCELLED);
        BotTask task = botTask(TASK_ID, a, BotTaskStatus.PENDING);

        service.markTimedOut(task);

        assertThat(task.getStatus()).isEqualTo(BotTaskStatus.FAILED);
        assertThat(a.getStatus()).isEqualTo(AuctionStatus.CANCELLED);
        verify(auctionRepo, never()).save(a);
    }

    @Test
    void markTimedOut_skipsAlreadyTerminalTask() {
        Auction a = build(AuctionStatus.VERIFICATION_PENDING);
        BotTask task = botTask(TASK_ID, a, BotTaskStatus.COMPLETED);

        service.markTimedOut(task);

        assertThat(task.getFailureReason()).isNull();
        verify(botTaskRepo, never()).save(task);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Auction build(AuctionStatus status) {
        return Auction.builder()
                .id(AUCTION_ID).seller(seller).parcel(parcel).status(status)
                .verificationMethod(VerificationMethod.SALE_TO_BOT)
                .startingBid(1000L).durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(status != AuctionStatus.DRAFT)
                .currentBid(0L).bidCount(0)
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .tags(new HashSet<>())
                .createdAt(OffsetDateTime.now(fixed))
                .updatedAt(OffsetDateTime.now(fixed))
                .build();
    }

    private BotTask botTask(Long id, Auction auction, BotTaskStatus status) {
        return BotTask.builder()
                .id(id)
                .taskType(BotTaskType.VERIFY)
                .status(status)
                .auction(auction)
                .parcelUuid(auction.getParcel().getSlParcelUuid())
                .regionName(auction.getParcel().getRegionName())
                .sentinelPrice(SENTINEL_PRICE)
                .createdAt(OffsetDateTime.now(fixed).minusMinutes(30))
                .build();
    }

    private BotTaskCompleteRequest success() {
        return new BotTaskCompleteRequest(
                "SUCCESS", ESCROW_UUID, SENTINEL_PRICE,
                SELLER_AVATAR, "Test Parcel", 2048, "Coniston",
                128.0, 64.0, 22.0, null);
    }

    private static void injectConfig(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
