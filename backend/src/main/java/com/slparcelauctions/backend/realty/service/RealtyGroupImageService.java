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
 * Service backing the realty-group cover + logo image surface. Each operation
 * is variant-aware ({@link ImageVariant#LIGHT light} / {@link ImageVariant#DARK
 * dark}) so the controller stays a thin HTTP shim that parses the variant token
 * and delegates here.
 *
 * <p>Storage keys: {@code realty-groups/{publicId}/cover-{variant}.webp} and
 * {@code realty-groups/{publicId}/logo-{variant}.webp}. Each upload overwrites
 * the same key for its (group, surface, variant) tuple. The trailing {@code
 * .webp} suffix is appended by {@link ImageStorageService}; the keyWithoutExt
 * we pass in does not include it.
 *
 * <p>Authorization lives in the controller (existing {@code RealtyGroupAuthorizer}
 * pattern). This service trusts its callers to have already gated on the
 * {@code EDIT_GROUP_PROFILE} permission; it only validates that the addressed
 * group exists and is not dissolved.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RealtyGroupImageService {

    private final RealtyGroupRepository groups;
    private final ImageStorageService imageStorage;
    private final ObjectStorageService objectStorage;

    // ─────────────────────── COVER ───────────────────────

    @Transactional
    public RealtyGroup uploadCover(UUID publicId, ImageVariant variant, MultipartFile file) {
        RealtyGroup group = loadActive(publicId);
        String keyWithoutExt = "realty-groups/" + group.getPublicId() + "/cover-" + variant.slug();
        StoredImage stored = storeBytes(file, ImagePurpose.COVER, keyWithoutExt);
        if (variant == ImageVariant.LIGHT) {
            group.setCoverLightObjectKey(stored.objectKey());
            group.setCoverLightContentType(stored.contentType());
            group.setCoverLightSizeBytes(stored.sizeBytes());
        } else {
            group.setCoverDarkObjectKey(stored.objectKey());
            group.setCoverDarkContentType(stored.contentType());
            group.setCoverDarkSizeBytes(stored.sizeBytes());
        }
        log.info("Realty group {} cover {} updated: key={} ({} bytes)",
                group.getPublicId(), variant.slug(), stored.objectKey(), stored.sizeBytes());
        return group;
    }

    @Transactional
    public RealtyGroup deleteCover(UUID publicId, ImageVariant variant) {
        RealtyGroup group = loadActive(publicId);
        String key = variant == ImageVariant.LIGHT
                ? group.getCoverLightObjectKey()
                : group.getCoverDarkObjectKey();
        bestEffortDelete(key);
        if (variant == ImageVariant.LIGHT) {
            group.setCoverLightObjectKey(null);
            group.setCoverLightContentType(null);
            group.setCoverLightSizeBytes(null);
        } else {
            group.setCoverDarkObjectKey(null);
            group.setCoverDarkContentType(null);
            group.setCoverDarkSizeBytes(null);
        }
        log.info("Realty group {} cover {} cleared", group.getPublicId(), variant.slug());
        return group;
    }

    @Transactional(readOnly = true)
    public StoredObject fetchCoverBytes(UUID publicId, ImageVariant variant) {
        RealtyGroup group = loadActive(publicId);
        String key = variant == ImageVariant.LIGHT
                ? group.getCoverLightObjectKey()
                : group.getCoverDarkObjectKey();
        return fetchObject(group.getPublicId(), key, RealtyGroupImageNotFoundException.Kind.COVER);
    }

    // ─────────────────────── LOGO ───────────────────────

    @Transactional
    public RealtyGroup uploadLogo(UUID publicId, ImageVariant variant, MultipartFile file) {
        RealtyGroup group = loadActive(publicId);
        String keyWithoutExt = "realty-groups/" + group.getPublicId() + "/logo-" + variant.slug();
        StoredImage stored = storeBytes(file, ImagePurpose.LOGO, keyWithoutExt);
        if (variant == ImageVariant.LIGHT) {
            group.setLogoLightObjectKey(stored.objectKey());
            group.setLogoLightContentType(stored.contentType());
            group.setLogoLightSizeBytes(stored.sizeBytes());
        } else {
            group.setLogoDarkObjectKey(stored.objectKey());
            group.setLogoDarkContentType(stored.contentType());
            group.setLogoDarkSizeBytes(stored.sizeBytes());
        }
        log.info("Realty group {} logo {} updated: key={} ({} bytes)",
                group.getPublicId(), variant.slug(), stored.objectKey(), stored.sizeBytes());
        return group;
    }

    @Transactional
    public RealtyGroup deleteLogo(UUID publicId, ImageVariant variant) {
        RealtyGroup group = loadActive(publicId);
        String key = variant == ImageVariant.LIGHT
                ? group.getLogoLightObjectKey()
                : group.getLogoDarkObjectKey();
        bestEffortDelete(key);
        if (variant == ImageVariant.LIGHT) {
            group.setLogoLightObjectKey(null);
            group.setLogoLightContentType(null);
            group.setLogoLightSizeBytes(null);
        } else {
            group.setLogoDarkObjectKey(null);
            group.setLogoDarkContentType(null);
            group.setLogoDarkSizeBytes(null);
        }
        log.info("Realty group {} logo {} cleared", group.getPublicId(), variant.slug());
        return group;
    }

    @Transactional(readOnly = true)
    public StoredObject fetchLogoBytes(UUID publicId, ImageVariant variant) {
        RealtyGroup group = loadActive(publicId);
        String key = variant == ImageVariant.LIGHT
                ? group.getLogoLightObjectKey()
                : group.getLogoDarkObjectKey();
        return fetchObject(group.getPublicId(), key, RealtyGroupImageNotFoundException.Kind.LOGO);
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

    private StoredImage storeBytes(MultipartFile file, ImagePurpose purpose, String keyWithoutExt) {
        try {
            return imageStorage.storeImage(
                    file.getInputStream(),
                    new ImageStorageContext(purpose, keyWithoutExt));
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

    private StoredObject fetchObject(UUID groupPublicId, String key,
                                      RealtyGroupImageNotFoundException.Kind kind) {
        if (key == null) {
            throw new RealtyGroupImageNotFoundException(groupPublicId, kind);
        }
        try {
            return objectStorage.get(key);
        } catch (ObjectNotFoundException e) {
            // Column points at a key S3 doesn't have — treat as image-not-set to the caller.
            log.warn("Realty group {} {} object missing in storage (key={})",
                    groupPublicId, kind.name().toLowerCase(), key);
            throw new RealtyGroupImageNotFoundException(groupPublicId, kind);
        }
    }
}
