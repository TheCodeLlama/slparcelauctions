package com.slparcelauctions.backend.auction.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.exception.ExternalApiTimeoutException;
import com.slparcelauctions.backend.sl.exception.ParcelNotFoundInSlException;
import com.slparcelauctions.backend.user.User;

import reactor.core.publisher.Mono;

/**
 * Unit coverage for {@link OwnershipCheckTask}. The five branches from spec
 * §8.2: owner match, owner mismatch, parcel 404, World API timeout, and a
 * non-ACTIVE guard that short-circuits before any World API call.
 */
@ExtendWith(MockitoExtension.class)
class OwnershipCheckTaskTest {

    private static final Long AUCTION_ID = 1L;
    private static final Long PARCEL_ID = 100L;
    private static final UUID PARCEL_UUID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SELLER_AVATAR = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_AVATAR = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Mock AuctionRepository auctionRepo;
    @Mock SlWorldApiClient worldApi;
    @Mock SuspensionService suspensionService;

    OwnershipCheckTask task;
    Clock fixed;

    @BeforeEach
    void setUp() {
        fixed = Clock.fixed(Instant.parse("2026-04-16T12:00:00Z"), ZoneOffset.UTC);
        task = new OwnershipCheckTask(auctionRepo, worldApi, suspensionService, fixed);
        lenient().when(auctionRepo.save(any(Auction.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void ownerMatches_updatesTimestamp_resetsFailureCounter_doesNotSuspend() {
        Auction a = buildActive();
        a.setConsecutiveWorldApiFailures(3);
        when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));
        when(worldApi.fetchParcel(PARCEL_UUID))
                .thenReturn(Mono.just(meta(SELLER_AVATAR, "agent")));

        task.checkOne(AUCTION_ID);

        assertThat(a.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(a.getLastOwnershipCheckAt()).isEqualTo(OffsetDateTime.now(fixed));
        assertThat(a.getConsecutiveWorldApiFailures()).isZero();
        verify(auctionRepo).save(a);
        verifyNoInteractions(suspensionService);
    }

    @Test
    void ownerMismatch_delegatesToSuspensionService_forOwnershipChange() {
        Auction a = buildActive();
        when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));
        ParcelMetadata changed = meta(OTHER_AVATAR, "agent");
        when(worldApi.fetchParcel(PARCEL_UUID)).thenReturn(Mono.just(changed));

        task.checkOne(AUCTION_ID);

        verify(suspensionService).suspendForOwnershipChange(a, changed);
        verify(auctionRepo, never()).save(any(Auction.class));
    }

    @Test
    void ownerChangedToGroup_alsoTreatedAsMismatch_suspended() {
        // Seller avatar UUID still matches the new UUID by coincidence? Not a
        // realistic case, but the ownertype flip alone is treated as a
        // mismatch — a group owning the parcel means the seller no longer
        // controls it as an individual.
        Auction a = buildActive();
        when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));
        ParcelMetadata groupOwned = meta(SELLER_AVATAR, "group");
        when(worldApi.fetchParcel(PARCEL_UUID)).thenReturn(Mono.just(groupOwned));

        task.checkOne(AUCTION_ID);

        verify(suspensionService).suspendForOwnershipChange(a, groupOwned);
    }

    @Test
    void parcelNotFound_delegatesToSuspensionService_forDeletedParcel() {
        Auction a = buildActive();
        when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));
        when(worldApi.fetchParcel(PARCEL_UUID))
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
        when(worldApi.fetchParcel(PARCEL_UUID))
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

    @Test
    void missingAuction_shortCircuits_noWorldApiCall() {
        when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.empty());

        task.checkOne(AUCTION_ID);

        verifyNoInteractions(worldApi);
        verifyNoInteractions(suspensionService);
    }

    // Epic 04 Task 7 — prove the lock entry path is the pessimistic variant.
    // A regression that reverts to findById would hide the race between a
    // bid placement and an ownership suspension.
    @Test
    void lockEntryPath_usesFindByIdForUpdate_notFindById() {
        Auction a = buildActive();
        when(auctionRepo.findByIdForUpdate(AUCTION_ID)).thenReturn(Optional.of(a));
        when(worldApi.fetchParcel(PARCEL_UUID))
                .thenReturn(Mono.just(meta(SELLER_AVATAR, "agent")));

        task.checkOne(AUCTION_ID);

        verify(auctionRepo).findByIdForUpdate(AUCTION_ID);
        verify(auctionRepo, never()).findById(AUCTION_ID);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Auction buildActive() {
        User seller = User.builder().id(42L).email("s@example.com")
                .slAvatarUuid(SELLER_AVATAR).verified(true).build();
        Parcel parcel = Parcel.builder().id(PARCEL_ID).slParcelUuid(PARCEL_UUID)
                .ownerUuid(SELLER_AVATAR).ownerType("agent")
                .regionName("Coniston").continentName("Sansara").verified(true).build();
        return Auction.builder()
                .id(AUCTION_ID).seller(seller).parcel(parcel)
                .status(AuctionStatus.ACTIVE)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .startingBid(1000L).durationHours(168)
                .snipeProtect(false).listingFeePaid(true)
                .currentBid(0L).bidCount(0)
                .consecutiveWorldApiFailures(0)
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .tags(new HashSet<>())
                .build();
    }

    private ParcelMetadata meta(UUID owner, String ownerType) {
        return new ParcelMetadata(
                PARCEL_UUID, owner, ownerType,
                "Test Parcel", "Coniston",
                1024, "desc", "http://example.com/snap.jpg", "MATURE",
                128.0, 64.0, 22.0);
    }
}
