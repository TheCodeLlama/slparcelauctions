package com.slparcelauctions.backend.admin.infrastructure.bots;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.notification.NotificationWsBroadcasterPort;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.bot-pool-health-log.enabled=false"
})
@Transactional
class AdminBotPoolServiceTest {

    // Inline ObjectMapper, same pattern as AdminBotPoolService itself — the
    // auto-configured bean is not reliably available in all Spring Boot 4 test
    // contexts (see NotificationDao comments).
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired AdminBotPoolService service;
    @Autowired BotWorkerRepository workerRepo;
    @Autowired StringRedisTemplate redis;

    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private static final String UUID_A = "00000000-0000-0000-0000-000000000b01";
    private static final String UUID_B = "00000000-0000-0000-0000-000000000b02";
    private static final String UUID_C = "00000000-0000-0000-0000-000000000b03";

    @AfterEach
    void cleanupRedis() {
        redis.delete(BotHeartbeatService.KEY_PREFIX + UUID_A);
        redis.delete(BotHeartbeatService.KEY_PREFIX + UUID_B);
        redis.delete(BotHeartbeatService.KEY_PREFIX + UUID_C);
    }

    @Test
    void missingRedisKey_returnsIsAlive_false() {
        OffsetDateTime now = OffsetDateTime.now();
        workerRepo.save(BotWorker.builder()
                .name("bot-dead")
                .slUuid(UUID_A)
                .lastSeenAt(now.minusMinutes(10))
                .build());

        // No Redis key written — bot is stale
        List<BotPoolHealthRow> rows = service.getHealth();

        BotPoolHealthRow row = rows.stream()
                .filter(r -> UUID_A.equals(r.slUuid()))
                .findFirst()
                .orElseThrow();
        assertThat(row.isAlive()).isFalse();
        assertThat(row.sessionState()).isNull();
    }

    @Test
    void presentRedisKey_returnsIsAlive_true_withState() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        workerRepo.save(BotWorker.builder()
                .name("bot-alive")
                .slUuid(UUID_B)
                .lastSeenAt(now.minusSeconds(30))
                .build());

        Map<String, Object> state = Map.of(
                "workerName", "bot-alive",
                "sessionState", "ACTIVE",
                "currentRegion", "Ahern",
                "currentTaskKey", "task-99",
                "currentTaskType", "VERIFY",
                "reportedAt", now.toString()
        );
        redis.opsForValue().set(
                BotHeartbeatService.KEY_PREFIX + UUID_B,
                OBJECT_MAPPER.writeValueAsString(state));

        List<BotPoolHealthRow> rows = service.getHealth();

        BotPoolHealthRow row = rows.stream()
                .filter(r -> UUID_B.equals(r.slUuid()))
                .findFirst()
                .orElseThrow();
        assertThat(row.isAlive()).isTrue();
        assertThat(row.sessionState()).isEqualTo("ACTIVE");
        assertThat(row.currentRegion()).isEqualTo("Ahern");
        assertThat(row.currentTaskKey()).isEqualTo("task-99");
        assertThat(row.currentTaskType()).isEqualTo("VERIFY");
    }

    @Test
    void multipleWorkers_sortedByLastSeenAt_descending() {
        OffsetDateTime older = OffsetDateTime.now().minusMinutes(30);
        OffsetDateTime newer = OffsetDateTime.now().minusMinutes(5);

        workerRepo.save(BotWorker.builder()
                .name("bot-old")
                .slUuid(UUID_A)
                .lastSeenAt(older)
                .build());
        workerRepo.save(BotWorker.builder()
                .name("bot-new")
                .slUuid(UUID_C)
                .lastSeenAt(newer)
                .build());

        List<BotPoolHealthRow> rows = service.getHealth().stream()
                .filter(r -> r.slUuid().equals(UUID_A) || r.slUuid().equals(UUID_C))
                .toList();

        assertThat(rows).hasSizeGreaterThanOrEqualTo(2);
        // First element should be the more-recently-seen bot (UUID_C)
        int idxNew = rows.stream().map(BotPoolHealthRow::slUuid)
                .toList().indexOf(UUID_C);
        int idxOld = rows.stream().map(BotPoolHealthRow::slUuid)
                .toList().indexOf(UUID_A);
        assertThat(idxNew).isLessThan(idxOld);
    }
}
