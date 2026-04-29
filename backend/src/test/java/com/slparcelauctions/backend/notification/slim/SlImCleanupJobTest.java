package com.slparcelauctions.backend.notification.slim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=true",
    "slpa.notifications.sl-im.cleanup.cron=0 0 0 * * *",
    "slpa.notifications.sl-im.cleanup.expiry-after-hours=48",
    "slpa.notifications.sl-im.cleanup.retention-after-days=30",
    "slpa.notifications.sl-im.cleanup.batch-size=1000",
    "slpa.notifications.sl-im.cleanup.top-users-in-log=10",
    "slpa.escrow.scheduler.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.refresh-token-cleanup.enabled=false",
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false"
})
class SlImCleanupJobTest {

    @Autowired SlImCleanupJob job;
    @Autowired SlImMessageDao dao;
    @Autowired SlImMessageRepository repo;
    @Autowired UserRepository userRepo;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean Clock clock;

    @Test
    void run_expiresPendingOlderThan48h_keepsNewer_deletesTerminalOlderThan30d() {
        when(clock.instant()).thenReturn(Instant.parse("2026-04-26T08:30:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        User u = userRepo.save(User.builder()
            .email("u-" + UUID.randomUUID() + "@test.local")
            .passwordHash("hash").build());
        String avatar = UUID.randomUUID().toString();

        // 49h-old PENDING — should expire
        var stalePending = dao.upsert(u.getId(), avatar, "[SLPA] stale", "k1");
        forceCreatedAt(stalePending.id(), "2026-04-24T07:00:00Z");

        // 47h-old PENDING — should stay
        var freshPending = dao.upsert(u.getId(), avatar, "[SLPA] fresh", "k2");
        forceCreatedAt(freshPending.id(), "2026-04-24T10:00:00Z");

        // 31d-old DELIVERED — should be deleted
        var oldDelivered = dao.upsert(u.getId(), avatar, "[SLPA] old-d", "k3");
        forceStatusAndUpdatedAt(oldDelivered.id(), "DELIVERED", "2026-03-25T08:30:00Z");

        // 31d-old FAILED — should be deleted
        var oldFailed = dao.upsert(u.getId(), avatar, "[SLPA] old-f", "k4");
        forceStatusAndUpdatedAt(oldFailed.id(), "FAILED", "2026-03-25T08:30:00Z");

        // 31d-old EXPIRED — should be deleted
        var oldExpired = dao.upsert(u.getId(), avatar, "[SLPA] old-e", "k5");
        forceStatusAndUpdatedAt(oldExpired.id(), "EXPIRED", "2026-03-25T08:30:00Z");

        // 29d-old DELIVERED — should stay
        var newishDelivered = dao.upsert(u.getId(), avatar, "[SLPA] newish", "k6");
        forceStatusAndUpdatedAt(newishDelivered.id(), "DELIVERED", "2026-03-29T08:30:00Z");

        job.run();

        // stalePending → EXPIRED
        assertThat(repo.findById(stalePending.id()).orElseThrow().getStatus())
            .isEqualTo(SlImMessageStatus.EXPIRED);
        // freshPending → still PENDING
        assertThat(repo.findById(freshPending.id()).orElseThrow().getStatus())
            .isEqualTo(SlImMessageStatus.PENDING);
        // 31d terminal-status rows deleted
        assertThat(repo.findById(oldDelivered.id())).isEmpty();
        assertThat(repo.findById(oldFailed.id())).isEmpty();
        assertThat(repo.findById(oldExpired.id())).isEmpty();
        // 29d DELIVERED — still present
        assertThat(repo.findById(newishDelivered.id())).isPresent();
    }

    @Test
    void run_logsInfoLineWithExpectedFields() {
        when(clock.instant()).thenReturn(Instant.parse("2026-04-26T08:30:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        User u = userRepo.save(User.builder()
            .email("u-" + UUID.randomUUID() + "@test.local")
            .passwordHash("hash").build());
        String avatar = UUID.randomUUID().toString();

        var p = dao.upsert(u.getId(), avatar, "[SLPA] stale", null);
        forceCreatedAt(p.id(), "2026-04-24T07:00:00Z");

        Logger logger = (Logger) LoggerFactory.getLogger(SlImCleanupJob.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            job.run();

            assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(line ->
                    line.contains("SL IM cleanup sweep")
                        && line.contains("expired=1")
                        && line.contains("deleted=")
                        && line.contains("expiry_cutoff=")
                        && line.contains("retention_cutoff=")
                        && line.contains("top_users=[" + u.getId() + ":1"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void run_chunkedDelete_handlesLargeBacklog() {
        when(clock.instant()).thenReturn(Instant.parse("2026-04-26T08:30:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        User u = userRepo.save(User.builder()
            .email("u-" + UUID.randomUUID() + "@test.local")
            .passwordHash("hash").build());
        String avatar = UUID.randomUUID().toString();

        // Seed 25 deletable DELIVERED rows; with batch=1000 (default), the loop
        // runs once and clears all 25, exercising the do-while exit condition.
        for (int i = 0; i < 25; i++) {
            var r = dao.upsert(u.getId(), avatar, "[SLPA] msg-" + i, "k" + i);
            forceStatusAndUpdatedAt(r.id(), "DELIVERED", "2026-03-20T00:00:00Z");
        }

        job.run();

        // All 25 rows deleted (batch covers them in one shot).
        long remaining = repo.findAll().stream()
            .filter(m -> m.getUserId().equals(u.getId())).count();
        assertThat(remaining).isZero();
    }

    private void forceCreatedAt(long id, String iso) {
        jdbc.update("UPDATE sl_im_message SET created_at = ? WHERE id = ?",
            OffsetDateTime.parse(iso), id);
    }

    private void forceStatusAndUpdatedAt(long id, String status, String iso) {
        jdbc.update("UPDATE sl_im_message SET status = ?::varchar, updated_at = ? WHERE id = ?",
            status, OffsetDateTime.parse(iso), id);
    }
}
