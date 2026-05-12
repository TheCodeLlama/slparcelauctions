package com.slparcelauctions.backend.auction.monitoring;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.admin.audit.SystemUserResolver;
import com.slparcelauctions.backend.auction.CancellationService;
import com.slparcelauctions.backend.realty.moderation.RealtyGroupModerationProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sub-project F spec §10.2 — sweeps {@code listing_suspensions} rows with
 * {@code cause = ADMIN_GROUP_BULK} that have been suspended for longer than the
 * configured {@code slpa.realty.group-bulk-suspend.auto-cancel-hours} (default
 * 48 h) and force-cancels each via
 * {@link CancellationService#adminCancelExpiredBulkSuspend(Long, Long)}.
 *
 * <p>The repository query already filters to {@code cause = ADMIN_GROUP_BULK
 * AND lifted_at IS NULL AND cancelled_at IS NULL AND suspended_at < threshold},
 * so rows that have been reinstated by an admin (lifted_at set) or already
 * auto-cancelled (cancelled_at set) are never re-touched, and rows of other
 * causes (auto / admin-individual) are never picked up at all.
 *
 * <p><b>Per-row transaction isolation:</b> {@code adminCancelExpiredBulkSuspend}
 * is annotated {@code @Transactional} with default propagation
 * ({@code REQUIRED}). The outer {@code runOnce} is intentionally NOT
 * {@code @Transactional}, so each per-row call gets its own transaction. A
 * per-row failure (e.g. an unexpected race that flips the auction out of
 * {@code SUSPENDED} between the query and the for-update lock) only rolls back
 * that one row's transaction; the sweep logs the failure and continues with
 * the next row. This is different from
 * {@code BulkListingSuspendService.reinstateAll}, which IS {@code @Transactional}
 * and therefore cannot safely try/catch per row (the failed call would mark
 * the shared transaction rollback-only).
 *
 * <p>One batched {@code admin_actions} row is written per execution (not per
 * cancelled listing) via
 * {@link AdminActionService#recordSystemAction(AdminActionType, Map)}:
 * {@code action_type = REALTY_GROUP_BULK_SUSPEND_EXPIRY_RUN},
 * {@code details = { cancelledCount }}, actor = system user. The empty-due-set
 * case short-circuits before the audit row write to avoid log spam on cold
 * tables.
 *
 * <p>The {@code @ConditionalOnProperty} kill-switch matches the codebase-wide
 * scheduler-disable pattern: tests flip {@code slpa.realty.group-bulk-suspend.enabled
 * = false} via {@code @TestPropertySource} to prevent the bean from registering at
 * all during context startup.
 *
 * <p>{@link SystemUserResolver} is injected for future use by callers that need
 * the system actor (and to keep the bean wiring aligned with the plan); the
 * actual audit-row attribution is performed inside
 * {@code AdminActionService.recordSystemAction}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
    name = "slpa.realty.group-bulk-suspend.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class BulkSuspendedListingExpiryTask {

    private final ListingSuspensionRepository listingSuspensionRepo;
    private final CancellationService cancellationService;
    private final AdminActionService adminActionService;
    private final RealtyGroupModerationProperties props;
    @SuppressWarnings("unused") // injected for parity with the plan + future system-actor hooks
    private final SystemUserResolver systemUserResolver;
    private final Clock clock;

    @Scheduled(fixedRate = 60L * 60L * 1000L)
    public void runOnce() {
        OffsetDateTime threshold = OffsetDateTime.now(clock)
            .minusHours(props.getGroupBulkSuspend().getAutoCancelHours());
        List<ListingSuspension> due = listingSuspensionRepo.findExpiredBulkSuspends(threshold);
        if (due.isEmpty()) {
            return;
        }
        int cancelled = 0;
        for (ListingSuspension ls : due) {
            try {
                cancellationService.adminCancelExpiredBulkSuspend(
                    ls.getAuction().getId(), ls.getId());
                cancelled++;
            } catch (Exception e) {
                log.error("Failed to cancel listing-suspension {}", ls.getId(), e);
            }
        }
        adminActionService.recordSystemAction(
            AdminActionType.REALTY_GROUP_BULK_SUSPEND_EXPIRY_RUN,
            Map.of("cancelledCount", cancelled)
        );
        log.info("Bulk-suspend expiry sweep: cancelled {}/{} due listings", cancelled, due.size());
    }
}
