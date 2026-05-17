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

    private static final String DROP_OLD = """
            DROP INDEX IF EXISTS uq_auctions_parcel_locked_status
            """;

    // Status alone determines the parcel lock — no escrow join needed.
    // Every status here corresponds to a live phase of the listing
    // lifecycle (active auction, post-close transfer in flight, or disputed).
    // Terminal statuses (COMPLETED, CANCELLED, EXPIRED, FROZEN) release
    // the lock so the parcel can be re-listed. See spec §8.3.
    private static final String DDL = """
            CREATE UNIQUE INDEX IF NOT EXISTS uq_auctions_parcel_locked_status
              ON auctions(sl_parcel_uuid)
              WHERE status IN ('ACTIVE', 'TRANSFER_PENDING', 'DISPUTED')
            """;

    private final DataSource dataSource;

    @EventListener(ApplicationReadyEvent.class)
    public void createIndex() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // Drop the old parcel_id-based index if it still exists (idempotent
        // across schema wipes and fresh boots). The new index uses
        // sl_parcel_uuid — the denormalized column on auctions.
        jdbc.execute(DROP_OLD);
        jdbc.execute(DDL);
        log.info("Parcel locking partial unique index ensured (uq_auctions_parcel_locked_status)");
    }
}
