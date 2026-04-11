package com.slparcelauctions.backend.auth;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AuthPrincipalTest {

    @Test
    void record_exposesAllThreeAccessors() {
        AuthPrincipal principal = new AuthPrincipal(42L, "user@example.com", 7L);

        assertThat(principal.userId()).isEqualTo(42L);
        assertThat(principal.email()).isEqualTo("user@example.com");
        assertThat(principal.tokenVersion()).isEqualTo(7L);
    }

    @Test
    void record_equalsAndHashCodeAreValueBased() {
        AuthPrincipal a = new AuthPrincipal(1L, "a@example.com", 0L);
        AuthPrincipal b = new AuthPrincipal(1L, "a@example.com", 0L);
        AuthPrincipal c = new AuthPrincipal(2L, "a@example.com", 0L);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }
}
