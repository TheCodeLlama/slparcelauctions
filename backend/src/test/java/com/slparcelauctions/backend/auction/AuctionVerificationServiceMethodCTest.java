package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.auction.dto.PendingVerification;
import com.slparcelauctions.backend.auction.monitoring.OwnershipCheckTimestampInitializer;
import com.slparcelauctions.backend.auction.monitoring.config.OwnershipMonitorProperties;
import com.slparcelauctions.backend.bot.BotTask;
import com.slparcelauctions.backend.bot.BotTaskRepository;
import com.slparcelauctions.backend.bot.BotTaskService;
import com.slparcelauctions.backend.bot.BotTaskStatus;
import com.slparcelauctions.backend.bot.BotTaskType;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.verification.VerificationCodeService;

/**
 * Unit coverage for {@link AuctionVerificationService} Method C (SALE_TO_BOT).
 *
 * <p>Method C is asynchronous: {@code triggerVerification} enqueues a
 * {@link BotTask} via {@code BotTaskService.createForAuction} and leaves the
 * auction in VERIFICATION_PENDING. The callback from the bot worker at
 * {@code PUT /api/v1/bot/tasks/{id}/verify} (or dev stub
 * {@code POST /api/v1/dev/bot/tasks/{id}/complete}) drives the transition to
 * ACTIVE — that flow is covered by {@code BotTaskServiceTest} and
 * {@code BotTaskControllerIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class AuctionVerificationServiceMethodCTest {

    private static final Long SELLER_ID = 42L;
    private static final Long AUCTION_ID = 1L;
    private static final Long PARCEL_ID = 100L;
    private static final UUID PARCEL_UUID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SELLER_AVATAR = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID ESCROW_UUID = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final long SENTINEL_PRICE = 999999999L;

    @Mock AuctionService auctionService;
    @Mock AuctionRepository auctionRepo;
    @Mock SlWorldApiClient worldApi;
    @Mock VerificationCodeService verificationCodeService;
    @Mock BotTaskService botTaskService;
    @Mock BotTaskRepository botTaskRepo;

    AuctionVerificationService service;

    private User seller;
    private Parcel parcel;
    private Clock fixed;

    @BeforeEach
    void setUp() {
        fixed = Clock.fixed(Instant.parse("2026-04-16T12:00:00Z"), ZoneOffset.UTC);
        OwnershipMonitorProperties props = new OwnershipMonitorProperties();
        OwnershipCheckTimestampInitializer ownershipInit =
                new OwnershipCheckTimestampInitializer(props, fixed);
        service = new AuctionVerificationService(
                auctionService, auctionRepo, worldApi, verificationCodeService,
                botTaskService, botTaskRepo, ownershipInit, fixed, ESCROW_UUID, SENTINEL_PRICE);

        seller = User.builder().id(SELLER_ID).email("s@example.com")
                .slAvatarUuid(SELLER_AVATAR).verified(true).build();
        parcel = Parcel.builder().id(PARCEL_ID).slParcelUuid(PARCEL_UUID)
                .ownerUuid(SELLER_AVATAR).ownerType("agent")
                .regionName("Coniston").continentName("Sansara").verified(true).build();

        lenient().when(auctionRepo.save(any(Auction.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // -------------------------------------------------------------------------
    // triggerVerification — enqueues bot task, stays pending
    // -------------------------------------------------------------------------

    @Test
    void verify_fromDraftPaid_withSaleToBot_enqueuesBotTaskAndStaysPending() {
        Auction a = build(AuctionStatus.DRAFT_PAID);
        when(auctionService.loadForSeller(AUCTION_ID, SELLER_ID)).thenReturn(a);

        Auction out = service.triggerVerification(AUCTION_ID, VerificationMethod.SALE_TO_BOT, SELLER_ID);

        assertThat(out.getStatus()).isEqualTo(AuctionStatus.VERIFICATION_PENDING);
        // Bot worker callback (not dispatch) drives the ACTIVE transition.
        assertThat(out.getStartsAt()).isNull();
        assertThat(out.getEndsAt()).isNull();
        assertThat(out.getVerifiedAt()).isNull();
        verify(botTaskService).createForAuction(a);
    }

    @Test
    void verify_fromVerificationFailed_retry_enqueuesNewBotTask() {
        // On retry, BotTaskService.createForAuction is responsible for marking any
        // prior PENDING/IN_PROGRESS task FAILED("Superseded by retry") — covered
        // in BotTaskServiceTest. Here we assert the dispatch still calls it and
        // clears stale verificationNotes before re-entering PENDING.
        Auction a = build(AuctionStatus.VERIFICATION_FAILED);
        a.setVerificationNotes("stale failure note");
        when(auctionService.loadForSeller(AUCTION_ID, SELLER_ID)).thenReturn(a);

        Auction out = service.triggerVerification(AUCTION_ID, VerificationMethod.SALE_TO_BOT, SELLER_ID);

        assertThat(out.getStatus()).isEqualTo(AuctionStatus.VERIFICATION_PENDING);
        assertThat(out.getVerificationNotes()).isNull();
        verify(botTaskService).createForAuction(a);
    }

    // -------------------------------------------------------------------------
    // buildPendingVerification — SALE_TO_BOT
    // -------------------------------------------------------------------------

    @Test
    void buildPendingVerification_saleToBot_returnsBotTaskIdAndInstructions() {
        Auction a = build(AuctionStatus.VERIFICATION_PENDING);
        BotTask pending = botTask(777L, a, BotTaskStatus.PENDING, OffsetDateTime.now(fixed));
        when(botTaskRepo.findByStatusOrderByCreatedAtAsc(BotTaskStatus.PENDING))
                .thenReturn(List.of(pending));

        PendingVerification pv = service.buildPendingVerification(a);

        assertThat(pv).isNotNull();
        assertThat(pv.method()).isEqualTo(VerificationMethod.SALE_TO_BOT);
        assertThat(pv.code()).isNull();
        assertThat(pv.codeExpiresAt()).isNull();
        assertThat(pv.botTaskId()).isEqualTo(777L);
        assertThat(pv.instructions())
                .contains(ESCROW_UUID.toString())
                .contains("L$" + SENTINEL_PRICE)
                .contains("48 hours");
    }

    @Test
    void buildPendingVerification_saleToBot_picksLatestPendingTask() {
        // Retry loop could leave a newer PENDING task — the seller response
        // should show the latest one, not the oldest.
        Auction a = build(AuctionStatus.VERIFICATION_PENDING);
        BotTask older = botTask(100L, a, BotTaskStatus.PENDING,
                OffsetDateTime.now(fixed).minusHours(10));
        BotTask newer = botTask(200L, a, BotTaskStatus.PENDING,
                OffsetDateTime.now(fixed).minusMinutes(5));
        when(botTaskRepo.findByStatusOrderByCreatedAtAsc(BotTaskStatus.PENDING))
                .thenReturn(List.of(older, newer));

        PendingVerification pv = service.buildPendingVerification(a);

        assertThat(pv).isNotNull();
        assertThat(pv.botTaskId()).isEqualTo(200L);
    }

    @Test
    void buildPendingVerification_saleToBot_fallsBackToInProgress() {
        // A worker (Epic 06) may have already claimed the task and marked it
        // IN_PROGRESS. The seller response should still show it.
        Auction a = build(AuctionStatus.VERIFICATION_PENDING);
        BotTask claimed = botTask(555L, a, BotTaskStatus.IN_PROGRESS,
                OffsetDateTime.now(fixed));
        when(botTaskRepo.findByStatusOrderByCreatedAtAsc(BotTaskStatus.PENDING))
                .thenReturn(List.of());
        when(botTaskRepo.findByStatusOrderByCreatedAtAsc(BotTaskStatus.IN_PROGRESS))
                .thenReturn(List.of(claimed));

        PendingVerification pv = service.buildPendingVerification(a);

        assertThat(pv).isNotNull();
        assertThat(pv.botTaskId()).isEqualTo(555L);
    }

    @Test
    void buildPendingVerification_saleToBot_noOpenTasks_returnsNull() {
        Auction a = build(AuctionStatus.VERIFICATION_PENDING);
        when(botTaskRepo.findByStatusOrderByCreatedAtAsc(BotTaskStatus.PENDING))
                .thenReturn(List.of());
        when(botTaskRepo.findByStatusOrderByCreatedAtAsc(BotTaskStatus.IN_PROGRESS))
                .thenReturn(List.of());

        assertThat(service.buildPendingVerification(a)).isNull();
    }

    @Test
    void buildPendingVerification_saleToBot_ignoresTasksForOtherAuctions() {
        Auction a = build(AuctionStatus.VERIFICATION_PENDING);
        Auction otherAuction = build(AuctionStatus.VERIFICATION_PENDING);
        otherAuction.setId(999L);
        BotTask foreign = botTask(1L, otherAuction, BotTaskStatus.PENDING,
                OffsetDateTime.now(fixed));
        when(botTaskRepo.findByStatusOrderByCreatedAtAsc(BotTaskStatus.PENDING))
                .thenReturn(List.of(foreign));
        when(botTaskRepo.findByStatusOrderByCreatedAtAsc(BotTaskStatus.IN_PROGRESS))
                .thenReturn(List.of());

        assertThat(service.buildPendingVerification(a)).isNull();
    }

    @Test
    void buildPendingVerification_saleToBot_nonPendingStatus_returnsNull() {
        Auction a = build(AuctionStatus.ACTIVE);

        assertThat(service.buildPendingVerification(a)).isNull();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Auction build(AuctionStatus status) {
        return Auction.builder()
                .title("Test listing")
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

    private BotTask botTask(Long id, Auction auction, BotTaskStatus status,
                            OffsetDateTime createdAt) {
        return BotTask.builder()
                .id(id)
                .taskType(BotTaskType.VERIFY)
                .status(status)
                .auction(auction)
                .parcelUuid(auction.getParcel().getSlParcelUuid())
                .regionName(auction.getParcel().getRegionName())
                .sentinelPrice(SENTINEL_PRICE)
                .createdAt(createdAt)
                .build();
    }
}
