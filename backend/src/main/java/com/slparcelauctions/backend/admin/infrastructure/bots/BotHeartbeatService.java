package com.slparcelauctions.backend.admin.infrastructure.bots;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class BotHeartbeatService {

    static final Duration TTL = Duration.ofSeconds(180);
    static final String KEY_PREFIX = "bot:heartbeat:";

    // ObjectMapper is thread-safe after construction. Inline construction
    // mirrors the pattern in NotificationDao and SearchRateLimitInterceptor —
    // the auto-configured bean is not reliably available to service-layer
    // components in this Spring Boot 4 / Java 26 setup.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final BotWorkerRepository workerRepo;
    private final StringRedisTemplate redis;
    private final Clock clock;

    @Transactional
    public void handle(BotHeartbeatRequest req) {
        // 1. Upsert registry row
        BotWorker worker = workerRepo.findBySlUuid(req.slUuid())
                .orElseGet(() -> BotWorker.builder()
                        .name(req.workerName())
                        .slUuid(req.slUuid())
                        .lastSeenAt(OffsetDateTime.now(clock))
                        .build());
        worker.setLastSeenAt(OffsetDateTime.now(clock));
        workerRepo.save(worker);

        // 2. Write Redis live state with TTL
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("workerName", req.workerName());
        state.put("sessionState", req.sessionState());
        state.put("currentRegion", req.currentRegion());
        state.put("currentTaskKey", req.currentTaskKey());
        state.put("currentTaskType", req.currentTaskType());
        state.put("lastClaimAt", req.lastClaimAt() != null ? req.lastClaimAt().toString() : null);
        state.put("reportedAt", OffsetDateTime.now(clock).toString());
        try {
            redis.opsForValue().set(KEY_PREFIX + req.slUuid(),
                    OBJECT_MAPPER.writeValueAsString(state), TTL);
        } catch (Exception e) {
            log.error("Failed to write bot heartbeat to Redis: {}", req.slUuid(), e);
        }
        log.debug("Bot heartbeat from {}", req.workerName());
    }
}
