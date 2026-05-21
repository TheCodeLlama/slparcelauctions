package com.slparcelauctions.backend.user.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;

public record UserResponse(
        UUID publicId,
        String username,
        String email,
        String displayName,
        String bio,
        String profilePicUrl,
        // Relative URL to the user's default cover image, or null when unset.
        // Resolved via {@code apiUrl()} on the frontend; the backing endpoint
        // is permitAll so {@code <img src>} renders without an Authorization
        // header. Auto-inserted as the first photo on every new listing.
        String defaultCoverUrl,
        UUID slAvatarUuid,
        String slAvatarName,
        String slUsername,
        String slDisplayName,
        LocalDate slBornDate,
        Integer slPayinfo,
        Boolean verified,
        OffsetDateTime verifiedAt,
        Boolean emailVerified,
        // Forced post-verify onboarding state. Drives the (onboarded)
        // layout redirect on the frontend; both must be true for the user
        // to reach the dashboard. See
        // docs/superpowers/specs/2026-05-08-avatar-and-display-name-onboarding-design.md.
        boolean avatarStepCompleted,
        boolean displayNameStepCompleted,
        Map<String, Object> notifyEmail,
        Map<String, Object> notifySlIm,
        // Listing-suspension state (Epic 08 sub-spec 2 §7.2). Mirrors the
        // three new User columns so the dashboard banner can render the
        // current penalty/suspension/ban state from the same /me payload
        // without an extra round-trip. Each field is independently
        // populated — a banned account may still owe L$, etc. — and the
        // frontend collapses them into a single banner per
        // SuspensionReason ordering.
        Long penaltyBalanceOwed,
        OffsetDateTime listingSuspensionUntil,
        Boolean bannedFromListing,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        long unreadNotificationCount,
        Role role) {

    public static UserResponse from(User user) {
        return from(user, 0L);
    }

    public static UserResponse from(User user, long unreadNotificationCount) {
        // Plan Task 1: still keyed off the LIGHT slot. Plan Task 4 swaps this
        // to the variant-aware URL once the dark surface ships.
        String coverUrl = user.getDefaultCoverLightObjectKey() != null
                ? "/api/v1/users/" + user.getPublicId() + "/default-cover/image"
                : null;
        return new UserResponse(
                user.getPublicId(),
                user.getUsername(),
                user.getEmail(),
                user.getDisplayName(),
                user.getBio(),
                user.getProfilePicUrl(),
                coverUrl,
                user.getSlAvatarUuid(),
                user.getSlAvatarName(),
                user.getSlUsername(),
                user.getSlDisplayName(),
                user.getSlBornDate(),
                user.getSlPayinfo(),
                user.getVerified(),
                user.getVerifiedAt(),
                user.getEmailVerified(),
                Boolean.TRUE.equals(user.getAvatarStepCompleted()),
                Boolean.TRUE.equals(user.getDisplayNameStepCompleted()),
                user.getNotifyEmail(),
                user.getNotifySlIm(),
                user.getPenaltyBalanceOwed(),
                user.getListingSuspensionUntil(),
                user.getBannedFromListing(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                unreadNotificationCount,
                user.getRole());
    }
}
