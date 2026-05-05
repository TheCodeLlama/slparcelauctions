package com.slparcelauctions.backend.admin.infrastructure.reminders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.user.User;

/**
 * Unit coverage for {@link EscrowTransferReminderScheduler#run}.
 *
 * <p>The scheduler queries {@link EscrowRepository#findEscrowsApproachingTransferDeadline}
 * with {@code [now+12h, now+36h]} and fires
 * {@link NotificationPublisher#escrowTransferReminder} for each result,
 * then stamps {@code reminderSentAt}. No Spring context needed.
 */
@ExtendWith(MockitoExtension.class)
class EscrowTransferReminderSchedulerTest {

    @Mock EscrowRepository escrowRepo;
    @Mock NotificationPublisher publisher;

    EscrowTransferReminderScheduler scheduler;
    Clock fixed;

    @BeforeEach
    void setUp() {
        fixed = Clock.fixed(Instant.parse("2026-04-27T09:00:00Z"), ZoneOffset.UTC);
        scheduler = new EscrowTransferReminderScheduler(escrowRepo, publisher, fixed);
    }

    @Test
    void noOp_whenNoEscrowsInWindow() {
        when(escrowRepo.findEscrowsApproachingTransferDeadline(any(), any()))
                .thenReturn(List.of());

        scheduler.run();

        verify(publisher, never()).escrowTransferReminder(
                anyLong(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void passesCorrectWindowBoundsToRepository() {
        when(escrowRepo.findEscrowsApproachingTransferDeadline(any(), any()))
                .thenReturn(List.of());

        scheduler.run();

        OffsetDateTime now = OffsetDateTime.now(fixed);
        ArgumentCaptor<OffsetDateTime> startCap = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> endCap   = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(escrowRepo).findEscrowsApproachingTransferDeadline(
                startCap.capture(), endCap.capture());

        assertThat(startCap.getValue()).isEqualTo(now.plusHours(12));
        assertThat(endCap.getValue()).isEqualTo(now.plusHours(36));
    }

    @Test
    void firesReminderAndStampsReminderSentAt_forEachEscrowInWindow() {
        Escrow escrow = buildFundedEscrow(1L, 2L, 3L, "Test Parcel",
                OffsetDateTime.now(fixed).plusHours(24));
        when(escrowRepo.findEscrowsApproachingTransferDeadline(any(), any()))
                .thenReturn(List.of(escrow));

        scheduler.run();

        verify(publisher).escrowTransferReminder(
                eq(3L),          // seller id
                eq(2L),          // auction id
                eq(1L),          // escrow id
                eq("Test Parcel"),
                eq(escrow.getTransferDeadline()));

        ArgumentCaptor<Escrow> savedCaptor = ArgumentCaptor.forClass(Escrow.class);
        verify(escrowRepo).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getReminderSentAt()).isNotNull();
    }

    @Test
    void firesReminderForAllEscrowsInWindow() {
        Escrow e1 = buildFundedEscrow(10L, 20L, 30L, "Parcel A",
                OffsetDateTime.now(fixed).plusHours(20));
        Escrow e2 = buildFundedEscrow(11L, 21L, 31L, "Parcel B",
                OffsetDateTime.now(fixed).plusHours(25));
        when(escrowRepo.findEscrowsApproachingTransferDeadline(any(), any()))
                .thenReturn(List.of(e1, e2));

        scheduler.run();

        verify(publisher).escrowTransferReminder(
                eq(30L), eq(20L), eq(10L), eq("Parcel A"), any());
        verify(publisher).escrowTransferReminder(
                eq(31L), eq(21L), eq(11L), eq("Parcel B"), any());
    }

    // ---------------------------------------------------------------------------
    // Seed helpers
    // ---------------------------------------------------------------------------

    private Escrow buildFundedEscrow(long escrowId, long auctionId, long sellerId,
                                      String parcelTitle, OffsetDateTime transferDeadline) {
        User seller = User.builder().username("u-" + java.util.UUID.randomUUID().toString().substring(0, 8)).id(sellerId).build();

        Auction auction = Auction.builder()
                .id(auctionId)
                .seller(seller)
                .title(parcelTitle)
                .build();

        return Escrow.builder()
                .id(escrowId)
                .auction(auction)
                .state(EscrowState.FUNDED)
                .finalBidAmount(1000L)
                .commissionAmt(50L)
                .payoutAmt(950L)
                .paymentDeadline(OffsetDateTime.now(fixed).plusHours(48))
                .transferDeadline(transferDeadline)
                .fundedAt(OffsetDateTime.now(fixed).minusHours(48))
                .build();
    }
}
