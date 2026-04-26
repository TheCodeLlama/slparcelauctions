package com.slparcelauctions.backend.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Native-SQL DAO for the notification upsert path.
 *
 * <p>Uses {@code ON CONFLICT (user_id, coalesce_key) WHERE read = false DO UPDATE}
 * to collapse multiple deliveries of the same logical event (e.g., repeated
 * OUTBID notifications on the same auction) into a single unread row.
 *
 * <p>The {@code xmax != 0} trick is a Postgres-native way to know which
 * branch executed — INSERT ({@code xmax = 0}) vs UPDATE ({@code xmax} holds
 * the updating transaction ID) — without a second round-trip.
 *
 * <p>The partial unique index {@code uq_notification_unread_coalesce} is
 * created at startup by {@link NotificationCoalesceIndexInitializer}. Without
 * it the {@code ON CONFLICT} clause has nothing to match and every call
 * becomes a plain INSERT.
 *
 * <p>{@link ObjectMapper} is constructed inline (not injected) following the
 * pattern established by {@code SearchRateLimitInterceptor} — the
 * auto-configured bean is not reliably available to non-web-layer components
 * in this Spring Boot 4 / Java 26 setup.
 */
@Component
@RequiredArgsConstructor
public class NotificationDao {

    private final JdbcTemplate jdbc;

    // ObjectMapper is thread-safe after construction. Inline construction
    // mirrors the pattern in SearchRateLimitInterceptor and JwtAuthenticationEntryPoint.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public record UpsertResult(
            long id,
            boolean wasUpdate,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}

    public UpsertResult upsert(
            long userId,
            NotificationCategory category,
            String title,
            String body,
            Map<String, Object> data,
            String coalesceKey
    ) {
        String dataJson;
        try {
            dataJson = OBJECT_MAPPER.writeValueAsString(data == null ? Map.of() : data);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("notification data not serializable", e);
        }

        String sql = """
                INSERT INTO notification
                  (user_id, category, title, body, data, coalesce_key, read, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, false, now(), now())
                ON CONFLICT (user_id, coalesce_key) WHERE read = false
                DO UPDATE SET
                  title = EXCLUDED.title,
                  body = EXCLUDED.body,
                  data = EXCLUDED.data,
                  updated_at = now()
                RETURNING id, (xmax != 0) AS was_update, created_at, updated_at
                """;

        Map<String, Object> row = jdbc.queryForMap(
                sql, userId, category.name(), title, body, dataJson, coalesceKey);

        return new UpsertResult(
                ((Number) row.get("id")).longValue(),
                (Boolean) row.get("was_update"),
                ((java.sql.Timestamp) row.get("created_at")).toInstant().atOffset(ZoneOffset.UTC),
                ((java.sql.Timestamp) row.get("updated_at")).toInstant().atOffset(ZoneOffset.UTC)
        );
    }
}
