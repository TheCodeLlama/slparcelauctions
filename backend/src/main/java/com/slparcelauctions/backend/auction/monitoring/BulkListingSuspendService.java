package com.slparcelauctions.backend.auction.monitoring;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.AdminAuctionService;
import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.bot.BotMonitorLifecycleService;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.moderation.RealtyGroupSuspension;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;

/**
 * Bulk-suspend / bulk-reinstate every active listing attributed to a realty group.
 * Invoked by {@code RealtyGroupSuspensionService} when an admin issues / lifts a
 * group suspension with the bulk-listing flag set, and directly by the admin
 * bulk-listings controller (Task 14).
 *
 * <p>{@link #suspendAll} sweeps both case-1 (direct {@code realty_group_id}) and
 * case-3 ({@code realty_group_sl_group_id} → {@link RealtyGroupSuspension}'s
 * group) listings via {@link AuctionRepository#findActiveListingsForGroup}, flips
 * each ACTIVE row to SUSPENDED, writes one {@link ListingSuspension} per row
 * tagged {@link ListingSuspensionCause#ADMIN_GROUP_BULK} with a shared bulk-action
 * UUID, fires the bot-monitor close hook and a per-seller suspended notification,
 * and records one batched admin audit row.
 *
 * <p>{@link #reinstateAll} reverses the operation: finds every active bulk-cause
 * listing suspension for the group, calls {@link AdminAuctionService#reinstate}
 * per row (which extends {@code endsAt} by the suspension duration, fires the
 * bot-monitor resume hook, and publishes the reinstated notification), stamps
 * {@code listing_suspensions.lifted_at}, and records one batched audit row.
 *
 * <p>Sub-project F spec §10.1, §10.3.
 */
@Service
@Slf4j
public class BulkListingSuspendService {

    private final AuctionRepository auctionRepo;
    private final ListingSuspensionRepository listingSuspensionRepo;
    private final BotMonitorLifecycleService botMonitorLifecycleService;
    private final NotificationPublisher notificationPublisher;
    private final AdminAuctionService adminAuctionService;
    private final AdminActionService adminActionService;
    private final UserRepository userRepository;
    private final Clock clock;

    /**
     * Set by Spring via {@code @PersistenceContext}. Used only to materialise a
     * Hibernate-managed reference (proxy) for the {@code listing_suspensions
     * .group_suspension_id} FK column without a SELECT round-trip. Field
     * injection (rather than constructor) keeps the test constructor lean —
     * tests that don't link a {@code RealtyGroupSuspension} skip the proxy path
     * entirely.
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Result returned by {@link #suspendAll}: the bulk-action UUID that links every
     * {@code listing_suspensions} row written in this run, and the number of listings
     * actually transitioned to SUSPENDED.
     */
    public record BulkSuspendResult(UUID bulkActionId, int suspendedCount) {}

