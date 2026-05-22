package com.slparcelauctions.backend.realty.service;

import java.io.IOException;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.slparcelauctions.backend.common.image.ImageVariant;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.exception.GroupDissolvedException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupImageNotFoundException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.storage.ImagePurpose;
import com.slparcelauctions.backend.storage.ImageStorageContext;
import com.slparcelauctions.backend.storage.ImageStorageService;
import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.storage.StoredImage;
import com.slparcelauctions.backend.storage.StoredObject;
import com.slparcelauctions.backend.storage.exception.ObjectNotFoundException;
import com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service backing the realty-group default listing picture surface. A group may
 * upload one light + one dark variant; whichever is set is offered as the seed
 * for the auction sort-0 listing photo (plan Task 6) when the seller has not
 * provided their own picture for that auction. Mirrors
 * {@link RealtyGroupImageService} shape so the controller stays a thin shim
 * that parses the variant token and delegates here.
 *
 * <p>Storage keys: {@code realty-groups/{publicId}/default-listing-{variant}.webp}.
 * Each upload overwrites the same key for its (group, variant) tuple. The
 * trailing {@code .webp} suffix is appended by {@link ImageStorageService};
 * the keyWithoutExt we pass in does not include it.
 *
 * <p>Authorization lives in the controller (existing {@code RealtyGroupAuthorizer}
 * pattern with {@code EDIT_GROUP_PROFILE}). This service trusts its callers to
 * have already gated on that permission; it only validates that the addressed
 * group exists and is not dissolved.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RealtyGroupDefaultListingImageService {

    private final RealtyGroupRepository groups;
    private final ImageStorageService imageStorage;
    private final ObjectStorageService objectStorage;

    @Transactional
    public RealtyGroup upload(UUID publicId, ImageVariant variant, MultipartFile file) {
        RealtyGroup group = loadActive(publicId);
        String keyWithoutExt = "realty-groups/" + group.getPublicId() + "/default-listing-" + variant.slug();
        StoredImage stored = storeBytes(file, keyWithoutExt);
        if (variant == ImageVariant.LIGHT) {
            group.setDefaultListingLightObjectKey(stored.objectKey());
            group.setDefaultListingLightContentType(stored.contentType());
            group.setDefaultListingLightSizeBytes(stored.sizeBytes());
        } else {
            group.setDefaultListingDarkObjectKey(stored.objectKey());
            group.setDefaultListingDarkContentType(stored.contentType());
            group.setDefaultListingDarkSizeBytes(stored.sizeBytes());
        }
        log.info("Realty group {} default listing {} updated: key={} ({} bytes)",
                group.getPublicId(), variant.slug(), stored.objectKey(), stored.sizeBytes());
        return group;
    }

    @Transactional
    public RealtyGroup delete(UUID publicId, ImageVariant variant) {
        RealtyGroup group = loadActive(publicId);
        String key = variant == ImageVariant.LIGHT
                ? group.getDefaultListingLightObjectKey()
                : group.getDefaultListingDarkObjectKey();
        bestEffortDelete(key);
        if (variant == ImageVariant.LIGHT) {
            group.setDefaultListingLightObjectKey(null);
            group.setDefaultListingLightContentType(null);
            group.setDefaultListingLightSizeBytes(null);
        } else {
            group.setDefaultListingDarkObjectKey(null);
            group.setDefaultListingDarkContentType(null);
            group.setDefaultListingDarkSizeBytes(null);
        }
        log.info("Realty group {} default listing {} cleared", group.getPublicId(), variant.slug());
        return group;
    }

    @Transactional(readOnly = true)
    public StoredObject fetchBytes(UUID publicId, ImageVariant variant) {
        RealtyGroup group = loadActive(publicId);
        String key = variant == ImageVariant.LIGHT
                ? group.getDefaultListingLightObjectKey()
                : group.getDefaultListingDarkObjectKey();
        return fetchObject(group.getPublicId(), key);
    }

    // ─────────────────────── helpers ───────────────────────

    private RealtyGroup loadActive(UUID publicId) {
        RealtyGroup group = groups.findByPublicId(publicId)
                .orElseThrow(() -> new RealtyGroupNotFoundException(publicId));
        if (group.isDissolved()) {
            throw new GroupDissolvedException(group.getPublicId());
        }
        return group;
    }

    private StoredImage storeBytes(MultipartFile file, String keyWithoutExt) {
        try {
            return imageStorage.storeImage(
                    file.getInputStream(),
                    new ImageStorageContext(ImagePurpose.LISTING_PHOTO, keyWithoutExt));
        } catch (IOException e) {
            throw new UnsupportedImageFormatException("Failed to read upload: " + e.getMessage(), e);
        }
    }

    private void bestEffortDelete(String key) {
        if (key == null) {
            return;
        }
        try {
            objectStorage.delete(key);
        } catch (RuntimeException e) {
            // Idempotent column clear is the source of truth; storage delete is best-effort.
            log.warn("Best-effort delete of {} failed: {}", key, e.getMessage());
        }
    }

    private StoredObject fetchObject(UUID groupPublicId, String key) {
        if (key == null) {
            throw new RealtyGroupImageNotFoundException(
                    groupPublicId, RealtyGroupImageNotFoundException.Kind.DEFAULT_LISTING);
        }
        try {
            return objectStorage.get(key);
        } catch (ObjectNotFoundException e) {
            // Column points at a key S3 doesn't have - treat as image-not-set to the caller.
            log.warn("Realty group {} default-listing object missing in storage (key={})",
                    groupPublicId, key);
            throw new RealtyGroupImageNotFoundException(
                    groupPublicId, RealtyGroupImageNotFoundException.Kind.DEFAULT_LISTING);
        }
    }
}
