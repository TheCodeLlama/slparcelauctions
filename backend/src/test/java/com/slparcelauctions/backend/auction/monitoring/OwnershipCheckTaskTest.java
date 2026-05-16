package com.slparcelauctions.backend.auction.monitoring;

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
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.CancellationLogRepository;
import com.slparcelauctions.backend.auction.monitoring.config.OwnershipMonitorProperties;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroup;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroupRepository;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.dto.ParcelPageData;
import com.slparcelauctions.backend.sl.exception.ExternalApiTimeoutException;
import com.slparcelauctions.backend.sl.exception.ParcelNotFoundInSlException;
import com.slparcelauctions.backend.user.User;

import reactor.core.publisher.Mono;

/**
 * Unit coverage for {@link OwnershipCheckTask}. Covers the owner-match,
 * owner-mismatch-with-streak, parcel-404, World API timeout, non-ACTIVE
 * guard, post-cancel watcher, and case-3 branches.
 */
@ExtendWith(MockitoExtension.class)
class OwnershipCheckTaskTest {

    private static final Long AUCTION_ID = 1L;
    private static final UUID PARCEL_UUID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SELLER_AVATAR = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_AVATAR = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final Long SL_GROUP_REG_ID = 99L;
    private static final UUID SL_GROUP_UUID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID OTHER_GROUP_UUID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @Mock AuctionRepository auctionRepo;
    @Mock SlWorldApiClient worldApi;
    @Mock SuspensionService suspensionService;
    @Mock CancellationLogRepository cancellationLogRepo;
    @Mock RealtyGroupSlGroupRepository slGroupRepo;

    OwnershipCheckTask task;
    Clock fixed;
    OwnershipMonitorProperties props;

    @BeforeEach
    void setUp() {
        fixed = Clock.fixed(Instant.parse("2026-04-16T12:00:00Z"), ZoneOffset.UTC);
        props = new OwnershipMonitorProperties();
        // Default of 2 streak threshold mirrors production.
        task = new OwnershipCheckTask(auctionRepo, worldApi, suspensionService,
                cancellationLogRepo, slGroupRepo, props, fixed);
        lenient().when(auctionRepo.save(any(Auction.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void ownerMatches_updatesTimestamp_resetsFailureAndMismatchCounters_doesNotSuspend() {
        Auction a = buildActive();
        a.setConsecutiveWorldApiFailures(3);
        a.setConsecutiveOwnerMismatches(1);
        when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));
        when(worldApi.fetchParcelPage(PARCEL_UUID))
                .thenReturn(Mono.just(new ParcelPageData(meta(SELLER_AVATAR, "agent"), UUID.randomUUID())));

        task.checkOne(AUCTION_ID);

        assertThat(a.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(a.getLastOwnershipCheckAt()).isEqualTo(OffsetDateTime.now(fixed));
        assertThat(a.getConsecutiveWorldApiFailures()).isZero();
        assertThat(a.getConsecutiveOwnerMismatches()).isZero();
        verify(auctionRepo).save(a);
        verifyNoInteractions(suspensionService);
    }

    @Test
    void ownerMismatch_singleObservation_doesNotSuspend_incrementsStreak() {
        // Default threshold = 2, so a single mismatch must NOT suspend.
        Auction a = buildActive();
        when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));
        ParcelMetadata changed = meta(OTHER_AVATAR, "agent");
        when(worldApi.fetchParcelPage(PARCEL_UUID)).thenReturn(
                Mono.just(new ParcelPageData(changed, UUID.randomUUID())));

        task.checkOne(AUCTION_ID);

        assertThat(a.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(a.getConsecutiveOwnerMismatches()).isEqualTo(1);
        verify(auctionRepo).save(a);
        verifyNoInteractions(suspensionService);
    }

    @Test
    void ownerMismatch_secondConsecutiveObservation_suspends() {
        // Threshold = 2: prior streak of 1 + this mismatch = 2 -> suspend.
        Auction a = buildActive();
        a.setConsecutiveOwnerMismatches(1);
        when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));
        ParcelMetadata changed = meta(OTHER_AVATAR, "agent");
        when(worldApi.fetchParcelPage(PARCEL_UUID)).thenReturn(
                Mono.just(new ParcelPageData(changed, UUID.randomUUID())));

        task.checkOne(AUCTION_ID);

        assertThat(a.getConsecutiveOwnerMismatches()).isEqualTo(2);
        verify(suspensionService).suspendForOwnershipChange(a, changed);
    }

