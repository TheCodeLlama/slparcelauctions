package com.slparcelauctions.backend.auction.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.auction.AuctionPhoto;

public record AuctionPhotoResponse(
        UUID publicId,
        String url,
        String contentType,
        Long sizeBytes,
        Integer sortOrder,
        OffsetDateTime uploadedAt) {

    public static AuctionPhotoResponse from(AuctionPhoto p) {
        String url = "/api/v1/photos/" + p.getPublicId();
        return new AuctionPhotoResponse(p.getPublicId(), url, p.getContentType(),
                p.getSizeBytes(), p.getSortOrder(), p.getUploadedAt());
    }
}
