package com.slparcelauctions.backend.escrow.command;

import com.slparcelauctions.backend.common.EnumCheckConstraintSync;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Refreshes the {@code terminal_commands_purpose_check} constraint on startup
 * so that new values added to {@link TerminalCommandPurpose} (e.g.,
 * {@code WALLET_WITHDRAWAL} added by the wallet model) do not require a
 * manual Flyway migration. Without this, the V1 schema's CHECK constraint
 * pins the allowed values to the original three (AUCTION_ESCROW,
 * LISTING_FEE_REFUND, ADMIN_WITHDRAWAL) and any insert with WALLET_WITHDRAWAL
 * fails with a CHECK constraint violation.
 */
@Component
@RequiredArgsConstructor
public class TerminalCommandPurposeCheckConstraintInitializer {

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void refresh() {
        new EnumCheckConstraintSync(jdbc).sync(
                "terminal_commands", "purpose", TerminalCommandPurpose.class);
    }
}
