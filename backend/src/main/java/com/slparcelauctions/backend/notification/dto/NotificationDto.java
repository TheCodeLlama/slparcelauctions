package com.slparcelauctions.backend.notification.dto;

import com.slparcelauctions.backend.notification.Notification;
import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationGroup;
import java.time.OffsetDateTime;
import java.util.Map;

public record NotificationDto(
        Long id,
        NotificationCategory category,
        NotificationGroup group,
        String title,
        String body,
        Map<String, Object> data,
        Boolean read,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static NotificationDto from(Notification n) {
        return new NotificationDto(
                n.getId(), n.getCategory(), n.getCategory().getGroup(),
                n.getTitle(), n.getBody(), n.getData(), n.getRead(),
                n.getCreatedAt(), n.getUpdatedAt()
        );
    }
}
