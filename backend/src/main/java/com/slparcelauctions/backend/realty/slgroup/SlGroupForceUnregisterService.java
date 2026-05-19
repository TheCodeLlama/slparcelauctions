package com.slparcelauctions.backend.realty.slgroup;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.monitoring.BulkListingSuspendService;
import com.slparcelauctions.backend.realty.slgroup.exception.SlGroupNotFoundException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Admin compliance-action service: force-unregister an SL group registration even
 * though the standard non-force gate ({@link RealtyGroupSlGroupService#unregister})
 * would block on in-flight group-sale listings.
 *
 * <p>Spec §13.5: when the admin opts into the force path, this service
 * <ol>
 *   <li>resolves the {@code realty_group_sl_groups} row by public id (404 if missing),</li>
 *   <li>stamps {@code unregistered_at} / {@code unregistered_by_admin_id} /
 *       {@code unregister_reason} so the registration is no longer load-bearing for
 *       any future listing-create attempt,</li>
 *   <li>sweeps every {@link com.slparcelauctions.backend.auction.AuctionStatus#ACTIVE}
 *       group-sale listing attached to the registration into the bulk-suspend cascade via
 *       {@link BulkListingSuspendService#suspendAll}; the 48 h auto-cancel timer then
 *       gives the admin a deliberate window to decide per-listing cancel-or-resolve, and</li>
 *   <li>writes a single batched {@link AdminActionType#REALTY_GROUP_SL_GROUP_FORCE_UNREGISTER}
 *       audit row whose details carry the SL group public id, the cascaded count, and
 *       the supplied reason.</li>
 * </ol>
 *
 * <p>The whole operation runs inside one transaction so a failure in any step rolls
 * back the unregister stamp, the listing suspensions, and the audit row together.
 *
 * <p>Sub-project F spec §13.5.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlGroupForceUnregisterService {

    /** Reason code threaded into the bulk-suspend cascade so per-listing audit rows
     *  carry the same provenance as the SL-group-level admin action. */
    static final String CASCADE_REASON = "SL_GROUP_FORCE_UNREGISTER";

    private final RealtyGroupSlGroupRepository slGroupRepo;
    private final AuctionRepository auctionRepo;
    private final BulkListingSuspendService bulkListingSuspendService;
    private final AdminActionService adminActionService;
    private final UserRepository userRepo;
    private final Clock clock;

    /**
     * Force-unregister the SL group registration identified by
     * {@code slGroupPublicId} and cascade-suspend its in-flight group-sale listings.
     *
     * @param realtyGroupPublicId  the parent realty group's public id. Carried for
     *                             API symmetry / future cross-tenant checks; the
     *                             registration row already pins the {@code realty_group_id}
     *                             so the unregister stamp is authoritative regardless.
     * @param slGroupPublicId      the {@code realty_group_sl_groups} row's public id;
     *                             404 if not found.
     * @param adminId              the admin issuing the force-unregister.
     * @param reason               coded reason recorded on the row and in the audit details.
     * @throws SlGroupNotFoundException  if {@code slGroupPublicId} resolves to nothing.
     */
    @Transactional
    public void forceUnregister(
            UUID realtyGroupPublicId,
            UUID slGroupPublicId,
            Long adminId,
            String reason) {

        RealtyGroupSlGroup row = slGroupRepo.findByPublicId(slGroupPublicId)
            .orElseThrow(() -> new SlGroupNotFoundException(slGroupPublicId));

        OffsetDateTime now = OffsetDateTime.now(clock);
        // Hibernate proxy avoids a SELECT round-trip; we only need the FK id on the
        // User reference. AdminActionService.record does its own admin lookup.
        User admin = userRepo.getReferenceById(adminId);
        row.setUnregisteredAt(now);
        row.setUnregisteredByAdmin(admin);
        row.setUnregisterReason(reason);

        // Cascade: bulk-suspend any in-flight group-sale listings on this SL group.
        // suspendAll itself short-circuits cleanly on an empty list, but skipping the
        // call entirely when there are zero targets avoids a redundant audit row that
        // would only repeat what REALTY_GROUP_SL_GROUP_FORCE_UNREGISTER already records.
        List<Auction> active = auctionRepo.findActiveCase3ListingsForSlGroup(row.getId());
        if (!active.isEmpty()) {
            bulkListingSuspendService.suspendAll(
                row.getRealtyGroupId(),
                adminId,
                CASCADE_REASON,
                null,   // no admin notes from the force-unregister surface for now
                null);  // no linked realty_group_suspensions row
        }

        Map<String, Object> details = new HashMap<>();
        details.put("slGroupPublicId", slGroupPublicId.toString());
        details.put("cascadedListingCount", active.size());
        details.put("reason", reason);
        adminActionService.record(
            adminId,
            AdminActionType.REALTY_GROUP_SL_GROUP_FORCE_UNREGISTER,
            AdminActionTargetType.REALTY_GROUP,
            row.getRealtyGroupId(),
            reason,
            details);

        log.info("SL group force-unregistered: slGroupPublicId={} realtyGroupId={} adminId={} cascadedListingCount={}",
            slGroupPublicId, row.getRealtyGroupId(), adminId, active.size());
    }
}
