package com.slparcelauctions.backend.notification.slim;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Emits the partial unique index that backs the ON CONFLICT clause in
 * {@link SlImMessageDao}. Hibernate's ddl-auto cannot emit partial unique
 * indexes, so we add it via an {@code ApplicationReadyEvent} listener that
 * runs idempotent DDL.
 *
 * <p>Without this index the ON CONFLICT clause has nothing to match and every
 * upsert call becomes a plain INSERT — coalescing silently breaks.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SlImCoalesceIndexInitializer {

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void emit() {
        jdbc.execute("""
            CREATE UNIQUE INDEX IF NOT EXISTS uq_sl_im_pending_coalesce
            ON sl_im_message (user_id, coalesce_key)
            WHERE status = 'PENDING'
            """);
        log.info("SL IM partial unique index ensured: uq_sl_im_pending_coalesce");
    }
}
