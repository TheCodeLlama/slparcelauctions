package com.slparcelauctions.backend.realty.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Compact summary card for "my groups" lists (dashboard + user-profile aside).
 *
 * <p>Carries enough to render a clickable chip: name, slug (for routing to
 * {@code /group/[slug]}), logo, and the caller's join date. {@code memberSince} is the
 * group's creation date in the public dto sense; on the user-profile aside it acts as a
 * stable "since" badge.
 */
public record RealtyGroupSummaryDto(
    UUID publicId,
    String name,
    String slug,
    String logoUrl,
    int memberCount,
    OffsetDateTime memberSince
) {}
