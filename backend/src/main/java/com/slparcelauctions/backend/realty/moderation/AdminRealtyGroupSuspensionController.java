package com.slparcelauctions.backend.realty.moderation;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.realty.moderation.dto.AdminLiftSuspensionRequest;
import com.slparcelauctions.backend.realty.moderation.dto.AdminSuspensionRequest;
import com.slparcelauctions.backend.realty.moderation.dto.SuspensionDto;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Admin endpoints for issuing, lifting, and listing suspensions and permanent bans
 * against realty groups. Spec §6.2, §9.
 *
 * <p>Three operations under
 * {@code /api/v1/admin/realty-groups/{publicId}/suspensions}:
 * <ul>
 *   <li>{@code GET /} — list suspension history for the group (newest first).</li>
 *   <li>{@code POST /} — issue a new suspension. Returns 201 with the created
 *       {@link SuspensionDto}. A null {@code expiresAt} on the request body means
 *       permanent ban.</li>
 *   <li>{@code DELETE /{suspensionPublicId}} — lift an active suspension.
 *       Returns 204 with no body. The {@code suspensionPublicId} must reference
 *       a suspension that belongs to {@code publicId}; mismatches surface as 404.</li>
 * </ul>
 *
 * <p>{@code @PreAuthorize("hasRole('ADMIN')")} is defense-in-depth alongside the
 * blanket {@code /api/v1/admin/**} rule in {@code SecurityConfig}. Service-layer
 * exceptions are mapped to ProblemDetail responses by {@code RealtyExceptionHandler}.
 */
@RestController
@RequestMapping("/api/v1/admin/realty-groups/{publicId}/suspensions")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminRealtyGroupSuspensionController {

    private final RealtyGroupSuspensionService service;
    private final SuspensionDtoMapper mapper;

    @GetMapping
    public List<SuspensionDto> list(@PathVariable UUID publicId) {
        return service.listHistory(publicId).stream()
            .map(mapper::toDto)
            .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SuspensionDto issue(
            @PathVariable UUID publicId,
            @Valid @RequestBody AdminSuspensionRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        RealtyGroupSuspension saved = service.issue(
            publicId,
            admin.userId(),
            body.reason(),
            body.notes(),
            body.expiresAt(),
            body.bulkSuspendListings());
        return mapper.toDto(saved);
    }

    @DeleteMapping("/{suspensionPublicId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void lift(
            @PathVariable UUID publicId,
            @PathVariable UUID suspensionPublicId,
            @Valid @RequestBody AdminLiftSuspensionRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        service.lift(
            publicId,
            suspensionPublicId,
            admin.userId(),
            body.notes(),
            body.bulkReinstateListings());
    }
}
