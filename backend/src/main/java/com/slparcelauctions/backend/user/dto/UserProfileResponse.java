package com.slparcelauctions.backend.user.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.user.User;

public record UserProfileResponse(
        Long id,
        String displayName,
        String bio,
        String profilePicUrl,
        UUID slAvatarUuid,
        String slUsername,
        String slDisplayName,
        Boolean verified,
        BigDecimal avgSellerRating,
        BigDecimal avgBuyerRating,
        Integer totalSellerReviews,
        Integer totalBuyerReviews,
        Integer completedSales,
        OffsetDateTime createdAt) {

    public static UserProfileResponse from(User user) {
        return new UserProfileResponse(
                user.getId(),
                user.getDisplayName(),
                user.getBio(),
                user.getProfilePicUrl(),
                user.getSlAvatarUuid(),
                user.getSlUsername(),
                user.getSlDisplayName(),
                user.getVerified(),
                user.getAvgSellerRating(),
                user.getAvgBuyerRating(),
                user.getTotalSellerReviews(),
                user.getTotalBuyerReviews(),
                user.getCompletedSales(),
                user.getCreatedAt());
    }
}
