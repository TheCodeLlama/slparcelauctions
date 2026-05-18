package com.slparcelauctions.backend.admin.escrowreview;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.escrow.review.ManualReviewStatus;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Admin escrow manual-review queue API (spec §7). Mirrors
 * {@code AdminDisputeController}. {@code /api/v1/admin/**} is already gated to
 * {@code hasRole('ADMIN')} by {@code SecurityConfig}; the class-level
 * {@code @PreAuthorize} is defence in depth. Path params are the review
 * {@code publicId} (UUID) per the BaseEntity wire convention.
 */
@RestController
@RequestMapping("/api/v1/admin/escrow-reviews")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminEscrowReviewController {

    private final AdminEscrowReviewService service;

    @GetMapping
    public Page<AdminEscrowReviewRow> list(
            @RequestParam(required = false) ManualReviewStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(status, page, size);
    }

    @GetMapping("/{publicId}")
    public AdminEscrowReviewDetail detail(@PathVariable UUID publicId) {
        return service.detail(publicId);
    }

    @PostMapping("/{publicId}/resolve")
    public AdminEscrowReviewResolveResponse resolve(
            @PathVariable UUID publicId,
            @Valid @RequestBody AdminEscrowReviewResolveRequest body,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return service.resolve(publicId, body, principal.userId());
    }
}
