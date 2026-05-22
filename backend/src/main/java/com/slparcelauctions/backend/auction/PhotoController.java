package com.slparcelauctions.backend.auction;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.common.exception.ResourceNotFoundException;
import com.slparcelauctions.backend.common.image.ImageVariant;
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
 *
 * <p>Plan Task 6 made the endpoint theme-pair aware via a
 * {@code ?variant=light|dark} query parameter. Light is the default so
 * single-variant legacy callers (cards, search thumbs, review thumbnails)
 * keep working unchanged. A {@code variant=dark} request 404s when the row
 * has no dark sibling; anything outside the {@code light}/{@code dark} pair
 * raises {@link com.slparcelauctions.backend.common.image.InvalidVariantException},
 * mapped to 400 {@code INVALID_VARIANT} by the global handler.
 */
@RestController
@RequestMapping("/api/v1/photos")
@RequiredArgsConstructor
public class PhotoController {

    private final AuctionPhotoRepository photoRepo;
    private final ObjectStorageService storage;

    @GetMapping("/{publicId}")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> get(
            @PathVariable UUID publicId,
            @RequestParam(value = "variant", defaultValue = "light") String variant) {
        ImageVariant v = ImageVariant.parse(variant);
        AuctionPhoto photo = photoRepo.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Photo not found: " + publicId));
        StoredObject stored = switch (v) {
            case LIGHT -> storage.get(photo.getLightObjectKey());
            case DARK -> {
                if (photo.getDarkObjectKey() == null) {
                    throw new ResourceNotFoundException(
                            "Dark variant not available for photo: " + publicId);
                }
                yield storage.get(photo.getDarkObjectKey());
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .contentType(MediaType.parseMediaType(stored.contentType()))
                .body(stored.bytes());
    }
}
