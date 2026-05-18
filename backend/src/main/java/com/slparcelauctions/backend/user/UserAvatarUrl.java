package com.slparcelauctions.backend.user;

import java.util.UUID;

/**
 * Single source of truth for the relative user-avatar URL emitted on every
 * public/admin DTO. The serving endpoint is
 * {@code GET /api/v1/users/{publicId}/avatar/{size}} in
 * {@link UserController}, whose {@code publicId} path variable is a
 * {@code UUID}.
 *
 * <p>History: the BaseEntity-UUID refactor (commit {@code 8f6170d6})
 * migrated the endpoint to a {@code UUID} path variable, but several DTO
 * mappers kept hand-rolled {@code "/api/v1/users/" + getId() + "/avatar/256"}
 * builders feeding the numeric DB {@code id}. Spring then failed to parse
 * e.g. "42" as a UUID, returned 404, and the {@code <img>} fell back to
 * alt-text in production (broken reviewer avatars).
 *
 * <p>This helper makes that mistake impossible: it takes a {@code UUID},
 * so handing it a {@code Long}/{@code long} DB id is a compile error.
 * Every avatar-URL builder routes through here with {@code getPublicId()}.
 * {@code forUserOrNull} preserves the per-DTO "null publicId → null
 * avatarUrl" semantics the call sites already had.
 */
public final class UserAvatarUrl {

    /** The default avatar size every legacy call site requested. */
    public static final int DEFAULT_SIZE = 256;

    private UserAvatarUrl() {}

    /**
     * Relative avatar URL for a known, non-null user {@code publicId} at
     * {@link #DEFAULT_SIZE}.
     */
    public static String forUser(UUID publicId) {
        return forUser(publicId, DEFAULT_SIZE);
    }

    /**
     * Relative avatar URL for a known, non-null user {@code publicId} at
     * the requested square pixel {@code size}.
     */
    public static String forUser(UUID publicId, int size) {
        return "/api/v1/users/" + publicId + "/avatar/" + size;
    }

    /**
     * Null-tolerant variant: returns {@code null} when {@code publicId} is
     * {@code null} (mirrors the existing per-DTO {@code publicId == null ?
     * null : ...} guards), otherwise {@link #forUser(UUID)}.
     */
    public static String forUserOrNull(UUID publicId) {
        return publicId == null ? null : forUser(publicId);
    }

    /**
     * Null-tolerant variant at an explicit {@code size}: {@code null} when
     * {@code publicId} is {@code null}, otherwise {@link #forUser(UUID, int)}.
     */
    public static String forUserOrNull(UUID publicId, int size) {
        return publicId == null ? null : forUser(publicId, size);
    }
}
