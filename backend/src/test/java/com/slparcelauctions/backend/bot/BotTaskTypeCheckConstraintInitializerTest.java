package com.slparcelauctions.backend.bot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies that the bot_tasks CHECK constraints cover all enum values after
 * ApplicationReadyEvent fires. The {@link BotTaskType} enum carries
 * {@code VERIFY_SELL_TO} (spec 2026-05-17), {@code VERIFY_BUY_OWNER}, and
 * {@code SCAN_PARCEL} (spec 2026-05-23), so the type-check constraint is
 * (re)created on startup and must enumerate all three values. The status
 * constraint still covers every {@link BotTaskStatus} value.
 */
@SpringBootTest
@ActiveProfiles("dev")
class BotTaskTypeCheckConstraintInitializerTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void taskTypeCheckConstraintCoversAllEnumValues() {
        String constraintDef = jdbc.queryForObject(
                """
                SELECT pg_get_constraintdef(oid)
                  FROM pg_constraint
                 WHERE conname = 'bot_tasks_task_type_check'
                """,
                String.class);
        assertThat(constraintDef)
                .contains("VERIFY_SELL_TO")
                .contains("VERIFY_BUY_OWNER")
                .contains("SCAN_PARCEL");
    }

    @Test
    void statusCheckConstraintCoversAllEnumValues() {
        String constraintDef = jdbc.queryForObject(
                """
                SELECT pg_get_constraintdef(oid)
                  FROM pg_constraint
                 WHERE conname = 'bot_tasks_status_check'
                """,
                String.class);
        assertThat(constraintDef)
                .contains("PENDING")
                .contains("IN_PROGRESS")
                .contains("COMPLETED")
                .contains("FAILED")
                .contains("CANCELLED");
    }
}
