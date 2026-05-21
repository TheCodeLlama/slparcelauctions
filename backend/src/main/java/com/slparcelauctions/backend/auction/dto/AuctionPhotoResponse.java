package com.slparcelauctions.backend.auction.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.auction.AuctionPhoto;
import com.slparcelauctions.backend.auction.PhotoUrl;

public record AuctionPhotoResponse(
        UUID publicId,
        String url,
        String contentType,
        Long sizeBytes,
        Integer sortOrder,
        OffsetDateTime uploadedAt) {

    public static AuctionPhotoResponse from(AuctionPhoto p) {
        // Plan Task 1: DTO still surfaces only the LIGHT variant. Plan Task 6
        // will add explicit light/dark URLs + content types to this response.
        String url = PhotoUrl.forPhoto(p.getPublicId());
        return new AuctionPhotoResponse(p.getPublicId(), url, p.getLightContentType(),
                p.getLightSizeBytes(), p.getSortOrder(), p.getUploadedAt());
    }
}
