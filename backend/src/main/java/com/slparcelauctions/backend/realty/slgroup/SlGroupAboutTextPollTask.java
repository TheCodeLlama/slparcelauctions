package com.slparcelauctions.backend.realty.slgroup;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.sl.SlWorldApiClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sub-project E spec §7.2 -- previously polled pending {@link RealtyGroupSlGroup} rows on a
 * 5-minute cadence and looked for the verification code in the SL group About text. Sub-project
 * F retires the ABOUT_TEXT verification path: V28 dropped the {@code last_polled_at} and
 * {@code poll_attempts} columns this task depended on and tightened the {@code verified_via}
 * CHECK constraint to only admit {@code FOUNDER_TERMINAL}.
 *
 * <p>This class is reduced to a near-no-op until Task 21 deletes it entirely along with its
 * test, the dev controller hook, and the {@link SlGroupVerifyMethod#ABOUT_TEXT} enum value.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SlGroupAboutTextPollTask {

    static final Duration POLL_INTERVAL = Duration.ofMinutes(5);

    private final RealtyGroupSlGroupRepository repo;
    private final SlWorldApiClient worldApi;
    private final Clock clock;

    @Scheduled(fixedDelay = 5 * 60 * 1000L)
    @Transactional
    public void runScheduled() {
        // TODO Task 21 will delete this class. The repository's findDueForAboutTextPoll
        // now returns an empty list (the partial index it relied on, and the columns its
        // JPQL referenced, were both removed by V28), so the loop body never runs.
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime cutoff = now.minus(POLL_INTERVAL);
        repo.findDueForAboutTextPoll(now, cutoff);
    }

    /**
     * Polls a single row immediately. Retained as a stub so the existing dev controller
     * hook and tests can keep compiling until Task 21 deletes them along with this class.
     */
    @Transactional
    public RealtyGroupSlGroup pollOne(RealtyGroupSlGroup row, OffsetDateTime now) {
        // TODO Task 21 will delete this class. ABOUT_TEXT verification is being retired.
        try {
            worldApi.fetchGroupPage(row.getSlGroupUuid()).block();
        } catch (RuntimeException ignored) {
            // swallow -- Task 21 will remove this path entirely.
        }
        return row;
    }
}
