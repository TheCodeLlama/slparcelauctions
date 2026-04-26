package com.slparcelauctions.backend.notification;

import java.time.Clock;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "slpa.notifications.cleanup",
                        name = "enabled", havingValue = "true", matchIfMissing = false)
@Slf4j
public class NotificationCleanupJob {

    private final JdbcTemplate jdbc;
    private final NotificationCleanupProperties properties;
    private final Clock clock;

    @Scheduled(cron = "${slpa.notifications.cleanup.cron}")
    public void run() {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minusDays(properties.retentionDays());
        int totalDeleted = 0;
        int deletedThisChunk;
        do {
            deletedThisChunk = jdbc.update("""
                DELETE FROM notification
                WHERE id IN (
                    SELECT id FROM notification
                    WHERE read = true AND updated_at < ?
                    LIMIT ?
                )
                """, cutoff, properties.batchSize());
            totalDeleted += deletedThisChunk;
        } while (deletedThisChunk == properties.batchSize());
        log.info("Notification cleanup: deleted {} rows older than {}", totalDeleted, cutoff);
    }
}
