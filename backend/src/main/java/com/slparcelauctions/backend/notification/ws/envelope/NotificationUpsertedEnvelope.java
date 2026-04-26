package com.slparcelauctions.backend.notification.ws.envelope;

import com.slparcelauctions.backend.notification.dto.NotificationDto;

public record NotificationUpsertedEnvelope(
    String type,
    boolean isUpdate,
    NotificationDto notification
) {
    public NotificationUpsertedEnvelope(boolean isUpdate, NotificationDto notification) {
        this("NOTIFICATION_UPSERTED", isUpdate, notification);
    }
}
