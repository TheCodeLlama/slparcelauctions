package com.slparcelauctions.backend.admin.infrastructure.reconciliation;

import com.slparcelauctions.backend.notification.NotificationPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Integration test for {@link ReconciliationService#runDaily()}.
 *
 * <p>The cron is scheduled at 03:00 UTC daily; it will not fire during a
 * test run that takes seconds. The bean is present via
 * {@code matchIfMissing = true} on the {@code @ConditionalOnProperty}.
 *
 * <p>All scheduler properties that would inject noise (e.g. background jobs
 * that mutate escrow state) are disabled so the DB is in a known-clean state.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.realty.invitation-expiry.enabled=false",
    "slpa.bot-pool-health-log.enabled=false"
})
@Transactional
class ReconciliationServiceTest {

    @Autowired
    ReconciliationService service;

    @Autowired
    ReconciliationRunRepository runRepo;

    @MockitoBean
    NotificationPublisher publisher;

    @Test
    void staleBalanceRecordsErrorStatus() {
        // No terminal heartbeats in DB — freshestBalance() returns empty
        // → service records ERROR and does NOT publish a mismatch notification.
        //
        // Note: filter on rows newer than testStart so pre-existing rows left
        // behind by prior tests in the same JVM (committed before this
        // @Transactional test acquired its rollback boundary) do not flake the
        // size assertion. This used to be a pre-D order-dependent flake.
        var testStart = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        service.runDaily();

        var runs = runRepo.findAll().stream()
                .filter(r -> !r.getRanAt().isBefore(testStart))
                .toList();
        assertThat(runs).hasSize(1);
        ReconciliationRun row = runs.get(0);
        assertThat(row.getStatus()).isEqualTo(ReconciliationStatus.ERROR);
        assertThat(row.getErrorMessage()).contains("stale");
        assertThat(row.getObservedBalance()).isNull();

        verify(publisher, never()).reconciliationMismatch(anyList(), anyLong(), anyString());
    }
}
