package com.slparcelauctions.backend.notification.slim;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "slpa.notifications.sl-im.cleanup",
                       name = "enabled", havingValue = "true", matchIfMissing = false)
@Slf4j
public class SlImCleanupJob {

    private final JdbcTemplate jdbc;
    private final SlImCleanupProperties properties;
    private final Clock clock;

    @Scheduled(cron = "${slpa.notifications.sl-im.cleanup.cron}",
               zone = "America/Los_Angeles")
    public void run() {
        OffsetDateTime expiryCutoff = OffsetDateTime.now(clock)
            .minusHours(properties.expiryAfterHours());
        OffsetDateTime retentionCutoff = OffsetDateTime.now(clock)
            .minusDays(properties.retentionAfterDays());

        // Capture top users BEFORE the UPDATE so the WHERE clause still matches
        // the PENDING rows that are about to be transitioned to EXPIRED.
        List<Map<String, Object>> topUsers = jdbc.queryForList("""
            SELECT user_id, COUNT(*) AS n
            FROM sl_im_message
            WHERE status = 'PENDING' AND created_at < ?
            GROUP BY user_id
            ORDER BY n DESC
            LIMIT ?
            """, expiryCutoff, properties.topUsersInLog());

        int expired = jdbc.update("""
            UPDATE sl_im_message
            SET status = 'EXPIRED', updated_at = now()
            WHERE status = 'PENDING' AND created_at < ?
            """, expiryCutoff);

        int totalDeleted = 0;
        int deletedThisChunk;
        do {
            deletedThisChunk = jdbc.update("""
                DELETE FROM sl_im_message
                WHERE id IN (
                    SELECT id FROM sl_im_message
                    WHERE status IN ('DELIVERED','EXPIRED','FAILED')
                      AND updated_at < ?
                    LIMIT ?
                )
                """, retentionCutoff, properties.batchSize());
            totalDeleted += deletedThisChunk;
        } while (deletedThisChunk == properties.batchSize());

        String topUsersStr = topUsers.stream()
            .map(r -> r.get("user_id") + ":" + r.get("n"))
            .collect(Collectors.joining(", "));
        log.info("SL IM cleanup sweep: expired={} deleted={} expiry_cutoff={} retention_cutoff={} top_users=[{}]",
            expired, totalDeleted, expiryCutoff, retentionCutoff, topUsersStr);
    }
}
