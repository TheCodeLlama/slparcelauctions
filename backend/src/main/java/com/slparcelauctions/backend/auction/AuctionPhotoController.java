package com.slparcelauctions.backend.auction;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.slparcelauctions.backend.auction.dto.AuctionPhotoResponse;
import com.slparcelauctions.backend.auth.AuthPrincipal;

import lombok.RequiredArgsConstructor;

/**
 * Per-auction photo endpoints. Write paths (upload, delete) are gated to the
 * seller via {@link AuctionPhotoService} which calls
 * {@link AuctionService#loadForSeller(Long, Long)} — non-sellers get a 404
 * to avoid leaking draft existence. Public photo bytes are now served by the
 * flat {@code GET /api/v1/photos/{id}} endpoint in {@link PhotoController}.
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
}