    @Test
    void ownerMatch_inMiddleOfStreak_resetsCounter() {
        // Prior streak of 1 from an earlier sweep; this one is a match.
        Auction a = buildActive();
        a.setConsecutiveOwnerMismatches(1);
        when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));
        when(worldApi.fetchParcelPage(PARCEL_UUID)).thenReturn(
                Mono.just(new ParcelPageData(meta(SELLER_AVATAR, "agent"), UUID.randomUUID())));

        task.checkOne(AUCTION_ID);

        assertThat(a.getConsecutiveOwnerMismatches()).isZero();
        verifyNoInteractions(suspensionService);
    }

    @Test
    void ownerChangedToGroup_alsoTreatedAsMismatch_eventuallySuspends() {
        // Owner-type flip alone is treated as a mismatch -- a group owning the
        // parcel means the seller no longer controls it as an individual. Two
        // consecutive observations of the flip cross the threshold.
        Auction a = buildActive();
        a.setConsecutiveOwnerMismatches(1);
        when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));
        ParcelMetadata groupOwned = meta(SELLER_AVATAR, "group");
        when(worldApi.fetchParcelPage(PARCEL_UUID)).thenReturn(
                Mono.just(new ParcelPageData(groupOwned, UUID.randomUUID())));

        task.checkOne(AUCTION_ID);

        verify(suspensionService).suspendForOwnershipChange(a, groupOwned);
    }

    @Test
    void parcelNotFound_delegatesToSuspensionService_forDeletedParcel() {
        Auction a = buildActive();
        when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));
        when(worldApi.fetchParcelPage(PARCEL_UUID))
                .thenReturn(Mono.error(new ParcelNotFoundInSlException(PARCEL_UUID)));

        task.checkOne(AUCTION_ID);

        verify(suspensionService).suspendForDeletedParcel(a);
        verify(auctionRepo, never()).save(any(Auction.class));
    }

    @Test
    void worldApiTimeout_incrementsCounter_updatesTimestamp_doesNotSuspend() {
        Auction a = buildActive();
        a.setConsecutiveWorldApiFailures(2);
        when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));
        when(worldApi.fetchParcelPage(PARCEL_UUID))
                .thenReturn(Mono.error(new ExternalApiTimeoutException("World", "upstream 503")));

        task.checkOne(AUCTION_ID);

        assertThat(a.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(a.getConsecutiveWorldApiFailures()).isEqualTo(3);
        assertThat(a.getLastOwnershipCheckAt()).isEqualTo(OffsetDateTime.now(fixed));
        verify(auctionRepo).save(a);
        verifyNoInteractions(suspensionService);
    }

    @Test
    void nonActiveAuction_shortCircuits_noWorldApiCall_noSuspension() {
        Auction a = buildActive();
        a.setStatus(AuctionStatus.SUSPENDED);
        when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));

        task.checkOne(AUCTION_ID);

        verifyNoInteractions(worldApi);
        verifyNoInteractions(suspensionService);
        verify(auctionRepo, never()).save(any(Auction.class));
    }

    // -------------------------------------------------------------------------
    // Post-cancel watcher path -- one-shot, NOT gated by the streak threshold.
    // -------------------------------------------------------------------------

    @Test
    void cancelledWatchOpen_mismatch_raisesCancelAndSellFlag_clearsWatchUntil() {
        Auction a = buildActive();
        a.setStatus(AuctionStatus.CANCELLED);
        OffsetDateTime watchUntil = OffsetDateTime.now(fixed).plusHours(24);
        a.setPostCancelWatchUntil(watchUntil);
        OffsetDateTime cancelledAt = OffsetDateTime.now(fixed).minusHours(4);
        when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));
        when(worldApi.fetchParcelPage(PARCEL_UUID))
                .thenReturn(Mono.just(new ParcelPageData(meta(OTHER_AVATAR, "agent"), UUID.randomUUID())));
        com.slparcelauctions.backend.auction.CancellationLog log =
                com.slparcelauctions.backend.auction.CancellationLog.builder()
                        .id(7L).auction(a).seller(a.getSeller())
                        .cancelledFromStatus("ACTIVE").hadBids(true)
                        .cancelledAt(cancelledAt)
                        .build();
        when(cancellationLogRepo.findLatestByAuctionId(eq(AUCTION_ID), any()))
                .thenReturn(java.util.List.of(log));

        task.checkOne(AUCTION_ID);

        // First mismatch raises the flag immediately -- post-cancel watcher
        // intentionally bypasses the streak gate.
        verify(suspensionService).raiseCancelAndSellFlag(a, OTHER_AVATAR, cancelledAt);
        assertThat(a.getPostCancelWatchUntil()).isNull();
        assertThat(a.getStatus()).isEqualTo(AuctionStatus.CANCELLED);
        assertThat(a.getLastOwnershipCheckAt()).isEqualTo(OffsetDateTime.now(fixed));
        verify(auctionRepo).save(a);
        verify(suspensionService, never()).suspendForOwnershipChange(any(Auction.class), any());
    }

    @Test
    void cancelledWatchOpen_ownerMatches_doesNotRaiseFlag() {
        Auction a = buildActive();
        a.setStatus(AuctionStatus.CANCELLED);
        a.setPostCancelWatchUntil(OffsetDateTime.now(fixed).plusHours(24));
        when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));
        when(worldApi.fetchParcelPage(PARCEL_UUID))
                .thenReturn(Mono.just(new ParcelPageData(meta(SELLER_AVATAR, "agent"), UUID.randomUUID())));

        task.checkOne(AUCTION_ID);

        verify(suspensionService, never()).raiseCancelAndSellFlag(any(), any(), any());
        assertThat(a.getPostCancelWatchUntil()).isNotNull();
        assertThat(a.getLastOwnershipCheckAt()).isEqualTo(OffsetDateTime.now(fixed));
        verify(auctionRepo).save(a);
    }

    @Test
    void cancelledWatchExpired_shortCircuits_noWorldApiCall() {
        Auction a = buildActive();
        a.setStatus(AuctionStatus.CANCELLED);
        a.setPostCancelWatchUntil(OffsetDateTime.now(fixed).minusMinutes(1));
        when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));

        task.checkOne(AUCTION_ID);

        verifyNoInteractions(worldApi);
        verifyNoInteractions(suspensionService);
    }

    @Test
    void cancelledNoWatchUntil_shortCircuits_noWorldApiCall() {
        Auction a = buildActive();
        a.setStatus(AuctionStatus.CANCELLED);
        a.setPostCancelWatchUntil(null);
        when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));

        task.checkOne(AUCTION_ID);

        verifyNoInteractions(worldApi);
        verifyNoInteractions(suspensionService);
    }

    @Test
    void missingAuction_shortCircuits_noWorldApiCall() {
        when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.empty());

        task.checkOne(AUCTION_ID);

        verifyNoInteractions(worldApi);
        verifyNoInteractions(suspensionService);
    }

    @Test
    void lockEntryPath_usesFindByIdForUpdate_notFindById() {
        Auction a = buildActive();
        when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));
        when(worldApi.fetchParcelPage(PARCEL_UUID))
                .thenReturn(Mono.just(new ParcelPageData(meta(SELLER_AVATAR, "agent"), UUID.randomUUID())));

        task.checkOne(AUCTION_ID);

        verify(auctionRepo).findByIdForUpdate(AUCTION_ID);
        verify(auctionRepo, never()).findById(AUCTION_ID);
    }

    // -------------------------------------------------------------------------
    // Case-3 expected-owner
    // -------------------------------------------------------------------------

    @Test
    void runOnce_case3_parcelOwnerMatchesRegistration_passes() {
        Auction a = buildCase3();
        a.setConsecutiveWorldApiFailures(3);
        when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));
        when(slGroupRepo.findById(SL_GROUP_REG_ID)).thenReturn(Optional.of(buildSlGroupReg(SL_GROUP_UUID)));
        when(worldApi.fetchParcelPage(PARCEL_UUID))
                .thenReturn(Mono.just(new ParcelPageData(meta(SL_GROUP_UUID, "group"), UUID.randomUUID())));

        task.checkOne(AUCTION_ID);

        assertThat(a.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(a.getLastOwnershipCheckAt()).isEqualTo(OffsetDateTime.now(fixed));
        assertThat(a.getConsecutiveWorldApiFailures()).isZero();
        verify(auctionRepo).save(a);
        verifyNoInteractions(suspensionService);
    }

    @Test
    void runOnce_case3_parcelOwnerNoLongerMatches_streakGatedSuspend() {
        Auction a = buildCase3();
        a.setConsecutiveOwnerMismatches(1);
        when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));
        when(slGroupRepo.findById(SL_GROUP_REG_ID)).thenReturn(Optional.of(buildSlGroupReg(SL_GROUP_UUID)));
        ParcelMetadata changed = meta(OTHER_GROUP_UUID, "group");
        when(worldApi.fetchParcelPage(PARCEL_UUID))
                .thenReturn(Mono.just(new ParcelPageData(changed, UUID.randomUUID())));

        task.checkOne(AUCTION_ID);

        verify(suspensionService).suspendForOwnershipChange(a, changed);
    }

    @Test
    void runOnce_case3_registrationDeleted_skipsWithWarning() {
        Auction a = buildCase3();
        when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));
        when(slGroupRepo.findById(SL_GROUP_REG_ID)).thenReturn(Optional.empty());

        task.checkOne(AUCTION_ID);

        verifyNoInteractions(worldApi);
        verifyNoInteractions(suspensionService);
        verify(auctionRepo, never()).save(any(Auction.class));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Auction buildActive() {
        User seller = User.builder().id(42L).email("s@example.com").username("s")
                .slAvatarUuid(SELLER_AVATAR).verified(true).build();
        return Auction.builder()
                .title("Test listing")
                .id(AUCTION_ID).seller(seller).slParcelUuid(PARCEL_UUID)
                .status(AuctionStatus.ACTIVE)
                .startingBid(1000L).durationHours(168)
                .snipeProtect(false).listingFeePaid(true)
                .currentBid(0L).bidCount(0)
                .consecutiveWorldApiFailures(0)
                .consecutiveOwnerMismatches(0)
                .commissionRate(new BigDecimal("0.05"))
                .tags(new HashSet<>())
                .build();
    }

    private Auction buildCase3() {
        Auction a = buildActive();
        a.setRealtyGroupSlGroupId(SL_GROUP_REG_ID);
        return a;
    }

    private RealtyGroupSlGroup buildSlGroupReg(UUID slGroupUuid) {
        return RealtyGroupSlGroup.builder()
                .realtyGroupId(123L)
                .slGroupUuid(slGroupUuid)
                .verified(true)
                .build();
    }

    private ParcelMetadata meta(UUID owner, String ownerType) {
        return new ParcelMetadata(
                PARCEL_UUID, owner, ownerType, null,
                "Test Parcel", "Coniston",
                1024, "desc", "http://example.com/snap.jpg", "MODERATE",
                128.0, 64.0, 22.0);
    }
}
