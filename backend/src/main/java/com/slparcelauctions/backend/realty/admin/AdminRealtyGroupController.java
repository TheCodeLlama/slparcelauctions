package com.slparcelauctions.backend.realty.admin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.common.PagedResponse;
import com.slparcelauctions.backend.realty.admin.AdminRealtyGroupService.StatusFilter;
import com.slparcelauctions.backend.realty.dto.RealtyGroupPublicDto;
import com.slparcelauctions.backend.realty.dto.RealtyGroupRowDto;
import com.slparcelauctions.backend.realty.dto.UpdateRealtyGroupRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Admin escape-hatch surface for realty groups. Spec §5.6.
 *
 * <p>Five endpoints under {@code /api/v1/admin/realty-groups}:
 * <ul>
 *   <li>{@code GET /} — paginated, filterable list ({@code status=active|dissolved|all},
 *       {@code search}, plus standard pagination params).</li>
 *   <li>{@code GET /{publicId}} — detail (includes dissolved).</li>
 *   <li>{@code PATCH /{publicId}} — force-edit, bypasses permission gates +
 *       30-day rename cooldown.</li>
 *   <li>{@code DELETE /{publicId}} — force-dissolve.</li>
 *   <li>{@code DELETE /{publicId}/members/{memberPublicId}} — force-remove a member.
 *       When the target is the leader, the {@code ?newLeaderPublicId=} query parameter
 *       names the replacement (must be a current member of the same group). If the
 *       target is the leader and no replacement is provided, the request is rejected
 *       — admin should force-dissolve instead.</li>
 * </ul>
 *
 * <p>{@code @PreAuthorize("hasRole('ADMIN')")} is defense-in-depth alongside the
 * blanket {@code /api/v1/admin/**} rule in {@code SecurityConfig}. Every write goes
 * through {@link AdminRealtyGroupService}, which records to the admin audit log and
 * fires the appropriate notifications.
 */
@RestController
@RequestMapping("/api/v1/admin/realty-groups")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminRealtyGroupController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminRealtyGroupService service;

    @GetMapping
    public PagedResponse<RealtyGroupRowDto> list(
            @RequestParam(defaultValue = "active") String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        StatusFilter filter = parseStatus(status);
        Page<RealtyGroupRowDto> result = service.adminListGroups(
            filter, search,
            PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), parseSort(sort)));
        return PagedResponse.from(result);
    }

    @GetMapping("/{publicId}")
    public RealtyGroupPublicDto detail(@PathVariable UUID publicId) {
        return service.adminGetGroup(publicId);
    }

    @PatchMapping("/{publicId}")
    public RealtyGroupPublicDto update(
            @PathVariable UUID publicId,
            @Valid @RequestBody UpdateRealtyGroupRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        return service.adminUpdateGroup(publicId, body, admin.userId());
    }

    @DeleteMapping("/{publicId}")
    public ResponseEntity<Void> dissolve(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal AuthPrincipal admin) {
        service.adminDissolveGroup(publicId, admin.userId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{publicId}/members/{memberPublicId}")
    public ResponseEntity<Void> removeMember(
            @PathVariable UUID publicId,
            @PathVariable UUID memberPublicId,
            @RequestParam(required = false) UUID newLeaderPublicId,
            @AuthenticationPrincipal AuthPrincipal admin) {
        service.adminRemoveMember(
            publicId,
            memberPublicId,
            Optional.ofNullable(newLeaderPublicId),
            admin.userId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Map the {@code ?status=} query parameter to the service-layer enum. Defaults to
     * {@code active}; any unrecognized value (including a misspelled one) falls back to
     * {@code active} rather than 400-ing — admins typing the URL by hand benefit from the
     * forgiving default.
     */
    private static StatusFilter parseStatus(String raw) {
        if (raw == null) return StatusFilter.ACTIVE;
        return switch (raw.trim().toLowerCase()) {
            case "dissolved" -> StatusFilter.DISSOLVED;
            case "all" -> StatusFilter.ALL;
            default -> StatusFilter.ACTIVE;
        };
    }

    /** Same {@code property,direction[;...]} parser used by {@code AdminListingController}. */
    private static Sort parseSort(String sortParam) {
        if (sortParam == null || sortParam.isBlank()) return Sort.unsorted();
        List<Sort.Order> orders = new java.util.ArrayList<>();
        for (String chunk : sortParam.split(";")) {
            String[] parts = chunk.trim().split(",");
            if (parts.length == 0 || parts[0].isBlank()) continue;
            Sort.Direction dir = parts.length > 1 && parts[1].trim().equalsIgnoreCase("desc")
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;
            orders.add(new Sort.Order(dir, parts[0].trim()));
        }
        return orders.isEmpty() ? Sort.unsorted() : Sort.by(orders);
    }
}
