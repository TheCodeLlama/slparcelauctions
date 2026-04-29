package com.slparcelauctions.backend.notification;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.common.EnumCheckConstraintSync;

import lombok.RequiredArgsConstructor;

/**
 * Refreshes the {@code notification_category_check} constraint on startup so
 * new values added to {@link NotificationCategory} do not require manual DDL
 * edits. See {@link EnumCheckConstraintSync} for the rationale.
 */
@Component
@RequiredArgsConstructor
public class NotificationCategoryCheckConstraintInitializer {

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void refresh() {
        new EnumCheckConstraintSync(jdbc).sync("notification", "category", NotificationCategory.class);
    }
}
