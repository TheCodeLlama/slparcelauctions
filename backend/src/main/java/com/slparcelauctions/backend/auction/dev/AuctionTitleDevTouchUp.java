package com.slparcelauctions.backend.auction.dev;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Dev-profile-only one-shot that backfills any pre-Epic-07 auctions row
 * whose title is NULL with a placeholder. The title column landed as
 * NOT NULL in Epic 07 sub-spec 1; ddl-auto: update emits ADD COLUMN
 * NOT NULL which Postgres refuses without a DEFAULT, breaking startup
 * for any dev DB with pre-existing auction rows.
 *
 * <p>Production is unaffected (no live auctions before Epic 07 ships).
 * Removed after the first post-launch cleanup pass; tracked in
 * DEFERRED_WORK.md.
 */
@Component
@Profile("dev")
@RequiredArgsConstructor
@Slf4j
public class AuctionTitleDevTouchUp {

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        runOnce();
    }

    @Transactional
    public void runOnce() {
        int updated = jdbc.update(
                "UPDATE auctions SET title = 'Untitled (pre-Epic-07)' WHERE title IS NULL");
        if (updated > 0) {
            log.info("AuctionTitleDevTouchUp: backfilled {} pre-Epic-07 rows", updated);
        }
    }
}
