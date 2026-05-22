package com.slparcelauctions.backend.realty.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Compact summary card for "my groups" lists (dashboard + user-profile aside).
 *
 * <p>Carries enough to render a clickable chip: name, slug (for routing to
 * {@code /group/[slug]}), logo, and the group's age. {@code memberSince} is the
 * group's creation date in the public dto sense; on the user-profile aside it
 * acts as a stable "since" badge.
 *
 * <p>Logo URLs are dual light/dark (plan Task 2). Either may be {@code null}
 * when no upload exists for that variant; the frontend's {@code ThemedImage}
 * helper falls back to whichever sibling slot is populated.
 */
public record RealtyGroupSummaryDto(
    UUID publicId,
    String name,
    String slug,
    String logoLightUrl,
    String logoDarkUrl,
    int memberCount,
    OffsetDateTime memberSince
) {}
