package com.slparcelauctions.backend.realty.slgroup;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.GroupPageData;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sub-project E spec §7.2 -- polls pending {@link RealtyGroupSlGroup} rows on a 5-minute
 * cadence. For each row that hasn't been polled in the last 5 minutes, fetches the SL group
 * page and looks for the verification code in the About text. On match, flips the row to
 * verified-via-ABOUT_TEXT.
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
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime cutoff = now.minus(POLL_INTERVAL);
        List<RealtyGroupSlGroup> due = repo.findDueForAboutTextPoll(now, cutoff);
        for (RealtyGroupSlGroup row : due) {
            try {
                pollOne(row, now);
            } catch (RuntimeException e) {
                log.warn("about-text poll for sl_group {} threw {}; will retry next cycle",
                        row.getSlGroupUuid(), e.toString());
            }
        }
    }

    /**
     * Polls a single row immediately, regardless of throttle. Called by the manual
     * /recheck endpoint and by the scheduled sweep.
     */
    @Transactional
    public RealtyGroupSlGroup pollOne(RealtyGroupSlGroup row, OffsetDateTime now) {
        GroupPageData page;
        try {
            page = worldApi.fetchGroupPage(row.getSlGroupUuid()).block();
        } catch (RuntimeException e) {
            row.setLastPolledAt(now);
            row.setPollAttempts(row.getPollAttempts() + 1);
            return repo.save(row);
        }
        if (page == null) {
            row.setLastPolledAt(now);
            row.setPollAttempts(row.getPollAttempts() + 1);
            return repo.save(row);
        }
        String about = page.aboutText();
        String code = row.getVerificationCode();
        if (about != null && code != null && about.contains(code)) {
            row.setVerified(true);
            row.setVerifiedAt(now);
            row.setVerifiedVia(SlGroupVerifyMethod.ABOUT_TEXT);
            row.setVerificationCode(null);
            if (page.name() != null && row.getSlGroupName() == null) {
                row.setSlGroupName(page.name());
            }
            log.info("SL group verified via ABOUT_TEXT: sl_group_uuid={}", row.getSlGroupUuid());
        } else {
            row.setLastPolledAt(now);
            row.setPollAttempts(row.getPollAttempts() + 1);
        }
        return repo.save(row);
    }
}
