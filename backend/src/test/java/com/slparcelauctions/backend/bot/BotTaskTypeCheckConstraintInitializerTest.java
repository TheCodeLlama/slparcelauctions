package com.slparcelauctions.backend.bot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies that the bot_tasks CHECK constraints cover all enum values after
 * ApplicationReadyEvent fires. Regression guard for Hibernate ddl-auto:update
 * not widening CHECKs on enum value additions (see FOOTGUNS).
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
                .contains("VERIFY")
                .contains("MONITOR_AUCTION")
                .contains("MONITOR_ESCROW");
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
