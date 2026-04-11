package com.slparcelauctions.backend.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;

/**
 * Scheduled cleanup of expired and long-revoked refresh token rows. Runs daily at 03:30 server
 * time and deletes rows where either {@code expires_at} or {@code revoked_at} is more than 30
 * days in the past. Keeps the table from growing unbounded while retaining a month of audit
 * history for security investigations.
 *
 * <p>Disabled in integration tests via {@code auth.cleanup.enabled=false} on the test base class.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "auth.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class RefreshTokenCleanupJob {

    private static final int RETENTION_DAYS = 30;

    private final RefreshTokenRepository refreshTokenRepository;

    @Scheduled(cron = "0 30 3 * * *")  // 03:30 server local time, daily
    @Transactional
    public void cleanupExpiredTokens() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(RETENTION_DAYS);
        int deleted = refreshTokenRepository.deleteOldRows(cutoff);
        log.info("Refresh token cleanup: deleted {} rows older than {}", deleted, cutoff);
    }
}
