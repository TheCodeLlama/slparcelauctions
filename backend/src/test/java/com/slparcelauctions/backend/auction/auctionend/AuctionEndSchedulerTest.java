package com.slparcelauctions.backend.auction.auctionend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.auction.AuctionRepository;

/**
 * Unit coverage for {@link AuctionEndScheduler#sweep}. Mirrors the shape of
 * {@code OwnershipMonitorSchedulerTest} — the scheduler's job is limited to:
 * <ul>
 *   <li>compute {@code now} from the injected {@link Clock},</li>
 *   <li>query {@link AuctionRepository#findActiveIdsDueForEnd},</li>
 *   <li>hand each returned id to {@link AuctionEndTask#closeOne} in turn,</li>
 *   <li>log-and-continue on per-id failures.</li>
 * </ul>
 * No Spring context is needed.
 */
@ExtendWith(MockitoExtension.class)
class AuctionEndSchedulerTest {

    @Mock AuctionRepository auctionRepo;
    @Mock AuctionEndTask auctionEndTask;

    AuctionEndScheduler scheduler;
    Clock fixed;

    @BeforeEach
    void setUp() {
        fixed = Clock.fixed(Instant.parse("2026-04-20T12:00:00Z"), ZoneOffset.UTC);
        scheduler = new AuctionEndScheduler(auctionRepo, auctionEndTask, fixed);
    }

    @Test
    void noOp_whenNoDueAuctions() {
        when(auctionRepo.findActiveIdsDueForEnd(any())).thenReturn(List.of());

        scheduler.sweep();

        verifyNoInteractions(auctionEndTask);
    }

    @Test
    void dispatchesAllDueAuctions_inOrder_withNowFromClock() {
        when(auctionRepo.findActiveIdsDueForEnd(any()))
                .thenReturn(List.of(1L, 2L, 3L));

        scheduler.sweep();

        ArgumentCaptor<OffsetDateTime> nowCap =
                ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(auctionRepo).findActiveIdsDueForEnd(nowCap.capture());
        assertThat(nowCap.getValue()).isEqualTo(OffsetDateTime.now(fixed));

        InOrder inOrder = inOrder(auctionEndTask);
        inOrder.verify(auctionEndTask).closeOne(1L);
        inOrder.verify(auctionEndTask).closeOne(2L);
        inOrder.verify(auctionEndTask).closeOne(3L);
    }

    @Test
    void continuesAfterFailure_onMiddleId() {
        when(auctionRepo.findActiveIdsDueForEnd(any()))
                .thenReturn(List.of(10L, 20L, 30L));
        doThrow(new RuntimeException("boom on 20"))
                .when(auctionEndTask).closeOne(20L);

        scheduler.sweep();

        // The third id must still be dispatched even though the middle one
        // blew up. Verifies the log-and-continue contract in the catch.
        InOrder inOrder = inOrder(auctionEndTask);
        inOrder.verify(auctionEndTask).closeOne(10L);
        inOrder.verify(auctionEndTask).closeOne(20L);
        inOrder.verify(auctionEndTask).closeOne(30L);
    }
}
