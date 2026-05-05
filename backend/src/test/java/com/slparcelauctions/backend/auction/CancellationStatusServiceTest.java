package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.slparcelauctions.backend.auction.dto.CancellationHistoryDto;
import com.slparcelauctions.backend.auction.dto.CancellationStatusResponse;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;


/**
 * Unit coverage for {@link CancellationStatusService}.
 * <ul>
 *   <li>Ladder index → next consequence mapping (matches
 *       {@link CancellationService}'s authoritative logic).</li>
 *   <li>currentSuspension echoes the user's three new columns verbatim.</li>
 *   <li>History page-size clamp (>{@code MAX_PAGE_SIZE} → 50).</li>
 *   <li>History sort always cancelledAt DESC regardless of caller's Sort.</li>
 *   <li>{@code penaltyApplied} null when log row's kind is NONE.</li>
 *   <li>Photo URL is null when no listing photos (no parcel snapshot fallback).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CancellationStatusServiceTest {

    private static final long USER_ID = 42L;

    @Mock CancellationLogRepository logRepo;
    @Mock UserRepository userRepo;

    CancellationStatusService service;

    private User user;

    @BeforeEach
    void setUp() {
        CancellationPenaltyProperties penalty = new CancellationPenaltyProperties(
                new CancellationPenaltyProperties.Penalty(1000L, 2500L, 30),
                48);
        service = new CancellationStatusService(logRepo, userRepo, penalty);

        user = User.builder()
                .id(USER_ID)
                .email("seller@example.com").username("seller")
                .passwordHash("x")
                .penaltyBalanceOwed(0L)
                .bannedFromListing(false)
                .build();
        lenient().when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user));
    }

    @Test
    void statusFor_zeroPrior_returnsWarning() {
        when(logRepo.countPriorOffensesWithBids(USER_ID)).thenReturn(0L);

        CancellationStatusResponse resp = service.statusFor(USER_ID);

        assertThat(resp.priorOffensesWithBids()).isEqualTo(0L);
        assertThat(resp.nextConsequenceIfBidsPresent().kind())
                .isEqualTo(CancellationOffenseKind.WARNING);
        assertThat(resp.nextConsequenceIfBidsPresent().amountL()).isNull();
        assertThat(resp.nextConsequenceIfBidsPresent().suspends30Days()).isFalse();
        assertThat(resp.nextConsequenceIfBidsPresent().permanentBan()).isFalse();
    }

    @Test
    void statusFor_onePrior_returnsPenalty1000() {
        when(logRepo.countPriorOffensesWithBids(USER_ID)).thenReturn(1L);

        CancellationStatusResponse resp = service.statusFor(USER_ID);

        assertThat(resp.nextConsequenceIfBidsPresent().kind())
                .isEqualTo(CancellationOffenseKind.PENALTY);
        assertThat(resp.nextConsequenceIfBidsPresent().amountL()).isEqualTo(1000L);
    }

    @Test
    void statusFor_twoPrior_returnsPenaltyAnd30d() {
        when(logRepo.countPriorOffensesWithBids(USER_ID)).thenReturn(2L);

        CancellationStatusResponse resp = service.statusFor(USER_ID);

        assertThat(resp.nextConsequenceIfBidsPresent().kind())
                .isEqualTo(CancellationOffenseKind.PENALTY_AND_30D);
        assertThat(resp.nextConsequenceIfBidsPresent().amountL()).isEqualTo(2500L);
        assertThat(resp.nextConsequenceIfBidsPresent().suspends30Days()).isTrue();
    }

    @Test
    void statusFor_threePrior_returnsPermanentBan() {
        when(logRepo.countPriorOffensesWithBids(USER_ID)).thenReturn(3L);

        CancellationStatusResponse resp = service.statusFor(USER_ID);

        assertThat(resp.nextConsequenceIfBidsPresent().kind())
                .isEqualTo(CancellationOffenseKind.PERMANENT_BAN);
        assertThat(resp.nextConsequenceIfBidsPresent().permanentBan()).isTrue();
        // Ban has no L$ amount.
        assertThat(resp.nextConsequenceIfBidsPresent().amountL()).isNull();
    }

    @Test
    void statusFor_manyPrior_clampsToPermanentBan() {
        // Sanity — 12 prior offenses still reads as PERMANENT_BAN, not OOB.
        when(logRepo.countPriorOffensesWithBids(USER_ID)).thenReturn(12L);

        CancellationStatusResponse resp = service.statusFor(USER_ID);

        assertThat(resp.nextConsequenceIfBidsPresent().kind())
                .isEqualTo(CancellationOffenseKind.PERMANENT_BAN);
    }

    @Test
    void statusFor_currentSuspensionEchoesUserColumns() {
        OffsetDateTime suspendedUntil = OffsetDateTime.now().plusDays(20);
        user.setPenaltyBalanceOwed(1000L);
        user.setListingSuspensionUntil(suspendedUntil);
        user.setBannedFromListing(false);
        when(logRepo.countPriorOffensesWithBids(USER_ID)).thenReturn(1L);

        CancellationStatusResponse resp = service.statusFor(USER_ID);

        assertThat(resp.currentSuspension().penaltyBalanceOwed()).isEqualTo(1000L);
        assertThat(resp.currentSuspension().listingSuspensionUntil()).isEqualTo(suspendedUntil);
        assertThat(resp.currentSuspension().bannedFromListing()).isFalse();
    }

    @Test
    void statusFor_unknownUser_throws() {
        when(userRepo.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.statusFor(999L))
                .isInstanceOf(UserNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // historyFor
    // -------------------------------------------------------------------------

    @Test
    void historyFor_clampsPageSizeAt50() {
        when(logRepo.findBySellerId(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        service.historyFor(USER_ID, PageRequest.of(0, 9999));

        ArgumentCaptor<Pageable> cap = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(logRepo).findBySellerId(eq(USER_ID), cap.capture());
        Pageable forwarded = cap.getValue();
        assertThat(forwarded.getPageSize()).isEqualTo(50);
    }

    @Test
    void historyFor_alwaysSortsByCancelledAtDesc_regardlessOfCallerSort() {
        when(logRepo.findBySellerId(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        service.historyFor(USER_ID,
                PageRequest.of(0, 10, Sort.by("auction.id").ascending()));

        ArgumentCaptor<Pageable> cap = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(logRepo).findBySellerId(eq(USER_ID), cap.capture());
        Sort sort = cap.getValue().getSort();
        Sort.Order order = sort.getOrderFor("cancelledAt");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void historyFor_mapsLogRowsToDtos_penaltyAppliedNullWhenKindNone() {
        Auction auction = Auction.builder()
                .id(101L)
                .title("Pre-active cancel")
                .slParcelUuid(UUID.fromString("11111111-1111-1111-1111-111111111111"))
                .build();
        CancellationLog log = CancellationLog.builder()
                .id(1L)
                .auction(auction)
                .seller(user)
                .cancelledFromStatus("DRAFT")
                .hadBids(false)
                .penaltyKind(CancellationOffenseKind.NONE)
                .penaltyAmountL(null)
                .reason("Changed my mind")
                .cancelledAt(OffsetDateTime.now())
                .build();
        when(logRepo.findBySellerId(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(log), PageRequest.of(0, 10), 1));
        // Auction has no photos → primaryPhotoUrl is null (no parcel snapshot fallback).

        Page<CancellationHistoryDto> page = service.historyFor(USER_ID, PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
        CancellationHistoryDto dto = page.getContent().get(0);
        // No-bid cancellation → penaltyApplied is null per spec §7.4.
        assertThat(dto.penaltyApplied()).isNull();
        // No listing photos and no parcel snapshot fallback → null.
        assertThat(dto.primaryPhotoUrl()).isNull();
        assertThat(dto.auctionTitle()).isEqualTo("Pre-active cancel");
    }

    @Test
    void historyFor_mapsLogRowsToDtos_penaltyAppliedPopulatedWhenKindPenalty() {
        Auction auction = Auction.builder()
                .id(202L)
                .title("Active cancel with bids")
                .slParcelUuid(UUID.fromString("22222222-2222-2222-2222-222222222222"))
                .build();
        CancellationLog log = CancellationLog.builder()
                .id(2L)
                .auction(auction)
                .seller(user)
                .cancelledFromStatus("ACTIVE")
                .hadBids(true)
                .penaltyKind(CancellationOffenseKind.PENALTY)
                .penaltyAmountL(1000L)
                .reason("Personal reasons")
                .cancelledAt(OffsetDateTime.now())
                .build();
        when(logRepo.findBySellerId(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(log), PageRequest.of(0, 10), 1));

        Page<CancellationHistoryDto> page = service.historyFor(USER_ID, PageRequest.of(0, 10));

        CancellationHistoryDto dto = page.getContent().get(0);
        assertThat(dto.penaltyApplied()).isNotNull();
        assertThat(dto.penaltyApplied().kind()).isEqualTo(CancellationOffenseKind.PENALTY);
        assertThat(dto.penaltyApplied().amountL()).isEqualTo(1000L);
    }

    @Test
    void historyFor_resolvesPrimaryPhotoUrlFromAuctionPhoto() {
        AuctionPhoto p1 = AuctionPhoto.builder().id(50L).sortOrder(1).build();
        AuctionPhoto p2 = AuctionPhoto.builder().id(51L).sortOrder(0).build();
        Auction auction = Auction.builder()
                .id(303L)
                .title("Photo'd auction")
                .slParcelUuid(UUID.fromString("33333333-3333-3333-3333-333333333333"))
                // Order in the list is intentionally not sortOrder ASC — the
                // service must pick the min(sortOrder) regardless.
                .photos(new java.util.ArrayList<>(List.of(p1, p2)))
                .build();
        CancellationLog log = CancellationLog.builder()
                .id(3L)
                .auction(auction)
                .seller(user)
                .cancelledFromStatus("ACTIVE")
                .hadBids(true)
                .penaltyKind(CancellationOffenseKind.PENALTY)
                .penaltyAmountL(1000L)
                .cancelledAt(OffsetDateTime.now())
                .build();
        when(logRepo.findBySellerId(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(log), PageRequest.of(0, 10), 1));

        Page<CancellationHistoryDto> page = service.historyFor(USER_ID, PageRequest.of(0, 10));

        // Picks the photo with the smallest sortOrder (p2 = sortOrder 0).
        assertThat(page.getContent().get(0).primaryPhotoUrl())
                .isEqualTo("/api/v1/photos/51");
    }
}
