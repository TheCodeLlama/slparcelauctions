package com.slparcelauctions.backend.user.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import com.slparcelauctions.backend.user.User;

public record UserResponse(
        Long id,
        String email,
        String displayName,
        String bio,
        String profilePicUrl,
        UUID slAvatarUuid,
        String slAvatarName,
        String slUsername,
        String slDisplayName,
        LocalDate slBornDate,
        Integer slPayinfo,
        Boolean verified,
        OffsetDateTime verifiedAt,
        Boolean emailVerified,
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
        OffsetDateTime updatedAt) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getBio(),
                user.getProfilePicUrl(),
                user.getSlAvatarUuid(),
                user.getSlAvatarName(),
                user.getSlUsername(),
                user.getSlDisplayName(),
                user.getSlBornDate(),
                user.getSlPayinfo(),
                user.getVerified(),
                user.getVerifiedAt(),
                user.getEmailVerified(),
                user.getNotifyEmail(),
                user.getNotifySlIm(),
                user.getPenaltyBalanceOwed(),
                user.getListingSuspensionUntil(),
                user.getBannedFromListing(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }
}
