package com.slparcelauctions.backend.auction.config;

import javax.sql.DataSource;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates the partial unique index that enforces "at most one auction per parcel
 * can be in a locking status at a time." Runs once per JVM boot via
 * {@link ApplicationReadyEvent} — after JPA has created/validated tables, so the
 * {@code auctions} table is guaranteed to exist. Idempotent (CREATE IF NOT EXISTS).
 *
 * <p>Keeps the DDL alongside the entity package rather than as a Flyway migration
 * per CONVENTIONS.md (entities are the source of truth).
 *
 * <p>LOCKING_STATUSES must match
 * {@link com.slparcelauctions.backend.auction.AuctionStatusConstants#LOCKING_STATUSES}.
 * If one changes, update the other.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ParcelLockingIndexInitializer {

    private static final String DDL = """
            CREATE UNIQUE INDEX IF NOT EXISTS uq_auctions_parcel_locked_status
              ON auctions(parcel_id)
              WHERE status IN ('ACTIVE', 'ENDED', 'ESCROW_PENDING',
                               'ESCROW_FUNDED', 'TRANSFER_PENDING', 'DISPUTED')
            """;

    private final DataSource dataSource;

    @EventListener(ApplicationReadyEvent.class)
    public void createIndex() {
        new JdbcTemplate(dataSource).execute(DDL);
        log.info("Parcel locking partial unique index ensured (uq_auctions_parcel_locked_status)");
    }
}
