package com.slparcelauctions.backend.auction;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.common.exception.ResourceNotFoundException;
import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.storage.StoredObject;

import lombok.RequiredArgsConstructor;

/**
 * Public photo bytes endpoint. Single flat URL serves both seller-uploaded
 * and SL_PARCEL_SNAPSHOT photos — the photo publicId is the canonical identifier.
 *
 * <p>The endpoint is intentionally fully public — browsers fetching
 * {@code <img src>} cannot send the JWT bearer token, so the seller-only
 * draft-photo gate that lived here briefly returned 404 to the auction's
 * own seller. Photo publicIds are UUIDs; the auction's metadata (owner, status,
 * region, price) is hidden behind {@link AuctionController}'s separate
 * pre-ACTIVE 404 logic.
 */
@RestController
@RequestMapping("/api/v1/photos")
@RequiredArgsConstructor
public class PhotoController {

    private final AuctionPhotoRepository photoRepo;
    private final ObjectStorageService storage;

    @GetMapping("/{publicId}")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> get(@PathVariable UUID publicId) {
        AuctionPhoto photo = photoRepo.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Photo not found: " + publicId));
        StoredObject stored = storage.get(photo.getObjectKey());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .contentType(MediaType.parseMediaType(stored.contentType()))
                .body(stored.bytes());
    }
}
