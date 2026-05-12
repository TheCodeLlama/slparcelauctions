package com.slparcelauctions.backend.realty.reports;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.realty.reports.dto.ReportDto;
import com.slparcelauctions.backend.realty.reports.dto.SubmitReportRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Public REST entry point for users to submit a report against a realty group. Wraps
 * {@link RealtyGroupReportService#submit(UUID, Long, RealtyGroupReportReason, String)}
 * with auth + validation + DTO mapping; every error path is mapped to a problem-detail
 * envelope by {@code RealtyExceptionHandler}.
 *
 * <p>Authentication is implicit — {@code /api/v1/realty-groups/**} writes fall through
 * the {@code SecurityConfig} authenticated catch-all (only the GET shapes are listed as
 * {@code permitAll}). Unauthenticated requests are rejected as 401 by the JWT filter
 * chain before this controller runs.
 *
 * <p>Sub-project F spec §6.1, §12.1.
 */
@RestController
@RequestMapping("/api/v1/realty-groups/{publicId}/reports")
@RequiredArgsConstructor
public class RealtyGroupReportController {

    private final RealtyGroupReportService service;

    /**
     * Create a new report. Returns the persisted row's public DTO with a 201 Created.
     *
     * <p>Failure surfaces (handled by {@code RealtyExceptionHandler}):
     * <ul>
     *   <li>404 {@code REALTY_GROUP_NOT_FOUND} — {@code publicId} resolves to no group.</li>
     *   <li>409 {@code ALREADY_REPORTED} — the caller has an open report against this group.</li>
     *   <li>409 {@code CANNOT_REPORT_OWN_GROUP} — the caller is a member of the group.</li>
     *   <li>429 {@code REPORT_RATE_LIMITED} — caller has exhausted today's shared quota.</li>
     * </ul>
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReportDto submit(
            @PathVariable UUID publicId,
            @Valid @RequestBody SubmitReportRequest req,
            @AuthenticationPrincipal AuthPrincipal reporter) {
        RealtyGroupReport report = service.submit(publicId, reporter.userId(), req.reason(), req.details());
        return ReportDto.from(report);
    }
}
