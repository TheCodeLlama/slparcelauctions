package com.slparcelauctions.backend.admin.reports;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.admin.reports.dto.AdminReportActionRequest;
import com.slparcelauctions.backend.admin.reports.dto.AdminReportDetailDto;
import com.slparcelauctions.backend.admin.reports.dto.AdminReportListingRowDto;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.common.PagedResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/reports")
@RequiredArgsConstructor
public class AdminReportController {

    private final AdminReportService service;

    @GetMapping
    public PagedResponse<AdminReportListingRowDto> list(
            @RequestParam(defaultValue = "open") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        int clampedSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(page, clampedSize);
        if ("all".equalsIgnoreCase(status)) {
            return service.listAllGrouped(pageable);
        }
        ListingReportStatus filter = "reviewed".equalsIgnoreCase(status)
            ? ListingReportStatus.REVIEWED
            : ListingReportStatus.OPEN;
        return service.listGrouped(filter, pageable);
    }

    @GetMapping("/listing/{auctionId}")
    public List<AdminReportDetailDto> findByListing(@PathVariable Long auctionId) {
        return service.findByListing(auctionId);
    }

    @GetMapping("/{id}")
    public AdminReportDetailDto findOne(@PathVariable Long id) {
        return service.findOne(id);
    }

    @PostMapping("/{id}/dismiss")
    public AdminReportDetailDto dismiss(
            @PathVariable Long id,
            @Valid @RequestBody AdminReportActionRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        return service.dismiss(id, admin.userId(), body.notes());
    }

    @PostMapping("/listing/{auctionId}/warn-seller")
    public void warnSeller(
            @PathVariable Long auctionId,
            @Valid @RequestBody AdminReportActionRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        service.warnSeller(auctionId, admin.userId(), body.notes());
    }

    @PostMapping("/listing/{auctionId}/suspend")
    public void suspend(
            @PathVariable Long auctionId,
            @Valid @RequestBody AdminReportActionRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        service.suspend(auctionId, admin.userId(), body.notes());
    }

    @PostMapping("/listing/{auctionId}/cancel")
    public void cancel(
            @PathVariable Long auctionId,
            @Valid @RequestBody AdminReportActionRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        service.cancel(auctionId, admin.userId(), body.notes());
    }
}
