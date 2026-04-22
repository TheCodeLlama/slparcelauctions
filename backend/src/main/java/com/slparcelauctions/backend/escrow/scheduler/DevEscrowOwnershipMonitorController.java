package com.slparcelauctions.backend.escrow.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Dev-profile trigger for {@link EscrowOwnershipMonitorJob}. Exposes
 * {@code POST /api/v1/dev/escrow-ownership-monitor/run} which invokes
 * {@link EscrowOwnershipMonitorJob#sweep()} synchronously so integration
 * tests (and developers with curl) can exercise the sweep without waiting
 * for the 5-minute cron tick.
 *
 * <p>Two-layer gating, mirroring
 * {@code DevOwnershipMonitorController}:
 * <ol>
 *   <li>{@link Profile @Profile("dev")} — bean is not instantiated outside dev.</li>
 *   <li>{@code SecurityConfig} permits {@code /api/v1/dev/**} unconditionally;
 *       the profile gate is the real trust boundary. In prod no handler
 *       exists, so the request 404s at the MVC layer.</li>
 * </ol>
 *
 * <p>Unlike the auction monitor, the escrow sweep dispatches synchronously,
 * so by the time this endpoint returns every per-escrow check has
 * completed and any side effects (state transitions, fraud flags,
 * broadcasts) are durable.
 */
@RestController
@RequestMapping("/api/v1/dev/escrow-ownership-monitor")
@Profile("dev")
@ConditionalOnProperty(
        value = "slpa.escrow.ownership-monitor-job.enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class DevEscrowOwnershipMonitorController {

    private final EscrowOwnershipMonitorJob job;

    @PostMapping("/run")
    public ResponseEntity<Void> runNow() {
        log.info("Dev-triggered escrow ownership monitor sweep");
        job.sweep();
        return ResponseEntity.accepted().build();
    }
}
