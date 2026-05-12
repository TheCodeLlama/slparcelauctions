package com.slparcelauctions.backend.realty.moderation;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.audit.SystemUserResolver;
import com.slparcelauctions.backend.user.User;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sub-project F spec §9.3 — sweeps timed suspensions whose {@code expires_at} has
 * passed and stamps {@code lifted_at} / {@code lifted_by_admin_id} /
 * {@code lifted_notes} so the row's "active" predicate flips off without admin
 * action.
 *
 * <p>The repository's {@code findExpired} query already filters to rows that are
 * {@code lifted_at IS NULL AND expires_at IS NOT NULL AND expires_at < now}; the
 * sweep therefore never touches permanent bans (expires_at = NULL) or rows that
 * have already been lifted (lifted_at IS NOT NULL).
 *
 * <p>{@code liftedAt} is set to the row's {@code expiresAt} (not "now") so the
 * audit trail reflects the moment the suspension was supposed to end rather than
 * the moment this sweep happened to run.
 *
 * <p>The {@code lifted_by_admin_id} FK requires a real user row; {@link SystemUserResolver}
 * supplies the SLPA-system actor for that — see its Javadoc for the seed
 * convention.
 *
 * <p>The {@code @ConditionalOnProperty} kill-switch matches the codebase-wide
 * scheduler-disable pattern: tests flip {@code slpa.realty.group-suspension-expiry.enabled=false}
 * via {@code @TestPropertySource} to prevent the bean from registering at all
 * during context startup.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
    name = "slpa.realty.group-suspension-expiry.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class GroupSuspensionExpiryTask {

    private final RealtyGroupSuspensionRepository suspensionRepo;
    private final SystemUserResolver systemUserResolver;
    private final Clock clock;

    @Scheduled(fixedRate = 60L * 60L * 1000L)
    @Transactional
    public void runOnce() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<RealtyGroupSuspension> expired = suspensionRepo.findExpired(now);
        if (expired.isEmpty()) {
            return;
        }
        User systemUser = systemUserResolver.getSystemUser();
        for (RealtyGroupSuspension s : expired) {
            s.setLiftedAt(s.getExpiresAt());
            s.setLiftedByAdmin(systemUser);
            s.setLiftedNotes("Auto-lifted on expiry");
        }
        log.info("Auto-lifted {} expired group suspensions", expired.size());
    }
}
