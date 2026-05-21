package com.slparcelauctions.backend.user;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

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
 * CRUD orchestrator for the per-user default cover image. Stores bytes at
 * {@code users/{userId}/default-cover-{uuid}.{ext}} and three columns on
 * {@code users}: {@code default_cover_light_object_key}, {@code _content_type},
 * {@code _size_bytes}. Replacement uploads delete the prior object after
 * the new one is durably stored; replace-time delete failures are logged
 * but never fail the call (the new key is already on the row).
 *
 * <p>Plan Task 1 of theme-image-variants: this service still touches only
 * the LIGHT slot. The dark sibling columns exist post-V43 but this service
 * does not write to them yet — that wiring lands in Plan Task 4/5.
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
    public UserDefaultCoverDto upload(Long userId, MultipartFile file) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UnsupportedImageFormatException(
                    "Failed to read upload: " + e.getMessage(), e);
        }

        // Build the key sans extension; the chokepoint appends .webp.
        String oldKey = user.getDefaultCoverLightObjectKey();
        String keyWithoutExt = "users/" + userId + "/default-cover-" + UUID.randomUUID();

        StoredImage stored = imageStorage.storeImage(
                new ByteArrayInputStream(bytes),
                new ImageStorageContext(ImagePurpose.DEFAULT_COVER, keyWithoutExt));

        user.setDefaultCoverLightObjectKey(stored.objectKey());
        user.setDefaultCoverLightContentType(stored.contentType());
        user.setDefaultCoverLightSizeBytes(stored.sizeBytes());
        // JPA dirty checking flushes setters on transaction commit.

        if (oldKey != null) {
            try {
                storage.delete(oldKey);
            } catch (Exception e) {
                log.warn("Failed to delete prior default-cover object {} for user {}: {}",
                        oldKey, userId, e.getMessage());
            }
        }

        log.info("User {} default cover updated: key={} ({} bytes)",
                userId, stored.objectKey(), stored.sizeBytes());
        return new UserDefaultCoverDto(
                presign(stored.objectKey()), stored.contentType(), stored.sizeBytes());
    }

    /**
     * Fetches the raw default-cover bytes for the public {@code GET
     * /api/v1/users/{publicId}/default-cover/image} endpoint. Throws
     * {@link UserDefaultCoverNotFoundException} if the user has no cover
     * set; that maps to 404 via the global handler.
     */
    @Transactional(readOnly = true)
    public StoredObject fetchBytes(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        if (user.getDefaultCoverLightObjectKey() == null) {
            throw new UserDefaultCoverNotFoundException(userId);
        }
        return storage.get(user.getDefaultCoverLightObjectKey());
    }

    @Transactional(readOnly = true)
    public UserDefaultCoverDto get(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        if (user.getDefaultCoverLightObjectKey() == null) {
            throw new UserDefaultCoverNotFoundException(userId);
        }
        return new UserDefaultCoverDto(
                presign(user.getDefaultCoverLightObjectKey()),
                user.getDefaultCoverLightContentType(),
                user.getDefaultCoverLightSizeBytes());
    }

    @Transactional
    public void delete(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        String key = user.getDefaultCoverLightObjectKey();
        if (key == null) {
            return;
        }
        user.setDefaultCoverLightObjectKey(null);
        user.setDefaultCoverLightContentType(null);
        user.setDefaultCoverLightSizeBytes(null);
        try {
            storage.delete(key);
        } catch (Exception e) {
            log.warn("Failed to delete S3 object {} for user {}: {}", key, userId, e.getMessage());
        }
        log.info("User {} default cover removed (was key={})", userId, key);
    }

    private String presign(String key) {
        return storage.presignGet(key, PRESIGN_TTL);
    }
}
