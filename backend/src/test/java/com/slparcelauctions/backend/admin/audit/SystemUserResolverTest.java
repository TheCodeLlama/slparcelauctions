package com.slparcelauctions.backend.admin.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Unit tests for {@link SystemUserResolver}. The resolver is a one-method bean that
 * reads a configured user id and looks the row up via {@link UserRepository#findById};
 * the only two paths to cover are "row exists" (return it) and "row missing" (throw a
 * loud {@link IllegalStateException} so the misconfiguration surfaces in logs).
 */
@ExtendWith(MockitoExtension.class)
class SystemUserResolverTest {

    @Mock UserRepository userRepo;

    SystemUserResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new SystemUserResolver(userRepo);
        ReflectionTestUtils.setField(resolver, "systemUserId", 1L);
    }

    @Test
    void getSystemUser_returnsConfiguredUser() {
        // Identity-only stub — the resolver returns whatever the repo returned. No
        // need to mutate the id; the test only asserts referential equality.
        User u = User.builder().username("system").build();
        when(userRepo.findById(1L)).thenReturn(Optional.of(u));

        User result = resolver.getSystemUser();

        assertThat(result).isSameAs(u);
    }

    @Test
    void getSystemUser_throwsIfMissing() {
        when(userRepo.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.getSystemUser())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("System user not seeded")
            .hasMessageContaining("id=1");
    }
}
