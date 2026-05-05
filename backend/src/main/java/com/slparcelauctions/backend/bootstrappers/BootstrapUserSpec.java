package com.slparcelauctions.backend.bootstrappers;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.user.Role;

/**
 * Snapshot of every field {@link BootstrapUserFactory} needs to materialise a
 * seed user. {@code passwordHash} is the bcrypt value the row stores directly
 * — bootstrappers never see plaintext passwords.
 */
public record BootstrapUserSpec(
        String username,
        String email,
        String passwordHash,
        UUID slAvatarUuid,
        String slAvatarName,
        String slUsername,
        String slDisplayName,
        LocalDate slBornDate,
        Integer slPayinfo,
        String displayName,
        String bio,
        String profilePicUrl,
        Role role,
        boolean verified,
        OffsetDateTime verifiedAt,
        boolean emailVerified
) {
}
