package com.slparcelauctions.backend.realty.slgroup;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.realty.moderation.RealtyGroupModerationProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sub-project F spec §13.1 — hourly sweep that asks {@link SlGroupReverifyService} to
 * recheck every {@link RealtyGroupSlGroup} registration whose
 * {@code last_revalidated_at} is older than the configured cadence (or has never been
 * revalidated). The recheck call talks to the SL World API and flags drift onto the
 * row.
 *
 * <p>The cadence threshold is computed as
 * {@code now - slpa.realty.sl-group.reverify-cadence-days} (default 30). The
 * repository query also filters out unverified rows and rows that an admin has
 * force-unregistered ({@code unregistered_at IS NOT NULL}), so the task body only
 * sees real candidates and never has to re-filter in Java.
 *
 * <p>Per-row failures are logged and swallowed: a single hosed registration must
 * not abort the sweep for everyone else, and {@code SlGroupReverifyService.recheck}
 * already handles World API errors internally — anything that escapes is a
 * programmer error (NPE, etc.) we'd rather log + skip than let bubble up and kill
 * the next 5,000 rows. Each {@code recheck(...)} call runs in its own
 * {@code @Transactional} from the service, so a rollback on one row doesn't
 * poison the others.
 *
 * <p>The {@code @ConditionalOnProperty} kill-switch matches the codebase-wide
 * scheduler-disable pattern: tests flip {@code slpa.realty.sl-group.reverify.enabled=false}
 * via {@code @TestPropertySource} to prevent the bean from registering at all
 * during context startup.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        name = "slpa.realty.sl-group.reverify.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SlGroupReverifyTask {

    private final RealtyGroupSlGroupRepository repo;
    private final SlGroupReverifyService reverifyService;
    private final RealtyGroupModerationProperties props;
    private final Clock clock;

    @Scheduled(fixedRate = 60L * 60L * 1000L)
    public void runOnce() {
        OffsetDateTime threshold = OffsetDateTime.now(clock)
                .minusDays(props.getSlGroup().getReverifyCadenceDays());
        int batchSize = props.getSlGroup().getReverifyBatchSize();
        List<RealtyGroupSlGroup> due = repo.findDueForReverify(
                threshold, PageRequest.of(0, batchSize));
        if (due.isEmpty()) {
            return;
        }
        for (RealtyGroupSlGroup row : due) {
            try {
                reverifyService.recheck(row.getId());
            } catch (Exception e) {
                log.warn("Reverify failed for slGroup {}", row.getId(), e);
            }
        }
        log.info("Reverified {} SL groups (threshold={}, cap={})", due.size(), threshold, batchSize);
    }
}
