package com.slparcelauctions.backend.common;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * Stable JSON envelope for paginated endpoints.
 *
 * <p>Returning {@link org.springframework.data.domain.Page Page&lt;T&gt;}
 * (i.e. {@link org.springframework.data.domain.PageImpl}) from a controller
 * trips Spring Data 3.3+'s "Serializing PageImpl instances as-is is not
 * supported, meaning that there is no guarantee about the stability of the
 * resulting JSON structure!" warning. Spring Data's alternative — enabling
 * {@code VIA_DTO} globally — nests the page metadata under a {@code page}
 * sub-object, which would break the flat {@code {content, totalElements,
 * totalPages, number, size}} contract that the frontend's
 * {@code Page<T>} type (see {@code frontend/src/types/page.ts}) and every
 * existing integration test assert against.
 *
 * <p>This record pins the flat shape explicitly. Every paginated controller
 * should return {@code PagedResponse<T>} (via {@link #from(Page)}) rather
 * than {@code Page<T>} so the JSON contract stays deterministic across
 * Spring Data upgrades.
 *
 * @param content       the page's items
 * @param totalElements total across all pages for the query
 * @param totalPages    total page count for the query
 * @param number        0-based index of this page
 * @param size          page size used to compute this response
 */
public record PagedResponse<T>(
        List<T> content,
        long totalElements,
        int totalPages,
        int number,
        int size) {

    public static <T> PagedResponse<T> from(Page<T> page) {
        return new PagedResponse<>(
                page.getContent(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.getNumber(),
                page.getSize());
    }
}
