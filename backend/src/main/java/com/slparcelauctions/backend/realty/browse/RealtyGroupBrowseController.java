package com.slparcelauctions.backend.realty.browse;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.realty.browse.dto.RealtyGroupCardDto;

import lombok.RequiredArgsConstructor;

/**
 * Public browse endpoint for the {@code /groups} directory page. Spec
 * section 6.1. Anonymous-accessible; SecurityConfig permits {@code GET
 * /api/v1/realty-groups}. Verified-only + non-suspended filters live in the
 * underlying query so they are impossible to disable from the wire.
 *
 * <p>Sort defaults to {@link GroupsSortKey#RATING}. {@code size} is clamped
 * to {@value #MAX_PAGE_SIZE} to bound response size and DB cost; {@code page}
 * is floored at 0. Bean-validation on the enum-typed {@code sort} parameter
 * is delegated to Spring's argument-resolution layer: an unknown value yields
 * a 400 via the framework's default conversion-error handler.
 */
@RestController
@RequestMapping("/api/v1/realty-groups")
@RequiredArgsConstructor
public class RealtyGroupBrowseController {

    static final int MAX_PAGE_SIZE = 60;
    static final int DEFAULT_PAGE_SIZE = 20;

    private final RealtyGroupBrowseService service;

    @GetMapping
    public Page<RealtyGroupCardDto> list(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "page", required = false, defaultValue = "0") int page,
            @RequestParam(name = "size", required = false, defaultValue = "20") int size,
            @RequestParam(name = "sort", required = false, defaultValue = "RATING") GroupsSortKey sort) {
        int effectivePage = Math.max(0, page);
        int effectiveSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        return service.browse(
            (q != null && !q.isBlank()) ? q.trim() : null,
            sort,
            PageRequest.of(effectivePage, effectiveSize));
    }
}
