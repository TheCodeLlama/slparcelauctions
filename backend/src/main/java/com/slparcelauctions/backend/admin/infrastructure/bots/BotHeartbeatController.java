package com.slparcelauctions.backend.admin.infrastructure.bots;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/bot")
@RequiredArgsConstructor
public class BotHeartbeatController {

    private final BotHeartbeatService service;

    // Auth: shared bearer token validated by BotSharedSecretAuthorizer, gated
    // globally in SecurityConfig for all /api/v1/bot/** requests. No per-method
    // @PreAuthorize needed — the security filter chain is the trust boundary.
    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(@Valid @RequestBody BotHeartbeatRequest req) {
        service.handle(req);
        return ResponseEntity.ok().build();
    }
}
