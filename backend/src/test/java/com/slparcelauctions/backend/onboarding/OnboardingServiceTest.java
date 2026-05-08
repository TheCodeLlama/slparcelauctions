package com.slparcelauctions.backend.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class OnboardingServiceTest {

    @Mock
    private UserRepository userRepository;

    private OnboardingService service;

    @BeforeEach
    void setUp() {
        service = new OnboardingService(userRepository);
    }

    @Test
    void skipAvatar_flipsFlagWhenFalse() {
        User user = freshUser();
        when(userRepository.findById(eq(1L))).thenReturn(Optional.of(user));

        service.skipAvatar(1L);

        assertThat(user.getAvatarStepCompleted()).isTrue();
    }

    @Test
    void skipAvatar_idempotentWhenFlagAlreadyTrue() {
        User user = freshUser();
        user.setAvatarStepCompleted(true);
        when(userRepository.findById(eq(1L))).thenReturn(Optional.of(user));

        service.skipAvatar(1L);

        assertThat(user.getAvatarStepCompleted()).isTrue();
    }

    @Test
    void setDisplayName_writesTrimmedNonBlankValueAndFlipsFlag() {
        User user = freshUser();
        when(userRepository.findById(eq(1L))).thenReturn(Optional.of(user));

        service.setDisplayName(1L, "  Alice  ");

        // The override on User.getDisplayName falls back to username when
        // displayName is null/blank — so to inspect what was *written* we
        // look at the field via the explicit raw accessor; here we trust
        // that a non-null/non-blank value was set, which the override
        // surfaces as that exact trimmed value.
        assertThat(user.getDisplayName()).isEqualTo("Alice");
        assertThat(user.getDisplayNameStepCompleted()).isTrue();
    }

    @Test
    void setDisplayName_nullSkipsWriteButFlipsFlag() {
        User user = freshUser();
        when(userRepository.findById(eq(1L))).thenReturn(Optional.of(user));

        service.setDisplayName(1L, null);

        // raw column should still be null — getDisplayName falls back to
        // username, so we assert against username here.
        assertThat(user.getDisplayName()).isEqualTo(user.getUsername());
        assertThat(user.getDisplayNameStepCompleted()).isTrue();
    }

    @Test
    void setDisplayName_emptyStringSkipsWriteButFlipsFlag() {
        User user = freshUser();
        when(userRepository.findById(eq(1L))).thenReturn(Optional.of(user));

        service.setDisplayName(1L, "");

        assertThat(user.getDisplayName()).isEqualTo(user.getUsername());
        assertThat(user.getDisplayNameStepCompleted()).isTrue();
    }

    @Test
    void setDisplayName_whitespaceOnlySkipsWriteButFlipsFlag() {
        User user = freshUser();
        when(userRepository.findById(eq(1L))).thenReturn(Optional.of(user));

        service.setDisplayName(1L, "   ");

        assertThat(user.getDisplayName()).isEqualTo(user.getUsername());
        assertThat(user.getDisplayNameStepCompleted()).isTrue();
    }

    @Test
    void setDisplayName_idempotentWhenFlagAlreadyTrue() {
        User user = freshUser();
        user.setDisplayNameStepCompleted(true);
        user.setDisplayName("Existing");
        when(userRepository.findById(eq(1L))).thenReturn(Optional.of(user));

        service.setDisplayName(1L, "  Updated  ");

        // Service still writes when value is non-blank — flag stays true.
        assertThat(user.getDisplayName()).isEqualTo("Updated");
        assertThat(user.getDisplayNameStepCompleted()).isTrue();
    }

    private static User freshUser() {
        return User.builder()
                .email("alice@example.com")
                .username("alice")
                .passwordHash("x")
                .build();
    }
}
