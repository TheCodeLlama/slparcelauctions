package com.slparcelauctions.backend.admin.infrastructure.bots;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminBotPoolService {

    private static final String KEY_PREFIX = "bot:heartbeat:";

    // ObjectMapper is thread-safe after construction. Inline construction
    // mirrors the pattern in NotificationDao and SearchRateLimitInterceptor —
    // the auto-configured bean is not reliably available to service-layer
    // components in this Spring Boot 4 / Java 26 setup.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final BotWorkerRepository workerRepo;
    private final StringRedisTemplate redis;

    @Transactional(readOnly = true)
    public List<BotPoolHealthRow> getHealth() {
        return workerRepo.findAll().stream()
                .sorted(Comparator.comparing(BotWorker::getLastSeenAt).reversed())
                .map(this::toRow)
                .toList();
    }

    private BotPoolHealthRow toRow(BotWorker w) {
        String json = redis.opsForValue().get(KEY_PREFIX + w.getSlUuid());
        if (json == null) {
            return new BotPoolHealthRow(
                    w.getId(), w.getName(), w.getSlUuid(),
                    w.getFirstSeenAt(), w.getLastSeenAt(),
                    null, null, null, null, false);
        }
        try {
            Map<String, Object> state = OBJECT_MAPPER.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            return new BotPoolHealthRow(
                    w.getId(), w.getName(), w.getSlUuid(),
                    w.getFirstSeenAt(), w.getLastSeenAt(),
                    (String) state.get("sessionState"),
                    (String) state.get("currentRegion"),
                    (String) state.get("currentTaskKey"),
                    (String) state.get("currentTaskType"),
                    true);
        } catch (Exception e) {
            log.warn("Failed to parse bot heartbeat JSON for {}: {}", w.getSlUuid(), e.getMessage());
            return new BotPoolHealthRow(
                    w.getId(), w.getName(), w.getSlUuid(),
                    w.getFirstSeenAt(), w.getLastSeenAt(),
                    null, null, null, null, false);
        }
    }
}
