package com.slparcelauctions.backend.auction;

import java.util.UUID;

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
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.common.exception.ResourceNotFoundException;

import lombok.RequiredArgsConstructor;

/**
 * Per-auction photo endpoints. Write paths (upload, delete) are gated to the
 * seller via {@link AuctionPhotoService} which calls
 * {@link AuctionService#loadForSeller(Long, Long)} — non-sellers get a 404
 * to avoid leaking draft existence. Public photo bytes are now served by the
 * flat {@code GET /api/v1/photos/{publicId}} endpoint in {@link PhotoController}.
 *
 * <p>Both {@code auctionPublicId} and {@code photoPublicId} are UUIDs — internal
 * Long PKs are not exposed on the web/mobile API surface.
 */
@RestController
@RequestMapping("/api/v1/auctions/{auctionPublicId}/photos")
@RequiredArgsConstructor
public class AuctionPhotoController {

    private final AuctionPhotoService service;
    private final AuctionRepository auctionRepository;
    private final AuctionPhotoRepository photoRepository;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AuctionPhotoResponse upload(
            @PathVariable UUID auctionPublicId,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AuthPrincipal principal) {
        Long auctionId = resolveAuctionId(auctionPublicId);
        AuctionPhoto saved = service.upload(auctionId, principal.userId(), file);
        return AuctionPhotoResponse.from(saved);
    }

    @DeleteMapping("/{photoPublicId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable UUID auctionPublicId,
            @PathVariable UUID photoPublicId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        Long auctionId = resolveAuctionId(auctionPublicId);
        Long photoId = photoRepository.findByPublicId(photoPublicId)
                .map(AuctionPhoto::getId)
                .orElseThrow(() -> new ResourceNotFoundException("Photo not found: " + photoPublicId));
        service.delete(auctionId, photoId, principal.userId());
    }

    private Long resolveAuctionId(UUID auctionPublicId) {
        return auctionRepository.findByPublicId(auctionPublicId)
                .map(Auction::getId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionPublicId));
    }
}
