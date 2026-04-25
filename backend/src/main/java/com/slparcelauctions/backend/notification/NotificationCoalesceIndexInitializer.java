package com.slparcelauctions.backend.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Ensures the partial unique index {@code uq_notification_unread_coalesce} exists
 * on startup. Hibernate's {@code ddl-auto: update} cannot emit partial indexes
 * (they require a {@code WHERE} clause), so we create it manually here after the
 * table is guaranteed to exist.
 *
 * <p>Without this index the {@code ON CONFLICT (user_id, coalesce_key) WHERE read = false}
 * clause in {@link NotificationDao} has no matching constraint to target — every
 * upsert silently becomes a plain INSERT and coalesce semantics break. See
 * {@code EscrowTransactionTypeCheckConstraintInitializer} for the initializer
 * pattern used throughout this project.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationCoalesceIndexInitializer {

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndex() {
        jdbc.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_unread_coalesce
                ON notification (user_id, coalesce_key)
                WHERE read = false
                """);
        log.info("notification: ensured partial unique index uq_notification_unread_coalesce");
    }
}
