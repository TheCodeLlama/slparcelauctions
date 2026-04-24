package com.slparcelauctions.backend.auction.search;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * Paginated-plus-meta envelope for /auctions/search and
 * /me/saved/auctions. Extends the flat {content, totalElements,
 * totalPages, number, size} shape of {@link
 * com.slparcelauctions.backend.common.PagedResponse} with a {@code meta}
 * field for {@code sortApplied} + {@code nearRegionResolved}.
 *
 * <p>Does NOT re-use PagedResponse via composition because existing
 * frontend {@code Page<T>} type assertions check for field-level presence —
 * nesting the envelope would break them. Prefer a flat, purpose-built
 * record.
 */
public record SearchPagedResponse<T>(
        List<T> content,
        long totalElements,
        int totalPages,
        int number,
        int size,
        SearchMeta meta) {

    public static <T> SearchPagedResponse<T> from(Page<T> page, SearchMeta meta) {
        return new SearchPagedResponse<>(
                page.getContent(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.getNumber(),
                page.getSize(),
                meta);
    }
}
