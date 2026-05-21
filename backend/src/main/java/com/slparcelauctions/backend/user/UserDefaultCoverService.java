package com.slparcelauctions.backend.user;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.slparcelauctions.backend.common.image.ImageVariant;
import com.slparcelauctions.backend.storage.ImagePurpose;
import com.slparcelauctions.backend.storage.ImageStorageContext;
import com.slparcelauctions.backend.storage.ImageStorageService;
import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.storage.StoredImage;
import com.slparcelauctions.backend.storage.StoredObject;
import com.slparcelauctions.backend.user.dto.UserDefaultCoverDto;
import com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * CRUD orchestrator for the per-user default cover image. Each operation is
 * variant-aware ({@link ImageVariant#LIGHT light} / {@link ImageVariant#DARK
 * dark}) so the controllers stay thin HTTP shims that parse the variant token
 * and delegate here.
 *
 * <p>Storage keys: {@code users/{userPublicId}/default-cover-{variant}.webp}.
 * Each upload overwrites the same key for its (user, variant) tuple. The
 * trailing {@code .webp} suffix is appended by {@link ImageStorageService};
 * the keyWithoutExt passed in does not include it.
 *
 * <p>Each variant column trio ({@code default_cover_{light,dark}_object_key},
 * {@code _content_type}, {@code _size_bytes}) is written/cleared atomically
 * inside one transaction so the entity row never disagrees with what the
 * controller-emitted DTO claims is on disk. Storage deletes are best-effort —
 * a stale object orphaned by an S3 failure is acceptable; the row already
 * forgets the key.
 *
 * <p>Re-encodes via the central {@link ImageStorageService} chokepoint so
 * raster uploads are converted to WebP and EXIF / IPTC metadata is
 * stripped before bytes hit S3.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserDefaultCoverService {

    private static final Duration PRESIGN_TTL = Duration.ofMinutes(60);

    private final UserRepository userRepository;
    private final ObjectStorageService storage;
    private final ImageStorageService imageStorage;

    @Transactional
    public UserDefaultCoverDto upload(Long userId, ImageVariant variant, MultipartFile file) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UnsupportedImageFormatException(
                    "Failed to read upload: " + e.getMessage(), e);
        }

        String oldKey = readObjectKey(user, variant);
        // Per-variant slot keyed by public UUID + variant slug; no UUID suffix
        // because uploads overwrite the same slot. Chokepoint appends .webp.
        String keyWithoutExt = "users/" + user.getPublicId() + "/default-cover-" + variant.slug();

        StoredImage stored = imageStorage.storeImage(
                new ByteArrayInputStream(bytes),
                new ImageStorageContext(ImagePurpose.DEFAULT_COVER, keyWithoutExt));

        writeSlot(user, variant, stored.objectKey(), stored.contentType(), stored.sizeBytes());
        // JPA dirty checking flushes setters on transaction commit.

        // Idempotent overwrite — the new key equals the old key when the
        // variant slot was previously populated. Only call delete if the
        // prior column referenced a *different* key (legacy rows with the
        // old UUID-suffixed naming still need one final purge).
        if (oldKey != null && !oldKey.equals(stored.objectKey())) {
            try {
                storage.delete(oldKey);
            } catch (Exception e) {
                log.warn("Failed to delete prior default-cover object {} for user {}: {}",
                        oldKey, userId, e.getMessage());
            }
        }

        log.info("User {} default cover {} updated: key={} ({} bytes)",
                userId, variant.slug(), stored.objectKey(), stored.sizeBytes());
        return new UserDefaultCoverDto(
                presign(stored.objectKey()), stored.contentType(), stored.sizeBytes());
    }

    /**
     * Fetches the raw default-cover bytes for the public {@code GET
     * /api/v1/users/{publicId}/default-cover/image?variant=...} endpoint.
     * Throws {@link UserDefaultCoverNotFoundException} if the user has no
     * cover set in the requested variant slot; that maps to 404 via the
     * global handler.
     */
    @Transactional(readOnly = true)
    public StoredObject fetchBytes(Long userId, ImageVariant variant) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        String key = readObjectKey(user, variant);
        if (key == null) {
            throw new UserDefaultCoverNotFoundException(userId);
        }
        return storage.get(key);
    }

    @Transactional(readOnly = true)
    public UserDefaultCoverDto get(Long userId, ImageVariant variant) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        String key = readObjectKey(user, variant);
        if (key == null) {
            throw new UserDefaultCoverNotFoundException(userId);
        }
        return new UserDefaultCoverDto(
                presign(key),
                readContentType(user, variant),
                readSizeBytes(user, variant));
    }

    @Transactional
    public void delete(Long userId, ImageVariant variant) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        String key = readObjectKey(user, variant);
        if (key == null) {
            return;
        }
        writeSlot(user, variant, null, null, null);
        try {
            storage.delete(key);
        } catch (Exception e) {
            log.warn("Failed to delete S3 object {} for user {}: {}", key, userId, e.getMessage());
        }
        log.info("User {} default cover {} removed (was key={})", userId, variant.slug(), key);
    }

    // ─────────────────────── slot helpers ───────────────────────

    private static String readObjectKey(User user, ImageVariant variant) {
        return variant == ImageVariant.LIGHT
                ? user.getDefaultCoverLightObjectKey()
                : user.getDefaultCoverDarkObjectKey();
    }

    private static String readContentType(User user, ImageVariant variant) {
        return variant == ImageVariant.LIGHT
                ? user.getDefaultCoverLightContentType()
                : user.getDefaultCoverDarkContentType();
    }

    private static Long readSizeBytes(User user, ImageVariant variant) {
        return variant == ImageVariant.LIGHT
                ? user.getDefaultCoverLightSizeBytes()
                : user.getDefaultCoverDarkSizeBytes();
    }

    private static void writeSlot(User user, ImageVariant variant,
                                   String objectKey, String contentType, Long sizeBytes) {
        if (variant == ImageVariant.LIGHT) {
            user.setDefaultCoverLightObjectKey(objectKey);
            user.setDefaultCoverLightContentType(contentType);
            user.setDefaultCoverLightSizeBytes(sizeBytes);
        } else {
            user.setDefaultCoverDarkObjectKey(objectKey);
            user.setDefaultCoverDarkContentType(contentType);
            user.setDefaultCoverDarkSizeBytes(sizeBytes);
        }
    }

    private String presign(String key) {
        return storage.presignGet(key, PRESIGN_TTL);
    }
}
