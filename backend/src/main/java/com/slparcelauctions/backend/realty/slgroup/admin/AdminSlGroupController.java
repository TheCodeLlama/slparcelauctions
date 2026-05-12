package com.slparcelauctions.backend.realty.slgroup.admin;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.realty.slgroup.SlGroupReverifyResult;
import com.slparcelauctions.backend.realty.slgroup.admin.dto.AckDriftRequest;
import com.slparcelauctions.backend.realty.slgroup.admin.dto.AdminSlGroupRowDto;
import com.slparcelauctions.backend.realty.slgroup.admin.dto.ForceUnregisterRequest;
import com.slparcelauctions.backend.realty.slgroup.admin.dto.SlGroupRecheckResultDto;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Admin SL group moderation surface. Three operations bound to a specific
 * {@code realty_group_sl_groups} row, addressed by its public id under the
 * parent realty group's URL:
 *
 * <ul>
 *   <li>{@code POST /recheck} — synchronous reverify pass against the SL World
 *       API. Returns 200 with a {@link SlGroupRecheckResultDto} capturing the
 *       drift outcome. Writes a
 *       {@link com.slparcelauctions.backend.admin.audit.AdminActionType#REALTY_GROUP_SL_GROUP_RECHECK}
 *       audit row.</li>
 *   <li>{@code POST /ack-drift} — clears the row's drift fields and stamps the
 *       acknowledging admin. Rolls the founder snapshot forward if the
 *       currently observed founder is a non-null new value (admin implicitly
 *       accepts the new founder). Returns 200 with an
 *       {@link AdminSlGroupRowDto} showing the post-ack state. Returns 409
 *       {@code NO_DRIFT_DETECTED} when the row is not currently flagged
 *       drifted.</li>
 *   <li>{@code DELETE /} — unregister the row. The {@code ?force} query param
 *       selects between two paths:
 *       <ul>
 *         <li>{@code force=false} (default) — delegates to
 *             {@link com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroupService#unregister
 *             RealtyGroupSlGroupService.unregister}, which respects the
 *             active-listings gate.</li>
 *         <li>{@code force=true} — delegates to
 *             {@link com.slparcelauctions.backend.realty.slgroup.SlGroupForceUnregisterService#forceUnregister
 *             SlGroupForceUnregisterService.forceUnregister}, which bypasses the
 *             gate and cascades any in-flight case-3 listings into the bulk-
 *             suspend pipeline.</li>
 *       </ul>
 *       Returns 204 on success. The request body's {@code reason} is required on
 *       both paths and is recorded on the row + audit details for the force
 *       path; the non-force path drops it (the underlying service was authored
 *       pre-F and doesn't accept a reason argument).</li>
 * </ul>
 *
 * <p>{@code @PreAuthorize("hasRole('ADMIN')")} is defense-in-depth alongside the
 * blanket {@code /api/v1/admin/**} rule in {@code SecurityConfig}. Service-layer
 * exceptions are mapped to {@code ProblemDetail} responses by
 * {@code RealtyExceptionHandler}.
 *
 * <p>Sub-project F spec §6.6, §13.3, §13.4, §13.5.
 */
@RestController
@RequestMapping("/api/v1/admin/realty-groups/{publicId}/sl-groups/{slGroupPublicId}")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminSlGroupController {

    private final AdminSlGroupService service;

    @PostMapping("/recheck")
    public SlGroupRecheckResultDto recheck(
            @PathVariable UUID publicId,
            @PathVariable UUID slGroupPublicId,
            @AuthenticationPrincipal AuthPrincipal admin) {
        SlGroupReverifyResult result = service.recheck(publicId, slGroupPublicId, admin.userId());
        return new SlGroupRecheckResultDto(
            result.driftDetected(),
            result.driftReason(),
            result.currentFounderUuid());
    }

    @PostMapping("/ack-drift")
    public AdminSlGroupRowDto ackDrift(
            @PathVariable UUID publicId,
            @PathVariable UUID slGroupPublicId,
            @Valid @RequestBody AckDriftRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        service.ackDrift(publicId, slGroupPublicId, admin.userId(), body.notes());
        return service.loadAdminRow(publicId, slGroupPublicId);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregister(
            @PathVariable UUID publicId,
            @PathVariable UUID slGroupPublicId,
            @RequestParam(name = "force", defaultValue = "false") boolean force,
            @Valid @RequestBody ForceUnregisterRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        if (force) {
            service.forceUnregister(publicId, slGroupPublicId, admin.userId(), body.reason());
        } else {
            service.unregister(publicId, slGroupPublicId, admin.userId());
        }
    }
}
