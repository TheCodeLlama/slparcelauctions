package com.slparcelauctions.backend.bot;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.common.EnumCheckConstraintSync;

import lombok.RequiredArgsConstructor;

/**
 * Refreshes the {@code bot_tasks_task_type_check} constraint on startup so
 * MONITOR_AUCTION / MONITOR_ESCROW values added in Epic 06 pass the CHECK
 * without a manual DDL edit. See {@link EnumCheckConstraintSync}.
 */
@Component
@RequiredArgsConstructor
public class BotTaskTypeCheckConstraintInitializer {

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void refresh() {
        new EnumCheckConstraintSync(jdbc).sync("bot_tasks", "task_type", BotTaskType.class);
    }
}
