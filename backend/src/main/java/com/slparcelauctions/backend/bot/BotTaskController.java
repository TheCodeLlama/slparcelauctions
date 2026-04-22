package com.slparcelauctions.backend.bot;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.bot.dto.BotMonitorResultRequest;
import com.slparcelauctions.backend.bot.dto.BotTaskClaimRequest;
import com.slparcelauctions.backend.bot.dto.BotTaskCompleteRequest;
import com.slparcelauctions.backend.bot.dto.BotTaskResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Bot worker queue + callback surface. Auth: bearer token (see
 * {@link BotSharedSecretAuthorizer}, Task 3). Endpoints:
 *
 * <ul>
 *   <li>{@code POST /claim} — atomic claim of the next due task (Task 2).</li>
 *   <li>{@code PUT /{id}/verify} — VERIFY callback (Task 4).</li>
 *   <li>{@code POST /{id}/monitor} — MONITOR_* callback (Task 5).</li>
 *   <li>{@code GET /pending} — read-only debug view of the PENDING queue.</li>
 *   <li>{@code PUT /{id}} — DEPRECATED Task 4 shim for {@code /verify}; removed in Task 12.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/bot/tasks")
@RequiredArgsConstructor
public class BotTaskController {

    private final BotTaskService service;

    @PostMapping("/claim")
    public ResponseEntity<BotTaskResponse> claim(@Valid @RequestBody BotTaskClaimRequest body) {
        return service.claim(body.botUuid())
                .map(task -> ResponseEntity.ok(BotTaskResponse.from(task)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Read-only debug view of the PENDING queue. Retained for admin
     * triage — production workers use {@code POST /claim} which is atomic
     * and race-safe.
     */
    @GetMapping("/pending")
    public List<BotTaskResponse> pending() {
        return service.findPending().stream()
                .map(BotTaskResponse::from)
                .toList();
    }

    /**
     * VERIFY callback: terminates the task (COMPLETED/FAILED) and
     * transitions the auction. See {@link BotTaskService#complete} for
     * the full contract.
     */
    @PutMapping("/{taskId}/verify")
    public BotTaskResponse completeVerify(
            @PathVariable Long taskId,
            @Valid @RequestBody BotTaskCompleteRequest body) {
        return BotTaskResponse.from(service.complete(taskId, body));
    }

    /**
     * DEPRECATED Task 4 shim: forwards to {@code completeVerify}. Task 12
     * removes this method once all live workers are on the {@code /verify}
     * path.
     */
    @PutMapping("/{taskId}")
    @Deprecated
    public BotTaskResponse completeLegacy(
            @PathVariable Long taskId,
            @Valid @RequestBody BotTaskCompleteRequest body) {
        return completeVerify(taskId, body);
    }

    /**
     * MONITOR callback: applies the bot's observation and re-arms or
     * cancels the row per dispatcher logic. See
     * {@link BotTaskService#recordMonitorResult}.
     */
    @PostMapping("/{taskId}/monitor")
    public BotTaskResponse recordMonitorResult(
            @PathVariable Long taskId,
            @Valid @RequestBody BotMonitorResultRequest body) {
        return BotTaskResponse.from(service.recordMonitorResult(taskId, body));
    }
}
