package com.slparcelauctions.backend.notification.ws;

import com.slparcelauctions.backend.notification.NotificationDao.UpsertResult;
import com.slparcelauctions.backend.notification.NotificationWsBroadcasterPort;
import com.slparcelauctions.backend.notification.dto.NotificationDto;
import com.slparcelauctions.backend.notification.ws.envelope.NotificationUpsertedEnvelope;
import com.slparcelauctions.backend.notification.ws.envelope.ReadStateChangedEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Real STOMP broadcaster for per-user notification events.
 *
 * <p>Marked {@code @Primary} so Spring prefers this bean over the
 * {@link com.slparcelauctions.backend.notification.NoOpNotificationWsBroadcaster}
 * fallback that ships without a live broker. Tests that need to intercept
 * broadcast calls should use {@code @MockitoBean NotificationWsBroadcasterPort}.
 *
 * <p>All broker errors are swallowed and logged — a failed WS push must never
 * abort the caller's transactional path. The afterCommit hook that calls these
 * methods cannot roll back a committed transaction anyway; swallowing is the
 * only correct strategy.
 */
@Component
@Primary
@RequiredArgsConstructor
@Slf4j
public class NotificationWsBroadcaster implements NotificationWsBroadcasterPort {

    private final SimpMessagingTemplate template;

    @Override
    public void broadcastUpsert(long userId, UpsertResult result, NotificationDto dto) {
        try {
            template.convertAndSendToUser(
                String.valueOf(userId),
                "/queue/notifications",
                new NotificationUpsertedEnvelope(result.wasUpdate(), dto)
            );
        } catch (Exception ex) {
            log.warn("WS broadcast NOTIFICATION_UPSERTED failed userId={} notifId={}: {}",
                     userId, dto.id(), ex.toString());
        }
    }

    @Override
    public void broadcastReadStateChanged(long userId) {
        try {
            template.convertAndSendToUser(
                String.valueOf(userId),
                "/queue/notifications",
                new ReadStateChangedEnvelope()
            );
        } catch (Exception ex) {
            log.warn("WS broadcast READ_STATE_CHANGED failed userId={}: {}", userId, ex.toString());
        }
    }
}
