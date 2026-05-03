package com.slparcelauctions.backend.auction;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.common.exception.ResourceNotFoundException;
import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.storage.StoredObject;

import lombok.RequiredArgsConstructor;

/**
 * Public photo bytes endpoint. Single flat URL serves both seller-uploaded
 * and SL_PARCEL_SNAPSHOT photos — the photo id is the canonical identifier;
 * auction membership is internal.
 *
 * <p>Pre-ACTIVE auctions are draft-private: anonymous callers receive 404
 * to avoid enumerating draft listings by iterating photo IDs. The auction
 * seller can still preview draft photos by supplying their access token.
 */
@RestController
@RequestMapping("/api/v1/photos")
@RequiredArgsConstructor
public class PhotoController {

    private final AuctionPhotoRepository photoRepo;
    private final ObjectStorageService storage;

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> get(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal) {
        AuctionPhoto photo = photoRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Photo not found: " + id));

        // Pre-ACTIVE auctions are draft-only. Non-sellers get 404 to hide the
        // draft's existence (mirrors AuctionController.get() privacy pattern).
        Auction auction = photo.getAuction();
        if (isPreActive(auction.getStatus())) {
            Long callerId = principal != null ? principal.userId() : null;
            boolean isSeller = callerId != null
                    && auction.getSeller().getId().equals(callerId);
            if (!isSeller) {
                throw new ResourceNotFoundException("Photo not found: " + id);
            }
        }

        StoredObject stored = storage.get(photo.getObjectKey());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .contentType(MediaType.parseMediaType(stored.contentType()))
                .body(stored.bytes());
    }

    private static boolean isPreActive(AuctionStatus s) {
        return s == AuctionStatus.DRAFT
                || s == AuctionStatus.DRAFT_PAID
                || s == AuctionStatus.VERIFICATION_PENDING
                || s == AuctionStatus.VERIFICATION_FAILED;
    }
}
