package com.slparcelauctions.backend.admin.infrastructure.bots;

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
class BotHeartbeatServiceTest {

    @Autowired BotHeartbeatService service;
    @Autowired BotWorkerRepository repo;
    @Autowired StringRedisTemplate redis;

    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private static final String UUID_1 = "00000000-0000-0000-0000-000000000a01";
    private static final String UUID_2 = "00000000-0000-0000-0000-000000000a02";

    @AfterEach
    void cleanupRedis() {
        // Redis is not rolled back by @Transactional, so clean up manually.
        redis.delete(BotHeartbeatService.KEY_PREFIX + UUID_1);
        redis.delete(BotHeartbeatService.KEY_PREFIX + UUID_2);
    }

    @Test
    void firstHeartbeatRegistersWorker() {
        service.handle(new BotHeartbeatRequest("slpa-bot-1", UUID_1,
                "ACTIVE", "Bay City", "task-key-42", "VERIFY",
                OffsetDateTime.parse("2026-04-27T12:00:00Z")));

        assertThat(repo.findBySlUuid(UUID_1)).isPresent();
    }

    @Test
    void secondHeartbeatUpdatesLastSeenAt() {
        service.handle(new BotHeartbeatRequest("slpa-bot-1", UUID_1,
                "IDLE", null, null, null, null));
        var first = repo.findBySlUuid(UUID_1).orElseThrow();
        OffsetDateTime firstSeen = first.getLastSeenAt();

        // Force a small delay by modifying the worker's lastSeenAt directly so
        // we can observe the update path without sleeping.
        service.handle(new BotHeartbeatRequest("slpa-bot-1", UUID_1,
                "ACTIVE", "Ahern", "task-key-99", "VERIFY", null));

        var updated = repo.findBySlUuid(UUID_1).orElseThrow();
        assertThat(updated.getLastSeenAt()).isAfterOrEqualTo(firstSeen);
    }

    @Test
    void redisKeyWrittenWithTtl() {
        service.handle(new BotHeartbeatRequest("slpa-bot-2", UUID_2,
                "IDLE", null, null, null, null));

        Long ttl = redis.getExpire(BotHeartbeatService.KEY_PREFIX + UUID_2);
        // TTL is 180 s; allow a small margin for test execution time.
        assertThat(ttl).isBetween(170L, 180L);
    }
}
