package com.slparcelauctions.backend.realty.dto;

import java.util.UUID;

/**
 * Compact representation of a group's leader on the public group page.
 *
 * <p>{@code avatarUrl} is the same relative {@code /api/v1/users/{publicId}/avatar/256} path
 * used everywhere else in the codebase; the frontend wraps it with {@code apiUrl(...)} so
 * the browser fetches it from the backend rather than the page origin. It is {@code null}
 * only when the upstream user row has no public id (defensive — every persisted user does).
 */
public record LeaderCardDto(
    UUID userPublicId,
    String displayName,
    String avatarUrl
) {}
