package com.slparcelauctions.backend.admin;

import java.util.Arrays;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.admin.dto.AdminFraudFlagDetailDto;
import com.slparcelauctions.backend.admin.dto.AdminFraudFlagSummaryDto;
import com.slparcelauctions.backend.admin.dto.ResolveFraudFlagRequest;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.common.PagedResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/fraud-flags")
@RequiredArgsConstructor
public class AdminFraudFlagController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminFraudFlagService service;

    @GetMapping
    public PagedResponse<AdminFraudFlagSummaryDto> list(
            @RequestParam(defaultValue = "open") String status,
            @RequestParam(required = false) String reasons,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        int clampedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        List<FraudFlagReason> parsedReasons = parseReasons(reasons);
        return service.list(status, parsedReasons,
            PageRequest.of(page, clampedSize, Sort.by(Sort.Direction.DESC, "detectedAt")));
    }

    @GetMapping("/{id}")
    public AdminFraudFlagDetailDto detail(@PathVariable("id") Long id) {
        return service.detail(id);
    }

    @PostMapping("/{id}/dismiss")
    public AdminFraudFlagDetailDto dismiss(
            @PathVariable("id") Long id,
            @Valid @RequestBody ResolveFraudFlagRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        return service.dismiss(id, admin.userId(), body.adminNotes());
    }

    @PostMapping("/{id}/reinstate")
    public AdminFraudFlagDetailDto reinstate(
            @PathVariable("id") Long id,
            @Valid @RequestBody ResolveFraudFlagRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        return service.reinstate(id, admin.userId(), body.adminNotes());
    }

    private List<FraudFlagReason> parseReasons(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(FraudFlagReason::valueOf)
            .toList();
    }
}
