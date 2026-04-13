package com.slparcelauctions.backend.wstest;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.wstest.dto.WsTestBroadcastRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Dev/test-only WebSocket verification harness. Broadcasts whatever the caller
 * POSTs onto {@code /topic/ws-test}, which any authenticated STOMP subscriber
 * receives in real time.
 *
 * <p><strong>Profile gating:</strong> {@code @Profile({"dev", "test"})} means
 * this bean is absent from the prod application context entirely. There is no
 * SecurityConfig matcher to maintain because there is no bean to guard.
 *
 * <p><strong>Authentication:</strong> the broadcast endpoint falls under the
 * {@code /api/v1/**} authenticated catch-all in {@code SecurityConfig} — only
 * logged-in users can trigger a broadcast. The {@code AuthPrincipal} injected
 * here is the same one the JWT filter attaches in {@code JwtAuthenticationFilter}.
 *
 * <p><strong>Wire-type note:</strong> {@code principal.userId()} is a Java
 * {@code Long}. Jackson serializes it as a JSON number, which lands in
 * JavaScript as a plain {@code number}. Safe because user IDs are well under
 * {@code Number.MAX_SAFE_INTEGER} (2^53 - 1). The frontend harness types
 * {@code senderId} as {@code number} to match.
 */
@RestController
@RequestMapping("/api/v1/ws-test")
@RequiredArgsConstructor
@Profile({"dev", "test"})
@Slf4j
public class WsTestController {

    private final SimpMessagingTemplate messagingTemplate;

    @PostMapping("/broadcast")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void broadcast(
            @Valid @RequestBody WsTestBroadcastRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        log.info("WS test broadcast from userId={}: {}", principal.userId(), request.message());
        Object payload = Map.of(
            "message", request.message(),
            "senderId", principal.userId(),
            "timestamp", Instant.now().toString()
        );
        messagingTemplate.convertAndSend("/topic/ws-test", payload);
    }
}
