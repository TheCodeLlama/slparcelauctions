package com.slparcelauctions.backend.auction;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.slparcelauctions.backend.auction.dto.AuctionPhotoResponse;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.storage.StoredObject;

import lombok.RequiredArgsConstructor;

/**
 * Per-auction photo endpoints. Write paths (upload, delete) are gated to the
 * seller via {@link AuctionPhotoService} which calls
 * {@link AuctionService#loadForSeller(Long, Long)} — non-sellers get a 404
 * to avoid leaking draft existence. The public {@code GET /bytes} endpoint
 * proxies the stored object directly and is permitted at the security layer
 * (see {@code SecurityConfig}) because listing photos are part of the public
 * auction view.
 */
@RestController
@RequestMapping("/api/v1/auctions/{auctionId}/photos")
@RequiredArgsConstructor
public class AuctionPhotoController {

    private final AuctionPhotoService service;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AuctionPhotoResponse upload(
            @PathVariable Long auctionId,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AuthPrincipal principal) {
        AuctionPhoto saved = service.upload(auctionId, principal.userId(), file);
        return AuctionPhotoResponse.from(saved);
    }

    @DeleteMapping("/{photoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable Long auctionId,
            @PathVariable Long photoId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        service.delete(auctionId, photoId, principal.userId());
    }

    @GetMapping("/{photoId}/bytes")
    public ResponseEntity<byte[]> bytes(
            @PathVariable Long auctionId,
            @PathVariable Long photoId) {
        StoredObject stored = service.fetchBytes(auctionId, photoId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, stored.contentType())
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(stored.contentLength()))
                .body(stored.bytes());
    }
}
