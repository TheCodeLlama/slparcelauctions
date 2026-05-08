package com.slparcelauctions.backend.onboarding;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.user.dto.UserResponse;

import lombok.RequiredArgsConstructor;

/**
 * Owns the two flag-flip operations behind the post-verify onboarding flow.
 * Both methods are idempotent — re-calling on a row whose flag is already
 * true is a no-op (and equally cheap, since JPA dirty-checking sees no
 * change).
 *
 * <p>"Skip" semantics: any null / empty / whitespace-only displayName is
 * treated as "user opted out of setting one"; the column stays null and
 * {@code User.getDisplayName()}'s username fallback (PR #211) handles the
 * read side.
 */
@Service
@RequiredArgsConstructor
public class OnboardingService {

    private final UserRepository userRepository;

    @Transactional
    public UserResponse skipAvatar(Long userId) {
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        if (!Boolean.TRUE.equals(u.getAvatarStepCompleted())) {
            u.setAvatarStepCompleted(true);
        }
        return UserResponse.from(u);
    }

    @Transactional
    public UserResponse setDisplayName(Long userId, String displayName) {
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        String trimmed = displayName == null ? "" : displayName.trim();
        if (!trimmed.isEmpty()) {
            u.setDisplayName(trimmed);
        }
        if (!Boolean.TRUE.equals(u.getDisplayNameStepCompleted())) {
            u.setDisplayNameStepCompleted(true);
        }
        return UserResponse.from(u);
    }
}
