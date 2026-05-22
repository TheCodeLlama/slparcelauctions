package com.slparcelauctions.backend.realty.browse;

import java.time.ZoneOffset;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.JpaSort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.browse.dto.RealtyGroupCardDto;
import com.slparcelauctions.backend.realty.rating.dto.GroupRatingDto;

import lombok.RequiredArgsConstructor;

/**
 * Service backing the public {@code GET /api/v1/realty-groups} browse
 * endpoint. Translates the {@link GroupsSortKey} into a {@link Sort} order
 * the native query understands, calls
 * {@link RealtyGroupRepository#browseCards}, and maps each row from
 * {@link RealtyGroupCardProjection} to {@link RealtyGroupCardDto} with the
 * description truncated to {@value #TAGLINE_MAX_CHARS} chars + ellipsis.
 *
 * <p>Logo/cover URLs are built here from the projection's raw object keys
 * using the same pattern as {@code RealtyGroupDtoMapper}: object-key present
 * implies a public byte endpoint at
 * {@code /api/v1/realty-groups/{publicId}/{logo|cover}/image}; absent
 * object-key leaves the URL field {@code null}.
 *
 * <p>The projection's {@code createdAt} is an {@link java.time.Instant}
 * (Hibernate's default binding for {@code timestamptz} in native queries);
 * we widen to {@link java.time.OffsetDateTime} at UTC for the DTO's
 * {@code foundedAt} field.
 */
@Service
@RequiredArgsConstructor
public class RealtyGroupBrowseService {

    static final int TAGLINE_MAX_CHARS = 120;

    private final RealtyGroupRepository repo;

    @Transactional(readOnly = true)
    public Page<RealtyGroupCardDto> browse(
            String q,
            GroupsSortKey sort,
            Sort.Direction direction,
            double minRating,
            int minReviews,
            boolean activeOnly,
            Pageable pageable) {
        Pageable sorted = applySort(pageable, sort, direction);
        double safeMinRating = Math.max(0.0, minRating);
        int safeMinReviews = Math.max(0, minReviews);
        return repo
            .browseCards(q, safeMinRating, safeMinReviews, activeOnly, sorted)
            .map(this::toDto);
    }

    private Pageable applySort(Pageable original, GroupsSortKey sort, Sort.Direction direction) {
        // The browse SQL is a native query whose ORDER BY references SELECT
        // aliases ({@code averageRating}, {@code activeListings},
        // {@code completedSales}, {@code createdAt}). Plain
        // {@link Sort.Order} prefixes the property with the root table
        // alias ({@code g.}) which collides with the aliases (PostgreSQL
        // lowercases unquoted identifiers so {@code g.averageRating}
        // becomes {@code g.averagerating} and the table has no such
        // column). {@link JpaSort#unsafe} sidesteps the prefix when the
        // property string is not a plain identifier, so we wrap each
        // property in parentheses. PostgreSQL parses {@code ORDER BY
        // (averageRating)} the same way as {@code ORDER BY
        // averageRating}, resolving the unparenthesised inner reference
        // against the SELECT alias list.
        //
        // RATING ordering: {@code AVG(r.rating)} can be NULL when a group
        // has no reviews. PostgreSQL's default for {@code DESC} is NULLS
        // FIRST. We cannot apply {@code NULLS LAST} via an alias-based
        // expression in ORDER BY (PostgreSQL only resolves SELECT aliases
        // for the bare top-level sort key, not inside expressions). The
        // repo SQL would need a CTE or subquery wrapper to express this
        // cleanly; until then RATING sorts unreviewed groups first. The
        // tiebreaker on {@code name} (a real column) is still applied so
        // the page ordering remains stable across pagination.
        String primaryProp = switch (sort) {
            case RATING -> "(averageRating)";
            case NEWEST -> "(createdAt)";
            case MOST_ACTIVE_LISTINGS -> "(activeListings)";
            case MOST_SALES -> "(completedSales)";
        };
        Sort.Direction effective = direction == null ? Sort.Direction.DESC : direction;
        Sort sortSpec = JpaSort.unsafe(effective, primaryProp)
            .and(Sort.by(Sort.Order.asc("name")));
        return PageRequest.of(
            original.getPageNumber(),
            original.getPageSize(),
            sortSpec);
    }

    private RealtyGroupCardDto toDto(RealtyGroupCardProjection p) {
        return new RealtyGroupCardDto(
            p.getPublicId(),
            p.getName(),
            p.getSlug(),
            tagline(p.getDescription()),
            urlFor(p.getPublicId(), "logo", "light", p.getLogoLightObjectKey()),
            urlFor(p.getPublicId(), "logo", "dark", p.getLogoDarkObjectKey()),
            urlFor(p.getPublicId(), "cover", "light", p.getCoverLightObjectKey()),
            urlFor(p.getPublicId(), "cover", "dark", p.getCoverDarkObjectKey()),
            p.getCreatedAt() == null ? null : p.getCreatedAt().atOffset(ZoneOffset.UTC),
            p.getMemberCount(),
            p.getMemberSeatLimit(),
            (int) p.getActiveListings(),
            (int) p.getCompletedSales(),
            new GroupRatingDto(p.getAverageRating(), (long) p.getReviewCount()));
    }

    private static String tagline(String description) {
        if (description == null) return "";
        if (description.length() <= TAGLINE_MAX_CHARS) return description;
        return description.substring(0, TAGLINE_MAX_CHARS) + "...";
    }

    // Variant-aware URL builder (plan Task 2). Returns null when the slot's
    // object key is null so the frontend's ThemedImage helper can fall back
    // to the sibling variant.
    private static String urlFor(java.util.UUID publicId, String surface, String variant, String objectKey) {
        if (objectKey == null) return null;
        return "/api/v1/realty-groups/" + publicId + "/" + surface + "/image?variant=" + variant;
    }
}
