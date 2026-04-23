package com.slparcelauctions.backend.auction.dto;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.auction.AuctionPhoto;

public record AuctionPhotoResponse(
        Long id,
        String url,
        String contentType,
        Long sizeBytes,
        Integer sortOrder,
        OffsetDateTime uploadedAt) {

    public static AuctionPhotoResponse from(AuctionPhoto p) {
        String url = "/api/v1/auctions/" + p.getAuction().getId() + "/photos/" + p.getId() + "/bytes";
        return new AuctionPhotoResponse(p.getId(), url, p.getContentType(),
                p.getSizeBytes(), p.getSortOrder(), p.getUploadedAt());
    }
}
