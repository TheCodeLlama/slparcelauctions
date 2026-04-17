package com.slparcelauctions.backend.auction.config;

import javax.sql.DataSource;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Keeps the {@code auctions_status_check} CHECK constraint in sync with the
 * current {@link com.slparcelauctions.backend.auction.AuctionStatus} enum.
 *
 * <p>Hibernate's {@code ddl-auto=update} creates a CHECK constraint listing
 * the enum values at first-boot, but does NOT refresh that constraint when a
 * new value is added in a later release. Without this initializer, adding
 * {@code SUSPENDED} (Task 4) fails at INSERT/UPDATE time because the
 * constraint still lists only the pre-SUSPENDED values.
 *
 * <p>Runs once per JVM boot via {@link ApplicationReadyEvent} — after JPA has
 * created/validated tables. Idempotent: drops the constraint if present and
 * re-adds it with the full enum set.
 *
 * <p>This matches the pattern in {@link ParcelLockingIndexInitializer}. When
 * the enum changes, update this DDL to match.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuctionStatusCheckConstraintInitializer {

    private static final String DROP = "ALTER TABLE auctions DROP CONSTRAINT IF EXISTS auctions_status_check";
    private static final String ADD = """
            ALTER TABLE auctions ADD CONSTRAINT auctions_status_check
              CHECK (status IN (
                'DRAFT', 'DRAFT_PAID', 'VERIFICATION_PENDING', 'VERIFICATION_FAILED',
                'ACTIVE', 'ENDED', 'ESCROW_PENDING', 'ESCROW_FUNDED',
                'TRANSFER_PENDING', 'COMPLETED', 'CANCELLED', 'EXPIRED',
                'DISPUTED', 'SUSPENDED'
              ))
            """;

    private final DataSource dataSource;

    @EventListener(ApplicationReadyEvent.class)
    public void syncConstraint() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(DROP);
        jdbc.execute(ADD);
        log.info("Refreshed auctions_status_check constraint to match AuctionStatus enum");
    }
}
