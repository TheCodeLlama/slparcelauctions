package com.slparcelauctions.backend.notification.slim;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Native-SQL DAO for the SL IM message upsert path.
 *
 * <p>Uses {@code ON CONFLICT (user_id, coalesce_key) WHERE status = 'PENDING' DO UPDATE}
 * to collapse repeated deliveries of the same logical event (e.g., repeated OUTBID
 * notifications on the same auction) into a single PENDING row that the dispatcher
 * delivers as one IM with the latest text.
 *
 * <p>The {@code xmax != 0} trick is a Postgres-native way to know whether INSERT
 * (xmax = 0) or UPDATE (xmax holds updating tx ID) executed without a second roundtrip.
 *
 * <p>The partial unique index {@code uq_sl_im_pending_coalesce} is created at
 * startup by {@link SlImCoalesceIndexInitializer} (Hibernate's ddl-auto cannot
 * emit partial indexes). Without that index the ON CONFLICT clause has nothing
 * to match and every call becomes a plain INSERT.
 *
 * <p>NULL coalesce_key never matches itself in Postgres unique constraints
 * (NULL ≠ NULL semantics), so the same query handles coalescing and
 * non-coalescing categories with no service-layer branching.
 */
@Component
@RequiredArgsConstructor
public class SlImMessageDao {

    private final JdbcTemplate jdbc;

    public record UpsertResult(
        long id,
        boolean wasUpdate,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
    ) {}

    public UpsertResult upsert(
        long userId,
        String avatarUuid,
        String messageText,
        String coalesceKey
    ) {
        String sql = """
            INSERT INTO sl_im_message
              (user_id, avatar_uuid, coalesce_key, message_text,
               status, attempts, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'PENDING', 0, now(), now())
            ON CONFLICT (user_id, coalesce_key) WHERE status = 'PENDING'
            DO UPDATE SET
              message_text = EXCLUDED.message_text,
              avatar_uuid  = EXCLUDED.avatar_uuid,
              updated_at   = now()
            RETURNING id, (xmax != 0) AS was_update, created_at, updated_at
            """;
        Map<String, Object> row = jdbc.queryForMap(
            sql, userId, avatarUuid, coalesceKey, messageText);
        return new UpsertResult(
            ((Number) row.get("id")).longValue(),
            (Boolean) row.get("was_update"),
            ((java.sql.Timestamp) row.get("created_at")).toInstant().atOffset(ZoneOffset.UTC),
            ((java.sql.Timestamp) row.get("updated_at")).toInstant().atOffset(ZoneOffset.UTC)
        );
    }
}
