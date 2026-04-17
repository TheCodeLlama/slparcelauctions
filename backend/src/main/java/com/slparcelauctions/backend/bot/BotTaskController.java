package com.slparcelauctions.backend.bot;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.bot.dto.BotTaskCompleteRequest;
import com.slparcelauctions.backend.bot.dto.BotTaskResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Public queue + callback endpoints for the SL bot worker.
 *
 * <p><strong>Security posture (sub-spec 1):</strong> These endpoints ship on the
 * production URL surface <em>without authentication</em>. This is a deliberate
 * interim state — Epic 06 (SL bot service) adds bot worker authentication
 * (bearer token or mTLS) before the real worker is deployed. Until Epic 06 is
 * complete, this controller is exercised via the dev-profile stub
 * ({@link DevBotTaskController}). See spec §12.4 and the Epic 06 entry in
 * {@code DEFERRED_WORK.md}.
 *
 * <p><strong>Routes:</strong>
 * <ul>
 *   <li>{@code GET /api/v1/bot/tasks/pending} — FIFO list of PENDING tasks the
 *       worker should process next.</li>
 *   <li>{@code PUT /api/v1/bot/tasks/{taskId}} — worker callback reporting the
 *       outcome of a task (SUCCESS or FAILURE).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/bot/tasks")
@RequiredArgsConstructor
public class BotTaskController {

    private final BotTaskService service;

    @GetMapping("/pending")
    public List<BotTaskResponse> pending() {
        return service.findPending().stream()
                .map(BotTaskResponse::from)
                .toList();
    }

    @PutMapping("/{taskId}")
    public BotTaskResponse complete(
            @PathVariable Long taskId,
            @Valid @RequestBody BotTaskCompleteRequest body) {
        return BotTaskResponse.from(service.complete(taskId, body));
    }
}
