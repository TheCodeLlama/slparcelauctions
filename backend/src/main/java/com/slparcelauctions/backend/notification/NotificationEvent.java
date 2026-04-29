package com.slparcelauctions.backend.notification;

import java.util.Map;

public record NotificationEvent(
        long userId,
        NotificationCategory category,
        String title,
        String body,
        Map<String, Object> data,
        String coalesceKey
) {}
