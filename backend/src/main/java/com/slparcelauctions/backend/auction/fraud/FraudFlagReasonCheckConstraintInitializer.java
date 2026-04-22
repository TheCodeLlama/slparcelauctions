package com.slparcelauctions.backend.auction.fraud;

import javax.sql.DataSource;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Keeps the {@code fraud_flags_reason_check} CHECK constraint in sync with
 * the current {@link FraudFlagReason} enum.
 *
 * <p>Hibernate's {@code ddl-auto=update} created the CHECK constraint with
 * the initial three reasons but does NOT refresh it when new values land.
 * Escrow Task 5 adds {@link FraudFlagReason#ESCROW_WRONG_PAYER}; without
 * this initializer, payment wrong-payer inserts fail with a constraint
 * violation against the stale constraint text.
 *
 * <p>Runs once per JVM boot via {@link ApplicationReadyEvent} — after JPA
 * has created/validated tables. Idempotent: drops the constraint if present
 * and re-adds it with the full enum set.
 *
 * <p>This mirrors
 * {@link com.slparcelauctions.backend.auction.config.AuctionStatusCheckConstraintInitializer}.
 * When the enum changes, update this DDL to match.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FraudFlagReasonCheckConstraintInitializer {

    private static final String DROP =
            "ALTER TABLE fraud_flags DROP CONSTRAINT IF EXISTS fraud_flags_reason_check";
    private static final String ADD = """
            ALTER TABLE fraud_flags ADD CONSTRAINT fraud_flags_reason_check
              CHECK (reason IN (
                'OWNERSHIP_CHANGED_TO_UNKNOWN',
                'PARCEL_DELETED_OR_MERGED',
                'WORLD_API_FAILURE_THRESHOLD',
                'ESCROW_WRONG_PAYER'
              ))
            """;

    private final DataSource dataSource;

    @EventListener(ApplicationReadyEvent.class)
    public void syncConstraint() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(DROP);
        jdbc.execute(ADD);
        log.info("Refreshed fraud_flags_reason_check constraint to match FraudFlagReason enum");
    }
}