    @Autowired
    public BulkListingSuspendService(
            AuctionRepository auctionRepo,
            ListingSuspensionRepository listingSuspensionRepo,
            BotMonitorLifecycleService botMonitorLifecycleService,
            NotificationPublisher notificationPublisher,
            AdminAuctionService adminAuctionService,
            AdminActionService adminActionService,
            UserRepository userRepository,
            Clock clock) {
        this.auctionRepo = auctionRepo;
        this.listingSuspensionRepo = listingSuspensionRepo;
        this.botMonitorLifecycleService = botMonitorLifecycleService;
        this.notificationPublisher = notificationPublisher;
        this.adminAuctionService = adminAuctionService;
        this.adminActionService = adminActionService;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    /**
     * Suspend every active listing for the group.
     *
     * @param groupId                  the realty group whose listings to suspend
     * @param adminUserId              the admin issuing the bulk action
     * @param reason                   audit-facing reason (typically the suspension reason)
     * @param linkedGroupSuspensionId  FK back into {@code realty_group_suspensions};
     *                                 nullable for ad-hoc admin bulk suspends invoked
     *                                 directly via the bulk-listings controller (Task 14)
     */
    @Transactional
    public BulkSuspendResult suspendAll(Long groupId, Long adminUserId, String reason,
                                        Long linkedGroupSuspensionId) {
        UUID bulkActionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Auction> targets = auctionRepo.findActiveListingsForGroup(groupId);

        // Resolve once for FK columns. Hibernate proxy avoids a SELECT round-trip per
        // listing — the audit-action service does its own admin lookup separately.
        User admin = targets.isEmpty()
            ? null
            : userRepository.getReferenceById(adminUserId);
        RealtyGroupSuspension groupSuspensionRef = (targets.isEmpty() || linkedGroupSuspensionId == null)
            ? null
            : entityManager.getReference(RealtyGroupSuspension.class, linkedGroupSuspensionId);

        int count = 0;
        for (Auction a : targets) {
            if (a.getStatus() != AuctionStatus.ACTIVE) {
                // Defensive: the repo query selects ACTIVE only, but a concurrent transaction
                // could flip a row to SUSPENDED / ENDED between the SELECT and our update.
                // Skip rather than write a duplicate listing_suspensions row.
                log.debug("Skipping auction {} during bulk-suspend: status={}", a.getId(), a.getStatus());
                continue;
            }
            a.setStatus(AuctionStatus.SUSPENDED);
            a.setSuspendedAt(now);

            ListingSuspension ls = ListingSuspension.builder()
                .auction(a)
                .cause(ListingSuspensionCause.ADMIN_GROUP_BULK)
                .suspendedByAdmin(admin)
                .groupSuspension(groupSuspensionRef)
                .bulkActionId(bulkActionId)
                .reason(reason)
                .suspendedAt(now)
                .build();
            listingSuspensionRepo.save(ls);

            botMonitorLifecycleService.onAuctionClosed(a);
            notificationPublisher.listingSuspended(
                a.getSeller().getId(),
                a.getId(),
                a.getTitle(),
                "ADMIN_GROUP_BULK_SUSPEND");
            count++;
        }

        Map<String, Object> details = new HashMap<>();
        details.put("count", count);
        details.put("groupId", groupId);
        details.put("bulkActionId", bulkActionId.toString());
        if (linkedGroupSuspensionId != null) {
            details.put("groupSuspensionId", linkedGroupSuspensionId);
        }
        adminActionService.record(
            adminUserId,
            AdminActionType.REALTY_GROUP_BULK_SUSPEND,
            AdminActionTargetType.REALTY_GROUP,
            groupId,
            reason,
            details);

        log.info("Bulk-suspended {} listings for group id={} (bulk_action_id={}, linked_group_suspension_id={})",
            count, groupId, bulkActionId, linkedGroupSuspensionId);
        return new BulkSuspendResult(bulkActionId, count);
    }

    /**
     * Reinstate every active {@code ADMIN_GROUP_BULK}-cause suspension for the group.
     * Per-row reinstatement runs through {@link AdminAuctionService#reinstate}: that
     * service flips the auction back to {@code ACTIVE}, extends {@code endsAt} by the
     * suspension duration, fires the bot-monitor resume hook, and publishes the
     * reinstated notification — keeping the bulk path's per-row semantics identical
     * to the individual {@code /admin/auctions/{id}/reinstate} endpoint.
     *
     * <p>Atomic batch: any per-row reinstate failure rolls back the entire batch
     * (including any successful {@code liftedAt} stamps and the audit row). The
     * earlier per-row try/catch was misleading — under Spring's transaction
     * propagation model, {@code AdminAuctionService#reinstate} joins the outer
     * {@code @Transactional} and any RuntimeException flips the shared transaction
     * to rollback-only, so swallowing it here only hid the {@code
     * UnexpectedRollbackException} at commit while still rolling back everything.
     * If best-effort batching is ever required, switch
     * {@code AdminAuctionService#reinstate} to {@code Propagation.REQUIRES_NEW}.
     */
    @Transactional
    public int reinstateAll(Long groupId, Long adminUserId, String notes) {
        List<ListingSuspension> active = listingSuspensionRepo.findActiveBulkSuspensionsForGroup(groupId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        int count = 0;
        for (ListingSuspension ls : active) {
            // Reinstate joins the outer @Transactional; a failure here rolls back the
            // entire batch (including any liftedAt stamps already applied and the
            // audit row). Per-row try/catch can't deliver best-effort semantics under
            // Spring's transaction propagation model.
            adminAuctionService.reinstate(ls.getAuction().getId(), Optional.of(ls.getSuspendedAt()));
            ls.setLiftedAt(now);
            count++;
        }

        Map<String, Object> details = new HashMap<>();
        details.put("count", count);
        details.put("groupId", groupId);
        adminActionService.record(
            adminUserId,
            AdminActionType.REALTY_GROUP_BULK_REINSTATE,
            AdminActionTargetType.REALTY_GROUP,
            groupId,
            notes,
            details);

        log.info("Bulk-reinstated {} listings for group id={}", count, groupId);
        return count;
    }
}
