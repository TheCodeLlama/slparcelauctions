package com.slparcelauctions.backend.bot;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.common.EnumCheckConstraintSync;

import lombok.RequiredArgsConstructor;

/**
 * Refreshes the {@code bot_tasks_task_type_check} constraint on startup so
 * future {@link BotTaskType} enum values pass the CHECK without a manual DDL
 * edit. The escrow transfer-split spec (2026-05-17) reintroduced
 * {@code VERIFY_SELL_TO}; the initializer (re)creates the constraint
 * enumerating the current enum values, and stays in place so additional
 * task types can plug in without re-deriving the constraint plumbing.
 * See {@link EnumCheckConstraintSync}.
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
