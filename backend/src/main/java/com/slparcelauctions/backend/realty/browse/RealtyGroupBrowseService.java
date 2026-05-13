package com.slparcelauctions.backend.realty.browse;

import java.time.ZoneOffset;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
    public Page<RealtyGroupCardDto> browse(String q, GroupsSortKey sort, Pageable pageable) {
        Pageable sorted = applySort(pageable, sort);
        return repo.browseCards(q, sorted).map(this::toDto);
    }

    private Pageable applySort(Pageable original, GroupsSortKey sort) {
        Sort.Order primary = switch (sort) {
            case RATING -> new Sort.Order(Sort.Direction.DESC, "averageRating", Sort.NullHandling.NULLS_LAST);
            case NEWEST -> Sort.Order.desc("createdAt");
            case MOST_ACTIVE_LISTINGS -> Sort.Order.desc("activeListings");
            case MOST_SALES -> Sort.Order.desc("completedSales");
        };
        Sort.Order tiebreak = Sort.Order.asc("name");
        return PageRequest.of(
            original.getPageNumber(),
            original.getPageSize(),
            Sort.by(primary, tiebreak));
    }

    private RealtyGroupCardDto toDto(RealtyGroupCardProjection p) {
        return new RealtyGroupCardDto(
            p.getPublicId(),
            p.getName(),
            p.getSlug(),
            tagline(p.getDescription()),
            logoUrlFor(p),
            coverUrlFor(p),
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

    private static String logoUrlFor(RealtyGroupCardProjection p) {
        if (p.getLogoObjectKey() == null) return null;
        return "/api/v1/realty-groups/" + p.getPublicId() + "/logo/image";
    }

    private static String coverUrlFor(RealtyGroupCardProjection p) {
        if (p.getCoverObjectKey() == null) return null;
        return "/api/v1/realty-groups/" + p.getPublicId() + "/cover/image";
    }
}
