package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.user.Role;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AuthPrincipalTest {

    @Test
    void record_exposesAllThreeAccessors() {
        AuthPrincipal principal = new AuthPrincipal(42L, "user@example.com", 7L, Role.USER);

        assertThat(principal.userId()).isEqualTo(42L);
        assertThat(principal.email()).isEqualTo("user@example.com");
        assertThat(principal.tokenVersion()).isEqualTo(7L);
        assertThat(principal.role()).isEqualTo(Role.USER);
    }

    @Test
    void record_equalsAndHashCodeAreValueBased() {
        AuthPrincipal a = new AuthPrincipal(1L, "a@example.com", 0L, Role.USER);
        AuthPrincipal b = new AuthPrincipal(1L, "a@example.com", 0L, Role.USER);
        AuthPrincipal c = new AuthPrincipal(2L, "a@example.com", 0L, Role.USER);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }
}
