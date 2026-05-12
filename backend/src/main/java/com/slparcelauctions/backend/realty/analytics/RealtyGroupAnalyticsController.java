package com.slparcelauctions.backend.realty.analytics;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.realty.analytics.dto.MemberCommissionRowDto;

import lombok.RequiredArgsConstructor;

/**
 * Leader-side commission analytics surface (spec §6.8).
 *
 * <p>Lives in {@code com.slparcelauctions.backend.realty.analytics}, a sub-package of
 * {@code com.slparcelauctions.backend.realty} — within the scope of
 * {@link com.slparcelauctions.backend.realty.exception.RealtyExceptionHandler}, so 404 /
 * 403 responses are formatted consistently with the rest of the realty surface.
 */
@RestController
@RequestMapping("/api/v1/realty-groups/{publicId}/analytics")
@RequiredArgsConstructor
public class RealtyGroupAnalyticsController {

    private final GroupCommissionAnalyticsService analyticsService;

    @GetMapping("/commissions")
    public List<MemberCommissionRowDto> getCommissions(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal AuthPrincipal caller) {
        return analyticsService.compute(publicId, caller.userId());
    }
}
