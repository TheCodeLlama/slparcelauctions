package com.slparcelauctions.backend.auction.scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.bot.BotTask;
import com.slparcelauctions.backend.bot.BotTaskService;

/**
 * Unit coverage for {@link BotTaskTimeoutJob}. The job delegates all real
 * logic to {@link BotTaskService#findPendingOlderThan} and
 * {@link BotTaskService#markTimedOut}; these tests verify:
 *
 * <ul>
 *   <li>the configured {@code timeout-hours} value is converted to a
 *       {@link Duration} and passed through unchanged,</li>
 *   <li>every returned task is timed out,</li>
 *   <li>an empty queue short-circuits without invoking markTimedOut.</li>
 * </ul>
 *
 * <p>Partitioning of tasks by age (older/younger than threshold) is the
 * repository's concern and is covered by {@code BotTaskServiceTest}.
 */
class BotTaskTimeoutJobTest {

    private BotTaskService service;
    private BotTaskTimeoutJob job;

    @BeforeEach
    void setUp() throws Exception {
        service = mock(BotTaskService.class);
        job = new BotTaskTimeoutJob(service);
        Field f = BotTaskTimeoutJob.class.getDeclaredField("timeoutHours");
        f.setAccessible(true);
        f.setInt(job, 48);
    }

    @Test
    void sweep_timesOutEveryTaskFoundByService() {
        BotTask a = mock(BotTask.class);
        BotTask b = mock(BotTask.class);
        when(service.findPendingOlderThan(Duration.ofHours(48)))
                .thenReturn(List.of(a, b));

        job.sweepPending();

        verify(service).markTimedOut(a);
        verify(service).markTimedOut(b);
    }

    @Test
    void sweep_emptyQueue_doesNotInvokeMarkTimedOut() {
        when(service.findPendingOlderThan(Duration.ofHours(48)))
                .thenReturn(List.of());

        job.sweepPending();

        verify(service, never()).markTimedOut(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sweep_usesConfiguredTimeoutHours() throws Exception {
        Field f = BotTaskTimeoutJob.class.getDeclaredField("timeoutHours");
        f.setAccessible(true);
        f.setInt(job, 24);
        when(service.findPendingOlderThan(Duration.ofHours(24)))
                .thenReturn(List.of());

        job.sweepPending();

        verify(service).findPendingOlderThan(Duration.ofHours(24));
    }

    @Test
    void sweep_returnsNormally_evenIfMarkTimedOutThrows() {
        // Defensive: if one task fails, we still want the rest of the sweep to
        // proceed — but BotTaskService is @Transactional per call so the
        // exception bubbles up. This test pins current behavior (propagate).
        BotTask task = mock(BotTask.class);
        when(service.findPendingOlderThan(Duration.ofHours(48)))
                .thenReturn(List.of(task));
        org.mockito.Mockito.doThrow(new RuntimeException("simulated"))
                .when(service).markTimedOut(task);

        // The forEach propagates — confirming we aren't silently swallowing.
        assertThat(
                org.junit.jupiter.api.Assertions.assertThrows(
                        RuntimeException.class, () -> job.sweepPending()))
                .hasMessage("simulated");
    }
}
