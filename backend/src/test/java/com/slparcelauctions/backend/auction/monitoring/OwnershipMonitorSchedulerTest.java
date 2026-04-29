package com.slparcelauctions.backend.auction.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
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
import com.slparcelauctions.backend.auction.monitoring.config.OwnershipMonitorProperties;

/**
 * Unit coverage for {@link OwnershipMonitorScheduler#dispatchDueChecks}. The
 * scheduler's sole job is to translate the configured check interval into a
 * cutoff {@link OffsetDateTime}, hand that to
 * {@link AuctionRepository#findDueForOwnershipCheck}, and dispatch each
 * returned id to the async {@link OwnershipCheckTask}. Both paths are covered:
 * non-empty result (cutoff math + per-id dispatch order) and empty result
 * (no-op, no downstream interactions).
 */
@ExtendWith(MockitoExtension.class)
class OwnershipMonitorSchedulerTest {

    @Mock AuctionRepository auctionRepo;
    @Mock OwnershipCheckTask checkTask;
    OwnershipMonitorProperties props;
    OwnershipMonitorScheduler scheduler;
    Clock fixed;

    @BeforeEach
    void setUp() {
        props = new OwnershipMonitorProperties();
        props.setCheckIntervalMinutes(30);
        fixed = Clock.fixed(Instant.parse("2026-04-17T12:00:00Z"), ZoneOffset.UTC);
        scheduler = new OwnershipMonitorScheduler(auctionRepo, checkTask, props, fixed);
    }

    @Test
    void dispatchDueChecks_queriesWithCutoffAndNow_andDispatchesEachId() {
        when(auctionRepo.findDueForOwnershipCheck(any(), any()))
                .thenReturn(List.of(1L, 2L, 3L));

        scheduler.dispatchDueChecks();

        ArgumentCaptor<OffsetDateTime> cutoffCap = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> nowCap = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(auctionRepo).findDueForOwnershipCheck(cutoffCap.capture(), nowCap.capture());
        OffsetDateTime cutoff = cutoffCap.getValue();
        OffsetDateTime now = nowCap.getValue();
        // With a fixed clock at 2026-04-17T12:00:00Z and checkIntervalMinutes=30,
        // the cutoff must be exactly 30 minutes earlier than now, and now must
        // equal the clock instant.
        assertThat(now).isEqualTo(OffsetDateTime.now(fixed));
        assertThat(Duration.between(cutoff, now).toMinutes()).isEqualTo(30L);

        InOrder inOrder = inOrder(checkTask);
        inOrder.verify(checkTask).checkOne(1L);
        inOrder.verify(checkTask).checkOne(2L);
        inOrder.verify(checkTask).checkOne(3L);
    }

    @Test
    void dispatchDueChecks_emptyList_doesNotDispatch() {
        when(auctionRepo.findDueForOwnershipCheck(any(), any())).thenReturn(List.of());

        scheduler.dispatchDueChecks();

        verifyNoInteractions(checkTask);
    }
}
