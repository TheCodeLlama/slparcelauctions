package com.slparcelauctions.backend.realty.moderation;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.auction.monitoring.BulkListingSuspendService;
import com.slparcelauctions.backend.auction.monitoring.BulkListingSuspendService.BulkSuspendResult;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.moderation.exception.SuspensionAlreadyActiveException;
import com.slparcelauctions.backend.realty.moderation.exception.SuspensionNotFoundException;
import com.slparcelauctions.backend.user.User;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Issue / lift / inspect admin-driven suspensions and permanent bans against realty
 * groups. The single source of truth for the {@code realty_group_suspensions} table.
 *
 * <p>Behaviour per spec §8, §9:
 * <ul>
 *   <li>{@link #issue} writes a new {@link RealtyGroupSuspension} row, records the
 *       corresponding {@code AdminAction} (SUSPEND for timed, BAN for permanent),
 *       fires the per-member notification fan-out via
 *       {@link NotificationPublisher#realtyGroupSuspended}, and writes a Redis
 *       short-circuit hash at {@code realty_group_suspended:{groupId}} so
 *       {@code RealtyGroupGuard} (Task 7) can skip the DB query on the hot path.</li>
 *   <li>{@link #lift} stamps {@code lifted_at} / {@code lifted_by_admin_id} /
 *       {@code lifted_notes}, records UNSUSPEND/UNBAN, fires the lifted notification,
 *       and deletes the Redis short-circuit key.</li>
 *   <li>Both paths optionally cascade into {@link BulkListingSuspendService} for the
 *       bulk-listing-suspend / bulk-listing-reinstate flow. Task 11 fleshes out the
 *       bulk service; Task 6 ships a stub.</li>
 * </ul>
 *
 * <p>Sub-project F spec §8, §9.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RealtyGroupSuspensionService {

    /** Prefix for the Redis short-circuit hash used by {@code RealtyGroupGuard}. */
    static final String REDIS_KEY_PREFIX = "realty_group_suspended:";
    /** Marker value stored in the Redis hash when the suspension is a permanent ban. */
    static final String REDIS_PERMANENT_MARKER = "PERMANENT";
    /** Field name inside the Redis hash. */
    static final String REDIS_FIELD_EXPIRES_AT = "expiresAt";
    /** Upper bound on the Redis short-circuit TTL. Spec §8: "min(duration, 5 minutes)". */
    static final Duration REDIS_MAX_TTL = Duration.ofMinutes(5);

    private final RealtyGroupRepository groups;
    private final RealtyGroupSuspensionRepository suspensions;
    private final AdminActionService adminActionService;
    private final NotificationPublisher notificationPublisher;
    private final BulkListingSuspendService bulkListingSuspendService;
    private final StringRedisTemplate redis;
    private final Clock clock;

    /**
     * Issue a new suspension. {@code expiresAt == null} means a permanent ban — emits
     * {@link AdminActionType#REALTY_GROUP_BAN} rather than
     * {@link AdminActionType#REALTY_GROUP_SUSPEND} in the audit log.
     *
     * @throws RealtyGroupNotFoundException        if {@code groupPublicId} resolves to nothing
     * @throws SuspensionAlreadyActiveException    if the group already has an active suspension
     */
    @Transactional
    public RealtyGroupSuspension issue(
            UUID groupPublicId,
            Long adminUserId,
            SuspensionReason reason,
            String notes,
            OffsetDateTime expiresAt,
            boolean bulkSuspendListings) {

        RealtyGroup group = groups.findByPublicId(groupPublicId)
            .orElseThrow(() -> new RealtyGroupNotFoundException(groupPublicId));

        OffsetDateTime now = OffsetDateTime.now(clock);
        suspensions.findActiveByGroupId(group.getId(), now).ifPresent(existing -> {
            throw new SuspensionAlreadyActiveException(groupPublicId);
        });

        User admin = User.builder().build();
        // Use a reference-only User so the FK column gets populated without a round-trip
        // fetch. The audit-action service does its own admin lookup; for the suspension
        // row we only need the id on the User reference.
        adminReference(admin, adminUserId);

        RealtyGroupSuspension row = RealtyGroupSuspension.builder()
            .realtyGroup(group)
            .issuedByAdmin(admin)
            .reason(reason)
            .notes(notes)
            .issuedAt(now)
            .expiresAt(expiresAt)
            .build();
        RealtyGroupSuspension saved = suspensions.save(row);

        boolean permanent = (expiresAt == null);
        AdminActionType actionType = permanent
            ? AdminActionType.REALTY_GROUP_BAN
            : AdminActionType.REALTY_GROUP_SUSPEND;
        Map<String, Object> details = new HashMap<>();
        details.put("suspensionPublicId", saved.getPublicId().toString());
        details.put("reason", reason.name());
        details.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
        details.put("bulkSuspendListings", bulkSuspendListings);
        adminActionService.record(
            adminUserId,
            actionType,
            AdminActionTargetType.REALTY_GROUP,
            group.getId(),
            notes,
            details);

        writeRedisShortCircuit(group.getId(), expiresAt, now);

        notificationPublisher.realtyGroupSuspended(group, reason.name(), expiresAt);

        if (bulkSuspendListings) {
            BulkSuspendResult bulk = bulkListingSuspendService.suspendAll(
                group.getId(), adminUserId, reason.name(), saved.getId());
            log.info("Bulk-suspended {} listings for group id={} (bulk_action_id={})",
                bulk.suspendedCount(), group.getId(), bulk.bulkActionId());
        }

        log.info("Realty group suspension issued: groupPublicId={} adminUserId={} reason={} permanent={}",
            groupPublicId, adminUserId, reason, permanent);
        return saved;
    }

    /**
     * Lift an active suspension. {@code suspensionPublicId} must reference a suspension
     * that (a) belongs to the addressed group and (b) has not already been lifted —
     * both failure modes surface as {@link SuspensionNotFoundException} (404).
     *
     * @throws RealtyGroupNotFoundException  if {@code groupPublicId} resolves to nothing
     * @throws SuspensionNotFoundException   if the suspension is unknown, already lifted,
     *                                       or addresses a different group
     */
    @Transactional
    public RealtyGroupSuspension lift(
            UUID groupPublicId,
            UUID suspensionPublicId,
            Long adminUserId,
            String liftedNotes,
            boolean bulkReinstateListings) {

        RealtyGroup group = groups.findByPublicId(groupPublicId)
            .orElseThrow(() -> new RealtyGroupNotFoundException(groupPublicId));

        RealtyGroupSuspension row = suspensions.findByPublicId(suspensionPublicId)
            .orElseThrow(() -> new SuspensionNotFoundException(suspensionPublicId));
        if (row.getRealtyGroup() == null
                || !group.getId().equals(row.getRealtyGroup().getId())
                || row.getLiftedAt() != null) {
            // Already lifted, or cross-group reference — surface as 404 either way so
            // the admin UI shows the same "not actionable" state.
            throw new SuspensionNotFoundException(suspensionPublicId);
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        boolean permanent = (row.getExpiresAt() == null);

        User admin = User.builder().build();
        adminReference(admin, adminUserId);
        row.setLiftedAt(now);
        row.setLiftedByAdmin(admin);
        row.setLiftedNotes(liftedNotes);
        RealtyGroupSuspension saved = suspensions.save(row);

        AdminActionType actionType = permanent
            ? AdminActionType.REALTY_GROUP_UNBAN
            : AdminActionType.REALTY_GROUP_UNSUSPEND;
        Map<String, Object> details = new HashMap<>();
        details.put("suspensionPublicId", saved.getPublicId().toString());
        details.put("bulkReinstateListings", bulkReinstateListings);
        adminActionService.record(
            adminUserId,
            actionType,
            AdminActionTargetType.REALTY_GROUP,
            group.getId(),
            liftedNotes,
            details);

        redis.delete(REDIS_KEY_PREFIX + group.getId());

        notificationPublisher.realtyGroupUnsuspended(group);

        if (bulkReinstateListings) {
            int reinstated = bulkListingSuspendService.reinstateAll(
                group.getId(), adminUserId, liftedNotes);
            log.info("Bulk-reinstated {} listings for group id={}", reinstated, group.getId());
        }

        log.info("Realty group suspension lifted: groupPublicId={} suspensionPublicId={} adminUserId={} permanent={}",
            groupPublicId, suspensionPublicId, adminUserId, permanent);
        return saved;
    }

    /**
     * Currently-active suspension for a group, or empty when the group is in good standing.
     * Used by {@code RealtyGroupGuard} cold path and by the admin moderation overview page.
     */
    @Transactional(readOnly = true)
    public Optional<RealtyGroupSuspension> findActive(Long groupId) {
        return suspensions.findActiveByGroupId(groupId, OffsetDateTime.now(clock));
    }

    /** Full suspension history, newest first. Drives the admin moderation history pane. */
    @Transactional(readOnly = true)
    public List<RealtyGroupSuspension> listHistory(UUID groupPublicId) {
        RealtyGroup group = groups.findByPublicId(groupPublicId)
            .orElseThrow(() -> new RealtyGroupNotFoundException(groupPublicId));
        return suspensions.findHistoryByGroupId(group.getId());
    }

    // ─────────────────────── helpers ───────────────────────

    /**
     * Sets the inherited {@code id} field on a reference-only User. The FK column on
     * {@code realty_group_suspensions} only needs the id; we avoid an extra round-trip
     * to the users table since {@code AdminActionService.record} already fetches the
     * admin user for its own row.
     */
    private static void adminReference(User user, Long adminUserId) {
        try {
            java.lang.reflect.Field f = User.class.getSuperclass()
                .getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(user, adminUserId);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not set User.id via reflection", e);
        }
    }

    private void writeRedisShortCircuit(Long groupId, OffsetDateTime expiresAt, OffsetDateTime now) {
        String key = REDIS_KEY_PREFIX + groupId;
        String value = (expiresAt == null) ? REDIS_PERMANENT_MARKER : expiresAt.toString();
        redis.opsForHash().put(key, REDIS_FIELD_EXPIRES_AT, value);
        Duration ttl;
        if (expiresAt == null) {
            ttl = REDIS_MAX_TTL;
        } else {
            Duration untilExpiry = Duration.between(now, expiresAt);
            if (untilExpiry.isNegative() || untilExpiry.isZero()) {
                // Defensive — should not happen since findActive already filtered, but if a
                // caller passes a past expiry we still set a minimum-positive TTL so the
                // hash doesn't linger forever.
                ttl = Duration.ofSeconds(1);
            } else if (untilExpiry.compareTo(REDIS_MAX_TTL) > 0) {
                ttl = REDIS_MAX_TTL;
            } else {
                ttl = untilExpiry;
            }
        }
        redis.expire(key, ttl.toSeconds(), TimeUnit.SECONDS);
    }
}
