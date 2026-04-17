package com.slparcelauctions.backend.bot;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.bot.dto.BotTaskCompleteRequest;
import com.slparcelauctions.backend.bot.dto.BotTaskResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Dev-profile-only stand-in for the SL bot worker. Exposes
 * {@code POST /api/v1/dev/bot/tasks/{taskId}/complete} which delegates to the
 * same {@link BotTaskService#complete} path as the production
 * {@link BotTaskController} endpoint. Routed under {@code /dev/} so it is
 * obviously a local testing shortcut.
 *
 * <p>Two-layer gating (mirrors {@code DevAuctionController}):
 * <ol>
 *   <li>{@link Profile @Profile("dev")} — bean is not instantiated outside dev.</li>
 *   <li>{@code SecurityConfig} permits {@code /api/v1/dev/**} unconditionally;
 *       the profile gate is the real trust boundary. In prod no handler exists
 *       so the request 404s at the MVC layer.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/dev/bot/tasks")
@RequiredArgsConstructor
@Profile("dev")
public class DevBotTaskController {

    private final BotTaskService service;

    @PostMapping("/{taskId}/complete")
    public BotTaskResponse complete(
            @PathVariable Long taskId,
            @Valid @RequestBody BotTaskCompleteRequest body) {
        return BotTaskResponse.from(service.complete(taskId, body));
    }
}
