package com.slparcelauctions.backend.notification;

import java.util.Map;

/**
 * In-memory event passed from {@link NotificationPublisher} implementations to
 * {@link NotificationService#publish}.
 *
 * <p>{@code linkUrl} is the optional row-click target rendered by the bell and
 * the {@code /notifications} page. When null the row is non-clickable.
 * Populated for categories where a deeplink target is meaningful (e.g.,
 * realty-group invitations send the recipient to
 * {@code /groups/invitations/me} per design §5.8). The 6-arg constructor is
 * preserved as a convenience for the many call sites that do not yet supply a
 * link target; they default to {@code null}.
 */
public record NotificationEvent(
        long userId,
        NotificationCategory category,
        String title,
        String body,
        Map<String, Object> data,
        String coalesceKey,
        String linkUrl
) {
    public NotificationEvent(
            long userId,
            NotificationCategory category,
            String title,
            String body,
            Map<String, Object> data,
            String coalesceKey
    ) {
        this(userId, category, title, body, data, coalesceKey, null);
    }
}
