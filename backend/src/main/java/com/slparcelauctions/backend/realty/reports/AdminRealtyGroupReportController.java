package com.slparcelauctions.backend.realty.reports;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.realty.reports.dto.AdminDismissReportRequest;
import com.slparcelauctions.backend.realty.reports.dto.AdminReportDetailDto;
import com.slparcelauctions.backend.realty.reports.dto.AdminReportRowDto;
import com.slparcelauctions.backend.realty.reports.dto.AdminResolveReportRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Admin endpoints for triaging user-filed reports against realty groups. Spec §6.5.
 *
 * <p>Four operations under {@code /api/v1/admin/realty-groups/reports}:
 * <ul>
 *   <li>{@code GET /} — paginated queue. Optional {@code status} filter
 *       ({@link RealtyGroupReportStatus}); default sort is {@code createdAt DESC} so
 *       freshest reports surface first. Returns {@code Page<AdminReportRowDto>}.</li>
 *   <li>{@code GET /{publicId}} — full report detail. Returns
 *       {@link AdminReportDetailDto}.</li>
 *   <li>{@code POST /{publicId}/resolve} — body {@link AdminResolveReportRequest}.
 *       Flips OPEN to RESOLVED, stamps resolver/notes, and writes a
 *       {@code REALTY_GROUP_REPORT_RESOLVE} admin action via the service. The
 *       request's {@code escalateTo} field is purely informational — the
 *       controller does NOT itself issue a suspension/ban. The frontend uses the
 *       field to decide whether to immediately open a suspension modal.</li>
 *   <li>{@code POST /{publicId}/dismiss} — body {@link AdminDismissReportRequest}.
 *       Flips OPEN to DISMISSED, stamps resolver/notes, increments the reporter's
 *       {@code dismissedReportsCount}, and writes a
 *       {@code REALTY_GROUP_REPORT_DISMISS} admin action via the service.</li>
 * </ul>
 *
 * <p>{@code @PreAuthorize("hasRole('ADMIN')")} is defense-in-depth alongside the
 * blanket {@code /api/v1/admin/**} rule in {@code SecurityConfig}. Service-layer
 * exceptions ({@link com.slparcelauctions.backend.realty.reports.exception.ReportNotFoundException})
 * are mapped to ProblemDetail responses by {@code RealtyExceptionHandler}.
 *
 * <p>Sub-project F spec §6.5, §12.2, §12.3.
 */
@RestController
@RequestMapping("/api/v1/admin/realty-groups/reports")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminRealtyGroupReportController {

    private final RealtyGroupReportService service;
    private final ReportDtoMapper mapper;

    @GetMapping
    @Transactional(readOnly = true)
    public Page<AdminReportRowDto> list(
            @RequestParam(name = "status", required = false) RealtyGroupReportStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                Pageable pageable) {
        return service.findAll(status, pageable).map(mapper::toRow);
    }

    @GetMapping("/{publicId}")
    @Transactional(readOnly = true)
    public AdminReportDetailDto detail(@PathVariable UUID publicId) {
        return mapper.toDetail(service.find(publicId));
    }

    @PostMapping("/{publicId}/resolve")
    @Transactional
    public AdminReportDetailDto resolve(
            @PathVariable UUID publicId,
            @Valid @RequestBody AdminResolveReportRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        // NB: body.escalateTo() is intentionally NOT acted on here — the field is
        // informational, telling the frontend whether to chain into a suspension
        // modal next. The backend just resolves; suspension is a separate endpoint.
        RealtyGroupReport saved = service.resolve(publicId, admin.userId(), body.notes());
        return mapper.toDetail(saved);
    }

    @PostMapping("/{publicId}/dismiss")
    @Transactional
    public AdminReportDetailDto dismiss(
            @PathVariable UUID publicId,
            @Valid @RequestBody AdminDismissReportRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        RealtyGroupReport saved = service.dismiss(publicId, admin.userId(), body.notes());
        return mapper.toDetail(saved);
    }
}
