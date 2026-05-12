package com.slparcelauctions.backend.realty.slgroup;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.moderation.RealtyGroupModerationProperties;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.GroupPageData;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sub-project F §13.2 — re-fetches {@code world.secondlife.com/group/{uuid}} for
 * a single {@link RealtyGroupSlGroup} row and evaluates whether the row has
 * drifted from its claimed state on the SL side.
 *
 * <p>Three drift conditions are detected:
 * <ul>
 *   <li><b>FOUNDER_CHANGED</b> — page parses, but the founder UUID differs from
 *       the avatar that verified the registration originally. The realty group
 *       probably lost in-world ownership of the SL group.</li>
 *   <li><b>GROUP_NOT_FOUND</b> — the World API serves a 404 for the group UUID.
 *       The SL group was deleted or made unreachable. Recorded immediately,
 *       skipping the failure-counter ramp.</li>
 *   <li><b>FETCH_FAILED_REPEATEDLY</b> — non-404 fetch errors (timeout, 5xx,
 *       network) accumulate onto
 *       {@link RealtyGroupSlGroup#getConsecutiveFetchFailures()}; drift fires
 *       once the counter crosses
 *       {@link RealtyGroupModerationProperties.SlGroupReverify#getReverifyFetchFailureThreshold()}.
 *       Transient outages don't mass-drift every registration on the first
 *       blip.</li>
 * </ul>
 *
 * <p>Drift flagging is idempotent — the first detected drift wins. The
 * {@code drift_detected_at} stamp is set once and not overwritten by later
 * passes, so an admin can see when the drift was first observed even if
 * subsequent passes see the same or a different condition. {@code drift_reason}
 * follows the same rule.
 *
 * <p>On successful fetch the {@code consecutive_fetch_failures} counter resets
 * to zero and {@code last_revalidated_at} is stamped to "now." On failed fetch
 * {@code last_revalidated_at} is intentionally left untouched — the column
 * means "most recent <em>successful</em> revalidation" and drives the cadence
 * picker in
 * {@code SlGroupReverifyTask}. If we stamped it on failure, a partial outage
 * would silently extend the time between real revalidations.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlGroupReverifyService {

    private final RealtyGroupSlGroupRepository slGroupRepo;
    private final RealtyGroupRepository realtyGroupRepo;
    private final SlWorldApiClient slWorldApiClient;
    private final NotificationPublisher notificationPublisher;
    private final RealtyGroupModerationProperties props;
    private final Clock clock;

    @Transactional
    public SlGroupReverifyResult recheck(Long slGroupId) {
        RealtyGroupSlGroup row = slGroupRepo.findById(slGroupId).orElseThrow();
        try {
            GroupPageData parsed = slWorldApiClient.fetchGroupPage(row.getSlGroupUuid()).block();
            row.setConsecutiveFetchFailures(0);
            row.setLastRevalidatedAt(OffsetDateTime.now(clock));
            row.setCurrentFounderUuid(parsed == null ? null : parsed.founderUuid());
            if (parsed != null
                    && !Objects.equals(parsed.founderUuid(), row.getFounderAvatarUuid())
                    && row.getDriftDetectedAt() == null) {
                flagDrift(row, SlGroupDriftReason.FOUNDER_CHANGED);
            }
            return new SlGroupReverifyResult(
                row.getDriftDetectedAt() != null,
                row.getDriftReason(),
                parsed == null ? null : parsed.founderUuid()
            );
        } catch (WebClientResponseException.NotFound nf) {
            flagDrift(row, SlGroupDriftReason.GROUP_NOT_FOUND);
            // Note: do NOT update last_revalidated_at on failure (see class javadoc).
            return new SlGroupReverifyResult(true, row.getDriftReason(), null);
        } catch (RuntimeException e) {
            log.warn("SL group reverify fetch failed for slGroupId={} slGroupUuid={}: {}",
                row.getId(), row.getSlGroupUuid(), e.toString());
            int next = row.getConsecutiveFetchFailures() + 1;
            row.setConsecutiveFetchFailures(next);
            int threshold = props.getSlGroup().getReverifyFetchFailureThreshold();
            boolean flagsDrift = next >= threshold && row.getDriftDetectedAt() == null;
            if (flagsDrift) {
                flagDrift(row, SlGroupDriftReason.FETCH_FAILED_REPEATEDLY);
            }
            // Note: do NOT update last_revalidated_at on failure (see class javadoc).
            return new SlGroupReverifyResult(
                row.getDriftDetectedAt() != null,
                row.getDriftReason(),
                null
            );
        }
    }

    /**
     * Stamps drift onto the row and fans out a leader-targeted notification.
     * Idempotent — if the row was already flagged drifted, this is a no-op so
     * the original {@code drift_detected_at} survives. The notification only
     * fires on the first flagging pass.
     */
    private void flagDrift(RealtyGroupSlGroup row, SlGroupDriftReason reason) {
        if (row.getDriftDetectedAt() != null) {
            return;
        }
        row.setDriftDetectedAt(OffsetDateTime.now(clock));
        row.setDriftReason(reason.name());

        Long leaderId = realtyGroupRepo.findById(row.getRealtyGroupId())
            .map(RealtyGroup::getLeaderId)
            .orElse(null);
        if (leaderId == null) {
            log.warn("SL group drift flagged but realty group leader not found:"
                + " slGroupId={} realtyGroupId={} reason={}",
                row.getId(), row.getRealtyGroupId(), reason);
            return;
        }
        notificationPublisher.realtyGroupSlGroupDriftDetected(
            leaderId,
            row.getRealtyGroupId(),
            row.getSlGroupName(),
            reason.name()
        );
    }
}
