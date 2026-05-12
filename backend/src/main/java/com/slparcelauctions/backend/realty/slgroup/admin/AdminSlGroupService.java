package com.slparcelauctions.backend.realty.slgroup.admin;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.admin.dto.AdminSummaryDto;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroup;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroupRepository;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroupService;
import com.slparcelauctions.backend.realty.slgroup.SlGroupForceUnregisterService;
import com.slparcelauctions.backend.realty.slgroup.SlGroupReverifyResult;
import com.slparcelauctions.backend.realty.slgroup.SlGroupReverifyService;
import com.slparcelauctions.backend.realty.slgroup.admin.dto.AdminSlGroupRowDto;
import com.slparcelauctions.backend.realty.slgroup.exception.NoDriftDetectedException;
import com.slparcelauctions.backend.realty.slgroup.exception.SlGroupNotFoundException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service-layer wrapper for the admin SL group moderation endpoints. Keeps
 * {@link AdminSlGroupController} thin by absorbing the tenant-cross-check, audit
 * write, and drift-ack mutation logic that is not reusable from the other SL
 * group surfaces.
 *
 * <p>Three operations on a single SL group registration:
 * <ul>
 *   <li>{@link #recheck} — synchronous revalidation via
 *       {@link SlGroupReverifyService}; writes a
 *       {@link AdminActionType#REALTY_GROUP_SL_GROUP_RECHECK} audit row carrying
 *       the drift outcome.</li>
 *   <li>{@link #ackDrift} — clears the drift fields and stamps the acknowledging
 *       admin; if the observed founder UUID has moved to a new value, the
 *       founder snapshot ({@code founder_avatar_uuid}) is rolled forward to it
 *       so the row reflects the new in-world reality. Writes a
 *       {@link AdminActionType#REALTY_GROUP_SL_GROUP_DRIFT_ACK} audit row.
 *       Rejects via {@link NoDriftDetectedException} when there is no drift to
 *       acknowledge.</li>
 *   <li>{@link #unregister} / {@link #forceUnregister} — thin delegations to the
 *       pre-existing {@link RealtyGroupSlGroupService#unregister} gated path and
 *       the {@link SlGroupForceUnregisterService} cascade path, respectively.</li>
 * </ul>
 *
 * <p>Every operation resolves the registration row by its public id and validates
 * that it belongs to the realty group named in the URL. A mismatch surfaces as
 * {@link SlGroupNotFoundException} (404) so tenant existence cannot be probed.
 *
 * <p>Sub-project F spec §6.6, §13.3, §13.4, §13.5.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminSlGroupService {

    private final RealtyGroupSlGroupRepository slGroupRepo;
    private final RealtyGroupRepository realtyGroupRepo;
    private final SlGroupReverifyService reverifyService;
    private final SlGroupForceUnregisterService forceUnregisterService;
    private final RealtyGroupSlGroupService realtyGroupSlGroupService;
    private final AdminActionService adminActionService;
    private final UserRepository userRepo;
    private final Clock clock;

    /**
     * Resolves the SL group registration row and runs a synchronous reverify
     * pass through {@link SlGroupReverifyService#recheck}. Audit row carries the
     * drift outcome so the timeline distinguishes admin-driven rechecks from the
     * scheduled task's runs.
     */
    @Transactional
    public SlGroupReverifyResult recheck(
            UUID realtyGroupPublicId,
            UUID slGroupPublicId,
            Long adminId) {
        RealtyGroupSlGroup row = resolveRow(realtyGroupPublicId, slGroupPublicId);
        SlGroupReverifyResult result = reverifyService.recheck(row.getId());

        Map<String, Object> details = new HashMap<>();
        details.put("slGroupPublicId", slGroupPublicId.toString());
        details.put("driftDetected", result.driftDetected());
        if (result.driftReason() != null) {
            details.put("driftReason", result.driftReason());
        }
        if (result.currentFounderUuid() != null) {
            details.put("currentFounderUuid", result.currentFounderUuid().toString());
        }
        adminActionService.record(
            adminId,
            AdminActionType.REALTY_GROUP_SL_GROUP_RECHECK,
            AdminActionTargetType.REALTY_GROUP,
            row.getRealtyGroupId(),
            null,
            details);

        log.info("SL group rechecked by admin: slGroupPublicId={} adminId={} driftDetected={} reason={}",
            slGroupPublicId, adminId, result.driftDetected(), result.driftReason());
        return result;
    }

    /**
     * Clears the drift fields on the resolved registration row and stamps the
     * acknowledging admin + timestamp. If the most recently observed founder
     * UUID has moved to a non-null new value, the row's founder snapshot is
     * rolled forward — the admin has implicitly accepted the new founder as the
     * legitimate one. Writes a single {@code REALTY_GROUP_SL_GROUP_DRIFT_ACK}
     * audit row carrying the prior drift reason + the founder transition (if
     * any).
     *
     * @throws NoDriftDetectedException if {@code drift_detected_at} is null —
     *     ack only applies to rows currently flagged drifted.
     */
    @Transactional
    public void ackDrift(
            UUID realtyGroupPublicId,
            UUID slGroupPublicId,
            Long adminId,
            String notes) {
        RealtyGroupSlGroup row = resolveRow(realtyGroupPublicId, slGroupPublicId);
        if (row.getDriftDetectedAt() == null) {
            throw new NoDriftDetectedException(slGroupPublicId);
        }

        String priorDriftReason = row.getDriftReason();
        UUID priorFounderUuid = row.getFounderAvatarUuid();
        UUID newFounderUuid = row.getCurrentFounderUuid();
        boolean founderRolledForward = newFounderUuid != null
            && !Objects.equals(newFounderUuid, priorFounderUuid);

        row.setDriftDetectedAt(null);
        row.setDriftReason(null);
        row.setDriftAcknowledgedAt(OffsetDateTime.now(clock));
        // getReferenceById avoids the redundant SELECT — AdminActionService
        // re-resolves the admin user inside its own record() call.
        User admin = userRepo.getReferenceById(adminId);
        row.setDriftAcknowledgedByAdmin(admin);
        if (founderRolledForward) {
            row.setFounderAvatarUuid(newFounderUuid);
        }

        Map<String, Object> details = new HashMap<>();
        details.put("slGroupPublicId", slGroupPublicId.toString());
        if (priorDriftReason != null) {
            details.put("priorDriftReason", priorDriftReason);
        }
        details.put("founderRolledForward", founderRolledForward);
        if (founderRolledForward) {
            details.put("priorFounderUuid",
                priorFounderUuid == null ? null : priorFounderUuid.toString());
            details.put("newFounderUuid", newFounderUuid.toString());
        }
        adminActionService.record(
            adminId,
            AdminActionType.REALTY_GROUP_SL_GROUP_DRIFT_ACK,
            AdminActionTargetType.REALTY_GROUP,
            row.getRealtyGroupId(),
            notes,
            details);

        log.info("SL group drift acknowledged by admin: slGroupPublicId={} adminId={} "
            + "priorDriftReason={} founderRolledForward={}",
            slGroupPublicId, adminId, priorDriftReason, founderRolledForward);
    }

    /**
     * Non-force unregister path: delegates to
     * {@link RealtyGroupSlGroupService#unregister}, which respects the
     * active-listings gate and surfaces
     * {@link com.slparcelauctions.backend.realty.slgroup.exception.RegisteredSlGroupHasListingsException}
     * when in-flight case-3 listings are still attached.
     *
     * <p>Note: the underlying service uses
     * {@link com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer} which
     * requires the caller to hold the {@code REGISTER_SL_GROUP} permission on
     * the realty group. Admin users do not by default carry realty-group
     * membership; that gate is the right behavior for the public surface but
     * means an admin invoking this path must already be a member. The admin's
     * primary tool for force-action is the {@code force=true} route which
     * bypasses both the active-listings gate and any membership requirement via
     * {@link SlGroupForceUnregisterService}.
     */
    @Transactional
    public void unregister(
            UUID realtyGroupPublicId,
            UUID slGroupPublicId,
            Long adminId) {
        realtyGroupSlGroupService.unregister(adminId, realtyGroupPublicId, slGroupPublicId);
    }

    /**
     * Force-unregister path: delegates to
     * {@link SlGroupForceUnregisterService#forceUnregister}, which bypasses the
     * active-listings gate and cascades any in-flight case-3 listings through
     * the bulk-suspend path. The service writes its own
     * {@code REALTY_GROUP_SL_GROUP_FORCE_UNREGISTER} audit row.
     */
    @Transactional
    public void forceUnregister(
            UUID realtyGroupPublicId,
            UUID slGroupPublicId,
            Long adminId,
            String reason) {
        forceUnregisterService.forceUnregister(realtyGroupPublicId, slGroupPublicId, adminId, reason);
    }

    /**
     * Resolve the registration row by public id and confirm it belongs to the
     * realty group named in the URL. A miss in either dimension surfaces as
     * {@link SlGroupNotFoundException} so a probing caller cannot distinguish
     * "no such SL group" from "SL group exists but is owned by a different
     * realty group."
     *
     * <p>The realty group resolution explicitly does <em>not</em> filter on
     * {@code dissolved_at} — the admin moderation surface still needs to act on
     * SL group registrations attached to dissolved realty groups (cleanup,
     * recheck, force-unregister are all valid post-dissolve).
     */
    private RealtyGroupSlGroup resolveRow(UUID realtyGroupPublicId, UUID slGroupPublicId) {
        var group = realtyGroupRepo.findByPublicId(realtyGroupPublicId)
            .orElseThrow(() -> new RealtyGroupNotFoundException(realtyGroupPublicId));
        RealtyGroupSlGroup row = slGroupRepo.findByPublicId(slGroupPublicId)
            .orElseThrow(() -> new SlGroupNotFoundException(slGroupPublicId));
        if (!row.getRealtyGroupId().equals(group.getId())) {
            // Don't leak cross-tenant existence.
            throw new SlGroupNotFoundException(slGroupPublicId);
        }
        return row;
    }

    /**
     * Build the admin-facing row DTO for the resolved registration. Materializes
     * the lazy admin associations so the returned shape is safe to render
     * outside a transaction. Used by {@link AdminSlGroupController#ackDrift}
     * which wants to return the post-ack row state.
     */
    AdminSlGroupRowDto toAdminRow(RealtyGroupSlGroup row) {
        return new AdminSlGroupRowDto(
            row.getPublicId(),
            row.getSlGroupUuid(),
            row.getSlGroupName(),
            row.isVerified(),
            row.getVerifiedAt(),
            row.getVerifiedVia(),
            row.getFounderAvatarUuid(),
            row.getCurrentFounderUuid(),
            row.getLastRevalidatedAt(),
            row.getConsecutiveFetchFailures(),
            row.getDriftDetectedAt(),
            row.getDriftReason(),
            row.getDriftAcknowledgedAt(),
            toSummary(row.getDriftAcknowledgedByAdmin()),
            row.getUnregisteredAt(),
            toSummary(row.getUnregisteredByAdmin()),
            row.getUnregisterReason());
    }

    /**
     * Loads the post-ack row inside its own transactional read so the lazy
     * admin associations are initialized for the wire-shape render. Called from
     * {@link AdminSlGroupController#ackDrift} after the mutation transaction
     * has committed.
     */
    @Transactional(readOnly = true)
    public AdminSlGroupRowDto loadAdminRow(UUID realtyGroupPublicId, UUID slGroupPublicId) {
        RealtyGroupSlGroup row = resolveRow(realtyGroupPublicId, slGroupPublicId);
        return toAdminRow(row);
    }

    private static AdminSummaryDto toSummary(User user) {
        if (user == null) return null;
        return new AdminSummaryDto(user.getPublicId(), user.getDisplayName());
    }
}
