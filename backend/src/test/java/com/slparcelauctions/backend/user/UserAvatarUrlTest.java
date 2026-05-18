package com.slparcelauctions.backend.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Locks the single source of truth for user-avatar URLs. The endpoint is
 * {@code GET /api/v1/users/{publicId}/avatar/{size}} with a {@code UUID}
 * path variable — a numeric DB id produces a 404 (Spring cannot parse
 * "42" as a UUID) and a broken {@code <img>}. The helper's {@code UUID}
 * parameter makes passing a {@code Long} a compile error; these tests pin
 * the exact string shape and null semantics.
 */
class UserAvatarUrlTest {

    @Test
    void forUser_buildsPublicIdAvatarPath() {
        UUID publicId = UUID.fromString("11111111-2222-3333-4444-555555555555");

        assertThat(UserAvatarUrl.forUser(publicId, 256))
                .isEqualTo("/api/v1/users/11111111-2222-3333-4444-555555555555/avatar/256");
    }

    @Test
    void forUser_honoursRequestedSize() {
        UUID publicId = UUID.randomUUID();

        assertThat(UserAvatarUrl.forUser(publicId, 512))
                .isEqualTo("/api/v1/users/" + publicId + "/avatar/512");
    }

    @Test
    void forUser_defaultSizeOverloadUses256() {
        UUID publicId = UUID.randomUUID();

        assertThat(UserAvatarUrl.forUser(publicId))
                .isEqualTo("/api/v1/users/" + publicId + "/avatar/256");
    }

    @Test
    void forUserOrNull_returnsNullForNullPublicId() {
        assertThat(UserAvatarUrl.forUserOrNull(null)).isNull();
        assertThat(UserAvatarUrl.forUserOrNull(null, 256)).isNull();
    }

    @Test
    void forUserOrNull_buildsUrlForNonNullPublicId() {
        UUID publicId = UUID.randomUUID();

        assertThat(UserAvatarUrl.forUserOrNull(publicId))
                .isEqualTo("/api/v1/users/" + publicId + "/avatar/256");
        assertThat(UserAvatarUrl.forUserOrNull(publicId, 128))
                .isEqualTo("/api/v1/users/" + publicId + "/avatar/128");
    }

    @Test
    void producedPathSegmentParsesAsUuidNotNumericId() {
        UUID publicId = UUID.randomUUID();

        String url = UserAvatarUrl.forUser(publicId, 256);
        // /api/v1/users/<segment>/avatar/256  -> segment must be a UUID.
        String idSegment = url.substring(
                "/api/v1/users/".length(), url.indexOf("/avatar/"));

        assertThat(UUID.fromString(idSegment)).isEqualTo(publicId);
    }
}
