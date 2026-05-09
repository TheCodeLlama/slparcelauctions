package com.slparcelauctions.backend.admin.listings;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.admin.listings.dto.AdminListingActionRequest;
import com.slparcelauctions.backend.admin.listings.dto.AdminListingFilterParams;
import com.slparcelauctions.backend.admin.listings.dto.AdminListingRowDto;
import com.slparcelauctions.backend.admin.listings.dto.SetFeaturedRequest;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.common.PagedResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Admin listings table — list endpoint plus four standalone moderation
 * actions decoupled from reports. URL paths use the auction's
 * {@code publicId} (UUID); internal lookups happen via
 * {@link AdminListingService#warn} etc.
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-05-05-admin-listings-table-design.md}.
 */
@RestController
@RequestMapping("/api/v1/admin/listings")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminListingController {

    private final AdminListingService service;

    @GetMapping
    public PagedResponse<AdminListingRowDto> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) List<AuctionStatus> status,
            @RequestParam(required = false) Boolean hasReserve,
            @RequestParam(required = false) Boolean featured,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        AdminListingFilterParams params = new AdminListingFilterParams(
            normalize(search),
            status == null ? List.of() : status,
            hasReserve,
            featured
        );
        Page<AdminListingRowDto> result = service.list(params, PageRequest.of(page, size, parseSort(sort)));
        return PagedResponse.from(result);
    }

    @PostMapping("/{publicId}/warn")
    public ResponseEntity<Void> warn(
            @PathVariable UUID publicId,
            @Valid @RequestBody AdminListingActionRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        service.warn(publicId, admin.userId(), body.notes());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{publicId}/suspend")
    public ResponseEntity<Void> suspend(
            @PathVariable UUID publicId,
            @Valid @RequestBody AdminListingActionRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        service.suspend(publicId, admin.userId(), body.notes());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{publicId}/cancel")
    public ResponseEntity<Void> cancel(
            @PathVariable UUID publicId,
            @Valid @RequestBody AdminListingActionRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        service.cancel(publicId, admin.userId(), body.notes());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{publicId}/reinstate")
    public ResponseEntity<Void> reinstate(
            @PathVariable UUID publicId,
            @Valid @RequestBody AdminListingActionRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        service.reinstate(publicId, admin.userId(), body.notes());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{publicId}/featured")
    public AdminListingRowDto setFeatured(
            @PathVariable UUID publicId,
            @Valid @RequestBody SetFeaturedRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        return service.setFeatured(publicId, admin.userId(), body);
    }

    /** Trims whitespace and treats empty/blank strings as null. */
    private static String normalize(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Parses the {@code ?sort=property,direction[;property,direction...]}
     * shape used by the frontend. Multiple sorts can be chained with
     * {@code ;}. Direction defaults to ASC. The repository's whitelist
     * rejects unknown property names with {@code INVALID_SORT_COLUMN}.
     */
    private static Sort parseSort(String sortParam) {
        if (sortParam == null || sortParam.isBlank()) {
            return Sort.unsorted();
        }
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
