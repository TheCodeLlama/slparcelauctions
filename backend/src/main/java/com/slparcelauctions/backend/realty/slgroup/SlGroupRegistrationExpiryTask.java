package com.slparcelauctions.backend.realty.slgroup;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sub-project E spec §7.4 -- deletes pending {@link RealtyGroupSlGroup} rows that have
 * passed their {@code verification_code_expires_at}, freeing the SL group UUID for
 * re-registration.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SlGroupRegistrationExpiryTask {

    private final RealtyGroupSlGroupRepository repo;
    private final Clock clock;

    @Scheduled(fixedDelay = 60 * 60 * 1000L)
    @Transactional
    public void runScheduled() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<RealtyGroupSlGroup> expired = repo.findExpiredPending(now);
        if (expired.isEmpty()) {
            return;
        }
        log.info("Deleting {} expired pending SL-group registration(s)", expired.size());
        for (RealtyGroupSlGroup row : expired) {
            repo.delete(row);
        }
    }
}
