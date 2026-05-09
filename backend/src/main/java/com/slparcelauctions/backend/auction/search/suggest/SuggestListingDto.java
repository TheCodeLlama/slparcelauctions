package com.slparcelauctions.backend.auction.search.suggest;

import java.util.UUID;

/**
 * One row in the suggest popover's "Listings" group. The {@code
 * primaryPhotoUrl} is the relative path the frontend wraps in
 * {@code apiUrl()}; null when the listing has no photo.
 */
public record SuggestListingDto(
        UUID publicId,
        String title,
        String regionName,
        String parcelName,
        String primaryPhotoUrl,
        long currentBid) {
}
