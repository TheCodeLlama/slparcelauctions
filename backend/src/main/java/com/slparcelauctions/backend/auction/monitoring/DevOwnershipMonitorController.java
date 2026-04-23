package com.slparcelauctions.backend.auction.monitoring;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Dev-profile-only trigger for the ownership-monitor scheduler. Exposes
 * {@code POST /api/v1/dev/ownership-monitor/run} which invokes
 * {@link OwnershipMonitorScheduler#dispatchDueChecks()} synchronously so an
 * integration test (or a developer with curl) can exercise the sweep without
 * waiting for the 30-second cron tick.
 *
 * <p>Two-layer gating (mirrors {@link com.slparcelauctions.backend.bot.DevBotTaskController}):
 * <ol>
 *   <li>{@link Profile @Profile("dev")} — bean is not instantiated outside dev.</li>
 *   <li>{@code SecurityConfig} permits {@code /api/v1/dev/**} unconditionally;
 *       the profile gate is the real trust boundary. In prod no handler
 *       exists so the request 404s at the MVC layer.</li>
 * </ol>
 *
 * <p>The sweep dispatches async tasks — by the time this endpoint returns,
 * checks have been scheduled but not necessarily completed. Callers that need
 * to observe side effects must poll.
 */
@RestController
@RequestMapping("/api/v1/dev/ownership-monitor")
@Profile("dev")
@ConditionalOnProperty(
        value = "slpa.ownership-monitor.enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class DevOwnershipMonitorController {

    private final OwnershipMonitorScheduler scheduler;

    @PostMapping("/run")
    public ResponseEntity<Void> runNow() {
        log.info("Dev-triggered ownership monitor sweep");
        scheduler.dispatchDueChecks();
        return ResponseEntity.accepted().build();
    }
}
