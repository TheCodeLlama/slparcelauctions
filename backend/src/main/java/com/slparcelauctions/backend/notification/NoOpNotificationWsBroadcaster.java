package com.slparcelauctions.backend.notification;

import com.slparcelauctions.backend.notification.NotificationDao.UpsertResult;
import com.slparcelauctions.backend.notification.dto.NotificationDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * No-op fallback implementation of {@link NotificationWsBroadcasterPort}.
 * Active whenever no other bean implementing {@link NotificationWsBroadcasterPort}
 * has been registered (i.e., before Task 4 ships the real STOMP broadcaster).
 *
 * <p>Task 4 will register its concrete implementation marked {@code @Primary};
 * tests override this bean with {@code @MockitoBean}.
 */
@Component
@Slf4j
public class NoOpNotificationWsBroadcaster implements NotificationWsBroadcasterPort {

    @Override
    public void broadcastUpsert(long userId, UpsertResult result, NotificationDto dto) {
        log.debug("notification: no-op broadcastUpsert userId={} notifPublicId={}", userId, result.publicId());
    }

    @Override
    public void broadcastReadStateChanged(long userId) {
        log.debug("notification: no-op broadcastReadStateChanged userId={}", userId);
    }
}
