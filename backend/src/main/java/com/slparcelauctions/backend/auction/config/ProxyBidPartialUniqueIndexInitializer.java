package com.slparcelauctions.backend.auction.config;

import javax.sql.DataSource;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.auction.ProxyBid;
import com.slparcelauctions.backend.auction.ProxyBidStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Ensures the partial unique index that enforces "at most one
 * {@link ProxyBidStatus#ACTIVE} {@link ProxyBid} per {@code (auction, user)}"
 * exists on the {@code proxy_bids} table.
 *
 * <p>Hibernate's {@code ddl-auto=update} cannot express partial indexes (the
 * {@code WHERE status = 'ACTIVE'} predicate), so we add it explicitly at boot
 * via {@link ApplicationReadyEvent} — after JPA has created/validated the
 * {@code proxy_bids} table. Idempotent: uses {@code CREATE UNIQUE INDEX IF NOT
 * EXISTS}.
 *
 * <p>Mirrors the boot-time DDL pattern used by
 * {@link AuctionStatusCheckConstraintInitializer}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProxyBidPartialUniqueIndexInitializer {

    private static final String CREATE_INDEX = """
            CREATE UNIQUE INDEX IF NOT EXISTS proxy_bids_one_active_per_user
              ON proxy_bids (auction_id, user_id)
              WHERE status = 'ACTIVE'
            """;

    private final DataSource dataSource;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndex() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(CREATE_INDEX);
        log.info("Ensured partial unique index on proxy_bids (auction_id, user_id) WHERE status='ACTIVE'");
    }
}
