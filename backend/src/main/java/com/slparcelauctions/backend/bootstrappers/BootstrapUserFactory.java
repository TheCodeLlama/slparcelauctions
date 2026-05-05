package com.slparcelauctions.backend.bootstrappers;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Single-call helper for bootstrappers that need to seed a {@link User}. Builds
 * the entity from the spec and persists it in one method — bootstrappers do not
 * touch {@link User#builder()} or {@link UserRepository} directly. The factory
 * intentionally accepts a pre-hashed password ({@link BootstrapUserSpec#passwordHash})
 * so seed code never carries plaintext credentials.
 */
@Component
@RequiredArgsConstructor
public class BootstrapUserFactory {

    private final UserRepository userRepository;

    @Transactional
    public User createUser(BootstrapUserSpec spec) {
        User user = User.builder()
                .username(spec.username())
                .email(spec.email())
                .passwordHash(spec.passwordHash())
                .slAvatarUuid(spec.slAvatarUuid())
                .slAvatarName(spec.slAvatarName())
                .slUsername(spec.slUsername())
                .slDisplayName(spec.slDisplayName())
                .slBornDate(spec.slBornDate())
                .slPayinfo(spec.slPayinfo())
                .displayName(spec.displayName())
                .bio(spec.bio())
                .profilePicUrl(spec.profilePicUrl())
                .role(spec.role())
                .verified(spec.verified())
                .verifiedAt(spec.verifiedAt())
                .emailVerified(spec.emailVerified())
                .build();
        return userRepository.save(user);
    }
}
