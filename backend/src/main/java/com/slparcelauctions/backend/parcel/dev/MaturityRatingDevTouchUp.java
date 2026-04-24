package com.slparcelauctions.backend.parcel.dev;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

/**
 * Dev-profile-only one-shot that normalizes legacy {@code maturity_rating}
 * values in the parcels table. Runs on every dev startup; idempotent.
 *
 * <p>Existing rows follow the SL XML casing ("PG", "Mature", "Adult"). The
 * Epic 07 search filter endpoint accepts only the canonical
 * GENERAL/MODERATE/ADULT values, so any legacy row would silently drop out
 * of every maturity filter until touched.
 *
 * <p>Removed from the codebase after the first post-launch cleanup pass,
 * tracked in {@code DEFERRED_WORK.md}. Production deployments skip this
 * bean entirely via {@code @Profile("dev")}.
 *
 * <p>The third UPDATE normalizes "Adult" -> "ADULT" without rewriting
 * already-correct "ADULT" rows.
 *
 * <p>Note on transaction propagation: {@code runOnce()} uses the default
 * {@code @Transactional} (joins any caller transaction). REQUIRES_NEW was
 * considered to make the dev startup write commit independently, but the
 * production code path runs from {@link ApplicationReadyEvent} where there
 * is no surrounding transaction — so REQUIRES_NEW gave no production
 * benefit, and made integration tests fail because the seed rows in the
 * outer test transaction are invisible to a new transaction.
 */
@Component
@Profile("dev")
@Slf4j
public class MaturityRatingDevTouchUp {

    private final JdbcTemplate jdbc;

    @Autowired
    public MaturityRatingDevTouchUp(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        runOnce();
    }

    @Transactional
    public void runOnce() {
        int general = jdbc.update(
                "UPDATE parcels SET maturity_rating = 'GENERAL' "
                        + "WHERE UPPER(maturity_rating) = 'PG'");
        int moderate = jdbc.update(
                "UPDATE parcels SET maturity_rating = 'MODERATE' "
                        + "WHERE UPPER(maturity_rating) = 'MATURE'");
        int adult = jdbc.update(
                "UPDATE parcels SET maturity_rating = 'ADULT' "
                        + "WHERE UPPER(maturity_rating) = 'ADULT' "
                        + "AND maturity_rating <> 'ADULT'");

        log.info("MaturityRatingDevTouchUp: GENERAL={}, MODERATE={}, ADULT={}",
                general, moderate, adult);
    }
}
