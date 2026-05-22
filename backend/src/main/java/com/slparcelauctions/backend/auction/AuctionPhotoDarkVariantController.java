package com.slparcelauctions.backend.auction;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.slparcelauctions.backend.auction.dto.AuctionPhotoResponse;
import com.slparcelauctions.backend.auth.AuthPrincipal;

import lombok.RequiredArgsConstructor;

/**
 * Plan Task 7 of the theme-image-variants feature. Manages the dark variant
 * blob on an auction's sort-0 default-cover photo row. Only the auto-inserted
 * default-cover sources ({@link PhotoSource#USER_DEFAULT_COVER} and
 * {@link PhotoSource#GROUP_DEFAULT_COVER}) accept a dark sibling; seller
 * uploads and SL parcel snapshots stay single-slot and are rejected with
 * {@code 400 INVALID_PHOTO_SOURCE}.
 *
 * <p>Both {@code POST} and {@code DELETE} return the updated
 * {@link AuctionPhotoResponse} so the photo manager UI can refresh its row
 * state without an extra fetch. Authorization is enforced inside
 * {@link AuctionPhotoDarkVariantService#uploadDark}: the caller must be the
 * auction's seller or an admin.
 */
@RestController
@RequestMapping("/api/v1/auctions/{auctionPublicId}/photos/{photoPublicId}/dark")
@RequiredArgsConstructor
public class AuctionPhotoDarkVariantController {

    private final AuctionPhotoDarkVariantService service;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AuctionPhotoResponse upload(
            @PathVariable UUID auctionPublicId,
            @PathVariable UUID photoPublicId,
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal AuthPrincipal principal) {
        AuctionPhoto updated = service.uploadDark(
                principal.userId(), auctionPublicId, photoPublicId, file);
        return AuctionPhotoResponse.from(updated);
    }

    @DeleteMapping
    public AuctionPhotoResponse delete(
            @PathVariable UUID auctionPublicId,
            @PathVariable UUID photoPublicId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        AuctionPhoto updated = service.deleteDark(
                principal.userId(), auctionPublicId, photoPublicId);
        return AuctionPhotoResponse.from(updated);
    }
}
