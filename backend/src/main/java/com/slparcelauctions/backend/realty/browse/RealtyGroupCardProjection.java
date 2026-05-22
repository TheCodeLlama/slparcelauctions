package com.slparcelauctions.backend.realty.browse;

import java.time.Instant;
import java.util.UUID;

/**
 * Native-query projection populated by the browse SQL in
 * {@code RealtyGroupRepository.browseCards}. Field names match the SQL column
 * aliases exactly so Spring Data's interface-based projection binds them by
 * setter convention.
 *
 * <p>{@code averageRating} is a {@link Double} (not {@code BigDecimal}) to match
 * the wire shape of {@link com.slparcelauctions.backend.realty.rating.dto.GroupRatingDto},
 * which is {@code record GroupRatingDto(Double averageRating, long reviewCount)}.
 * The underlying SQL casts {@code AVG(r.rating)} to {@code double precision} so
 * Hibernate binds it as {@link Double}.
 *
 * <p>Note on image columns: the plan/spec calls these {@code logoUrl} /
 * {@code coverUrl}, but the actual schema stores object keys
 * ({@code logo_light_object_key} / {@code cover_light_object_key} plus their
 * {@code _dark_} siblings and the {@code default_listing_*} pair) and the
 * URLs are computed at DTO-mapping time via the pattern in
 * {@code RealtyGroupDtoMapper} ({@code /api/v1/realty-groups/{publicId}/logo/image}
 * etc). The projection carries the raw object keys; the browse service builds the URL.
 *
 * <p>Note on {@code memberCount}: until {@code realty_groups.member_count} is
 * denormalised (plan Task 7), the SQL computes the count via a correlated
 * subquery against {@code realty_group_members}.
 */
public interface RealtyGroupCardProjection {
    UUID getPublicId();
    String getName();
    String getSlug();
    String getDescription();
    String getLogoLightObjectKey();
    String getLogoDarkObjectKey();
    String getCoverLightObjectKey();
    String getCoverDarkObjectKey();
    String getDefaultListingLightObjectKey();
    String getDefaultListingDarkObjectKey();
    Instant getCreatedAt();
    int getMemberCount();
    int getMemberSeatLimit();
    long getActiveListings();
    long getCompletedSales();
    Double getAverageRating();
    long getReviewCount();
}
