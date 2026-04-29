package com.slparcelauctions.backend.admin.audit;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.common.EnumCheckConstraintSync;

import lombok.RequiredArgsConstructor;

/**
 * Refreshes the {@code admin_actions_action_type_check} and
 * {@code admin_actions_target_type_check} constraints on startup so new values
 * added to {@link AdminActionType} or {@link AdminActionTargetType} do not
 * require manual DDL edits. See {@link EnumCheckConstraintSync} for the
 * rationale.
 */
@Component
@RequiredArgsConstructor
public class AdminActionTypeCheckConstraintInitializer {

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void refresh() {
        EnumCheckConstraintSync sync = new EnumCheckConstraintSync(jdbc);
        sync.sync("admin_actions", "action_type", AdminActionType.class);
        sync.sync("admin_actions", "target_type", AdminActionTargetType.class);
    }
}
