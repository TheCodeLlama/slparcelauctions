package com.slparcelauctions.backend.notification.ws;

import com.slparcelauctions.backend.notification.ws.envelope.PenaltyClearedEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * STOMP broadcaster for account-state events (cancellation penalties, etc.).
 *
 * <p>Delivers to the per-user queue {@code /user/queue/account}. All broker
 * errors are swallowed and logged to prevent cascade failures into callers.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AccountStateBroadcaster {

    private final SimpMessagingTemplate template;

    public void broadcastPenaltyCleared(long userId) {
        try {
            template.convertAndSendToUser(
                String.valueOf(userId),
                "/queue/account",
                new PenaltyClearedEnvelope()
            );
        } catch (Exception ex) {
            log.warn("WS broadcast PENALTY_CLEARED failed userId={}: {}", userId, ex.toString());
        }
    }
}
