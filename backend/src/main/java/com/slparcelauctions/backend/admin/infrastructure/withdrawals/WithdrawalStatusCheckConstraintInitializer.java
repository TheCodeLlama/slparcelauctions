package com.slparcelauctions.backend.admin.infrastructure.withdrawals;

import com.slparcelauctions.backend.common.EnumCheckConstraintSync;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Refreshes the {@code withdrawals_status_check} constraint on startup so
 * that new values added to {@link WithdrawalStatus} do not require manual
 * DDL edits. Follows the same pattern as
 * {@link com.slparcelauctions.backend.admin.audit.AdminActionTypeCheckConstraintInitializer}.
 */
@Component
@RequiredArgsConstructor
public class WithdrawalStatusCheckConstraintInitializer {

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void refresh() {
        new EnumCheckConstraintSync(jdbc).sync(
                "withdrawals", "status", WithdrawalStatus.class);
    }
}
