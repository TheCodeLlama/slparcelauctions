package com.slparcelauctions.backend.admin.reports;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.admin.reports.dto.MyReportResponse;
import com.slparcelauctions.backend.auth.AuthPrincipal;

import lombok.RequiredArgsConstructor;

/**
 * Reporter-visibility surface — every report (listing or realty-group) the caller
 * has filed, newest first.
 *
 * <p>Sub-project F Slice 4 (Task 19) extension: the response envelope unions both
 * kinds via {@link MyReportResponse#entityType()} and
 * {@link MyReportResponse#entityPublicId()} so the frontend can render either kind
 * in one timeline without an extra round-trip to discover which list to query.
 *
 * <p>Sub-project F spec §12.4.
 */
@RestController
@RequestMapping("/api/v1/me/reports")
@RequiredArgsConstructor
public class MyReportsController {

    private final UserReportService service;

    @GetMapping
    public List<MyReportResponse> list(@AuthenticationPrincipal AuthPrincipal principal) {
        return service.findMyReports(principal.userId());
    }
}
