package com.slparcelauctions.backend.realty.moderation;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auction.monitoring.BulkListingSuspendService;
import com.slparcelauctions.backend.auction.monitoring.BulkListingSuspendService.BulkSuspendResult;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.moderation.dto.BulkReinstateListingsRequest;
import com.slparcelauctions.backend.realty.moderation.dto.BulkSuspendListingsRequest;
import com.slparcelauctions.backend.realty.moderation.dto.BulkSuspendResultDto;
import com.slparcelauctions.backend.realty.moderation.dto.ReinstateResultDto;
import com.slparcelauctions.backend.realty.moderation.exception.SuspensionNotFoundException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Admin endpoints for the standalone bulk-listing-suspend and bulk-listing-reinstate
 * cascades against a realty group. Wraps {@link BulkListingSuspendService}
 * (Task 11) behind admin-authenticated HTTP. Unlike the cascades triggered by
 * {@code AdminRealtyGroupSuspensionController}, these endpoints can run without
 * a corresponding {@link RealtyGroupSuspension} row — they're used when admins
 * want to bulk-act on a group's listings without (or after) issuing a group-level
 * suspension. When a {@code groupSuspensionPublicId} is supplied in the suspend
 * request, the controller resolves the public id through
 * {@link RealtyGroupSuspensionRepository} and passes the entity {@code id} into
 * the service so the bulk {@code listing_suspensions} rows carry the FK back to
 * the originating suspension.
 *
 * <p>Two operations under
 * {@code /api/v1/admin/realty-groups/{publicId}/listings}:
 * <ul>
 *   <li>{@code POST /suspend-all} — Body
 *       {@code {reason, notes, groupSuspensionPublicId nullable}} → invokes
 *       {@link BulkListingSuspendService#suspendAll}. Returns 200 with
 *       {@link BulkSuspendResultDto}.</li>
 *   <li>{@code POST /reinstate-all} — Body {@code {notes}} → invokes
 *       {@link BulkListingSuspendService#reinstateAll}. Returns 200 with
 *       {@link ReinstateResultDto}.</li>
 * </ul>
 *
 * <p>Both endpoints return 200 (not 201/204) because they're action-style
 * endpoints that return a result body, not REST-style create/delete operations.
 *
 * <p>{@code @PreAuthorize("hasRole('ADMIN')")} is defense-in-depth alongside the
 * blanket {@code /api/v1/admin/**} rule in {@code SecurityConfig}. A missing /
 * unknown {@code publicId} surfaces as {@link RealtyGroupNotFoundException} (404
 * via {@code RealtyExceptionHandler}); an unknown {@code groupSuspensionPublicId}
 * surfaces as {@link SuspensionNotFoundException} (404).
 *
 * <p>Sub-project F spec §6.3.
 */
@RestController
@RequestMapping("/api/v1/admin/realty-groups/{publicId}/listings")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminRealtyGroupBulkListingsController {

    private final BulkListingSuspendService bulkService;
    private final RealtyGroupRepository groupRepo;
    private final RealtyGroupSuspensionRepository suspensionRepo;

    @PostMapping("/suspend-all")
    @Transactional
    public BulkSuspendResultDto suspendAll(
            @PathVariable UUID publicId,
            @Valid @RequestBody BulkSuspendListingsRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {

        RealtyGroup group = groupRepo.findByPublicId(publicId)
            .orElseThrow(() -> new RealtyGroupNotFoundException(publicId));

        Long linkedGroupSuspensionId = null;
        if (body.groupSuspensionPublicId() != null) {
            RealtyGroupSuspension linked = suspensionRepo.findByPublicId(body.groupSuspensionPublicId())
                .orElseThrow(() -> new SuspensionNotFoundException(body.groupSuspensionPublicId()));
            linkedGroupSuspensionId = linked.getId();
        }

        BulkSuspendResult result = bulkService.suspendAll(
            group.getId(),
            admin.userId(),
            body.reason(),
            linkedGroupSuspensionId);
        return new BulkSuspendResultDto(result.bulkActionId(), result.suspendedCount());
    }

    @PostMapping("/reinstate-all")
    @Transactional
    public ReinstateResultDto reinstateAll(
            @PathVariable UUID publicId,
            @Valid @RequestBody BulkReinstateListingsRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {

        RealtyGroup group = groupRepo.findByPublicId(publicId)
            .orElseThrow(() -> new RealtyGroupNotFoundException(publicId));

        int reinstated = bulkService.reinstateAll(group.getId(), admin.userId(), body.notes());
        return new ReinstateResultDto(reinstated);
    }
}
