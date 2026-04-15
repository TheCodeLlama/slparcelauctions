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
        OffsetDateTime createdAt) {

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
                user.getCreatedAt());
    }
}
