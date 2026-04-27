package com.slparcelauctions.backend.notification.slim;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
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
class SlImCoalesceIndexInitializerTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired SlImCoalesceIndexInitializer initializer;

    @Test
    void partialUniqueIndexIsPresentAfterStartup() {
        // ApplicationReadyEvent fires during @SpringBootTest startup.
        Integer count = jdbc.queryForObject("""
            SELECT count(*)
            FROM pg_indexes
            WHERE tablename = 'sl_im_message'
              AND indexname = 'uq_sl_im_pending_coalesce'
            """, Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void emit_isIdempotent() {
        // Calling emit() a second time should not throw.
        initializer.emit();
        Integer count = jdbc.queryForObject("""
            SELECT count(*)
            FROM pg_indexes
            WHERE tablename = 'sl_im_message'
              AND indexname = 'uq_sl_im_pending_coalesce'
            """, Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
