package com.slparcelauctions.backend.admin.reports;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.admin.reports.dto.MyReportResponse;
import com.slparcelauctions.backend.admin.reports.dto.ReportRequest;
import com.slparcelauctions.backend.auth.AuthPrincipal;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/auctions/{auctionId}")
@RequiredArgsConstructor
public class UserReportController {

    private final UserReportService service;

    @PostMapping("/report")
    public MyReportResponse report(
            @PathVariable Long auctionId,
            @Valid @RequestBody ReportRequest body,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return service.upsertReport(auctionId, principal.userId(), body);
    }

    @GetMapping("/my-report")
    public ResponseEntity<MyReportResponse> myReport(
            @PathVariable Long auctionId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return service.findMyReport(auctionId, principal.userId())
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.noContent().build());
    }
}
