package com.slparcelauctions.backend.notification;

import com.slparcelauctions.backend.notification.NotificationDao.UpsertResult;
import com.slparcelauctions.backend.notification.dto.NotificationDto;

/**
 * Port interface for broadcasting notification state changes over WebSocket.
 * The concrete implementation is shipped in Task 4 and registered as
 * {@code @Primary}. Tests use {@code @MockitoBean} to satisfy this dependency
 * without a live STOMP broker.
 */
public interface NotificationWsBroadcasterPort {
    void broadcastUpsert(long userId, UpsertResult result, NotificationDto dto);
    void broadcastReadStateChanged(long userId);
}
