package com.slparcelauctions.backend.auction.fraud;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.common.EnumCheckConstraintSync;

import lombok.RequiredArgsConstructor;

/**
 * Refreshes the {@code fraud_flags_reason_check} constraint on startup so new
 * values added to {@link FraudFlagReason} do not require manual DDL edits.
 * See {@link EnumCheckConstraintSync} for the rationale.
 */
@Component
@RequiredArgsConstructor
public class FraudFlagReasonCheckConstraintInitializer {

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void refresh() {
        new EnumCheckConstraintSync(jdbc).sync("fraud_flags", "reason", FraudFlagReason.class);
    }
}
