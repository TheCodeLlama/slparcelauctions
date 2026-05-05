package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.user.Role;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthPrincipalTest {

    @Test
    void record_exposesAllFiveAccessors() {
        UUID publicId = UUID.randomUUID();
        AuthPrincipal principal = new AuthPrincipal(42L, publicId, "user@example.com", 7L, Role.USER);

        assertThat(principal.userId()).isEqualTo(42L);
        assertThat(principal.userPublicId()).isEqualTo(publicId);
        assertThat(principal.username()).isEqualTo("user@example.com");
        assertThat(principal.tokenVersion()).isEqualTo(7L);
        assertThat(principal.role()).isEqualTo(Role.USER);
    }

    @Test
    void record_equalsAndHashCodeAreValueBased() {
        UUID uuid = UUID.randomUUID();
        AuthPrincipal a = new AuthPrincipal(1L, uuid, "a@example.com", 0L, Role.USER);
        AuthPrincipal b = new AuthPrincipal(1L, uuid, "a@example.com", 0L, Role.USER);
        AuthPrincipal c = new AuthPrincipal(2L, UUID.randomUUID(), "a@example.com", 0L, Role.USER);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }
}
