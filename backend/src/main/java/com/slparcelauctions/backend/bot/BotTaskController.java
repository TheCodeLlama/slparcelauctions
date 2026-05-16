package com.slparcelauctions.backend.bot;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.bot.dto.BotTaskClaimRequest;
import com.slparcelauctions.backend.bot.dto.BotTaskResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Bot worker queue surface. Auth: bearer token (see
 * {@link BotSharedSecretAuthorizer}). After the ownership-only
 * verification refactor (spec 2026-05-16) the verify + monitor task
 * types are retired; the {@code /claim} and {@code /pending} endpoints
 * stay as future-extension scaffolding and return 204 / [] until a
 * future task type starts producing rows.
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
     * triage; production workers use {@code POST /claim} which is
     * atomic and race-safe.
     */
    @GetMapping("/pending")
    public List<BotTaskResponse> pending() {
        return service.findPending().stream()
                .map(BotTaskResponse::from)
                .toList();
    }
}
