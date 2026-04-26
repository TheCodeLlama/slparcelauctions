package com.slparcelauctions.backend.notification;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

public enum NotificationGroup {
    BIDDING, AUCTION_RESULT, ESCROW, LISTING_STATUS,
    REVIEWS, REALTY_GROUP, MARKETING, SYSTEM;

    public Set<NotificationCategory> categories() {
        return EnumSet.copyOf(
                Arrays.stream(NotificationCategory.values())
                        .filter(c -> c.getGroup() == this)
                        .toList()
        );
    }
}
