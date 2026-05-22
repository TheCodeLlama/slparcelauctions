package com.slparcelauctions.backend.auction;

import java.io.IOException;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.common.exception.ResourceNotFoundException;
import com.slparcelauctions.backend.storage.ImagePurpose;
import com.slparcelauctions.backend.storage.ImageStorageContext;
import com.slparcelauctions.backend.storage.ImageStorageService;
import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.storage.StoredImage;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service backing the per-auction dark-variant slot on a default-cover row.
 * The sort-0 default-cover row (source {@link PhotoSource#USER_DEFAULT_COVER}
 * or {@link PhotoSource#GROUP_DEFAULT_COVER}) is the only row in a listing's
 * photo set that carries a dual-slot light/dark pair: seller uploads and SL
 * parcel snapshots stay single-slot and any attempt to attach a dark variant
 * to them surfaces as {@code 400 INVALID_PHOTO_SOURCE}.
 *
 * <p>Authorization: the caller must either be the auction's seller or an
 * admin. Mismatches throw {@link AccessDeniedException} which the global
 * handler maps to {@code 403 ACCESS_DENIED}. The photo's {@code auction}
 * association is cross-checked against the URL's {@code auctionPublicId}
 * to defeat URL-tampering and to hide row existence behind a uniform 404.
 *
 * <p>Storage keys: {@code listings/{auctionId}/{photoPublicId}-dark.webp}.
 * Replacement uploads overwrite the same key (idempotent S3 PUT); any
 * pre-existing key that does not match the new key is best-effort deleted
 * before write. Bytes flow through the central {@link ImageStorageService}
 * chokepoint so the validation, resize, and WebP encoding stay uniform
 * with every other image upload.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuctionPhotoDarkVariantService {

    private final AuctionRepository auctionRepo;
    private final AuctionPhotoRepository photoRepo;
    private final ImageStorageService imageStorage;
    private final ObjectStorageService objectStorage;
    private final UserRepository userRepo;

    @Transactional
    public AuctionPhoto uploadDark(
            Long callerUserId, UUID auctionPublicId, UUID photoPublicId, MultipartFile file) {
        Auction auction = loadAuction(auctionPublicId);
        assertCanManage(callerUserId, auction);
        AuctionPhoto photo = loadPhoto(auction, photoPublicId);
        assertSourceSupportsDark(photo);

        String keyWithoutExt = "listings/" + auction.getId() + "/" + photo.getPublicId() + "-dark";
        StoredImage stored = storeBytes(file, keyWithoutExt);

        // Best-effort cleanup of any prior dark blob at a different key. The
        // chokepoint writes to keyWithoutExt + ".webp" deterministically so a
        // replacement of an existing .webp dark variant is an idempotent
        // overwrite at the same key; only a key shape change (or legacy
        // non-webp historical content) would leave an orphan to clean.
        String prior = photo.getDarkObjectKey();
        if (prior != null && !prior.equals(stored.objectKey())) {
            try {
                objectStorage.delete(prior);
            } catch (RuntimeException e) {
                log.warn("Failed to delete prior dark variant {} for photo {}: {}",
                        prior, photo.getId(), e.getMessage());
            }
        }

        photo.setDarkObjectKey(stored.objectKey());
        photo.setDarkContentType(stored.contentType());
        photo.setDarkSizeBytes(stored.sizeBytes());
        log.info("Auction {} photo {} dark variant uploaded: key={} ({} bytes)",
                auction.getId(), photo.getId(), stored.objectKey(), stored.sizeBytes());
        return photo;
    }

    @Transactional
    public AuctionPhoto deleteDark(
            Long callerUserId, UUID auctionPublicId, UUID photoPublicId) {
        Auction auction = loadAuction(auctionPublicId);
        assertCanManage(callerUserId, auction);
        AuctionPhoto photo = loadPhoto(auction, photoPublicId);
        assertSourceSupportsDark(photo);

        String prior = photo.getDarkObjectKey();
        if (prior != null) {
            try {
                objectStorage.delete(prior);
            } catch (RuntimeException e) {
                // Idempotent column clear is the source of truth; storage
                // delete is best-effort (matches the seller-photo delete path
                // and the realty-group image delete path).
                log.warn("Best-effort delete of dark variant {} for photo {} failed: {}",
                        prior, photo.getId(), e.getMessage());
            }
        }
        photo.setDarkObjectKey(null);
        photo.setDarkContentType(null);
        photo.setDarkSizeBytes(null);
        log.info("Auction {} photo {} dark variant cleared (prior key={})",
                auction.getId(), photo.getId(), prior);
        return photo;
    }

    // --------------------------------------------------------------- helpers

    private Auction loadAuction(UUID auctionPublicId) {
        return auctionRepo.findByPublicId(auctionPublicId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionPublicId));
    }

    private AuctionPhoto loadPhoto(Auction auction, UUID photoPublicId) {
        AuctionPhoto photo = photoRepo.findByPublicId(photoPublicId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Photo not found: " + photoPublicId));
        // Cross-check the URL's auction with the photo's auction so a tampered
        // URL pointing the right photo at the wrong auction 404s instead of
        // leaking row existence.
        if (!photo.getAuction().getId().equals(auction.getId())) {
            throw new ResourceNotFoundException(
                    "Photo not found: " + photoPublicId);
        }
        return photo;
    }

    private void assertSourceSupportsDark(AuctionPhoto photo) {
        PhotoSource src = photo.getSource();
        if (src != PhotoSource.USER_DEFAULT_COVER
                && src != PhotoSource.GROUP_DEFAULT_COVER) {
            throw new InvalidPhotoSourceException(src);
        }
    }

    private void assertCanManage(Long callerUserId, Auction auction) {
        User caller = userRepo.findById(callerUserId)
                .orElseThrow(() -> new AccessDeniedException(
                        "Authenticated user not found: " + callerUserId));
        boolean isAdmin = caller.getRole() == Role.ADMIN;
        boolean isSeller = auction.getSeller() != null
                && auction.getSeller().getId().equals(callerUserId);
        if (!isAdmin && !isSeller) {
            throw new AccessDeniedException(
                    "Not the seller of this auction and not an admin");
        }
    }

    private StoredImage storeBytes(MultipartFile file, String keyWithoutExt) {
        try {
            return imageStorage.storeImage(
                    file.getInputStream(),
                    new ImageStorageContext(ImagePurpose.LISTING_PHOTO, keyWithoutExt));
        } catch (IOException e) {
            throw new UnsupportedImageFormatException(
                    "Failed to read upload: " + e.getMessage(), e);
        }
    }
}
