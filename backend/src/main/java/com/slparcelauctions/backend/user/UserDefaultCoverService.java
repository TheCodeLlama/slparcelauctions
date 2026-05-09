package com.slparcelauctions.backend.user;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.slparcelauctions.backend.auction.ListingPhotoProcessor;
import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.storage.StoredObject;
import com.slparcelauctions.backend.user.dto.UserDefaultCoverDto;
import com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * CRUD orchestrator for the per-user default cover image. Stores bytes at
 * {@code users/{userId}/default-cover-{uuid}.{ext}} and three columns on
 * {@code users}: {@code default_cover_object_key}, {@code _content_type},
 * {@code _size_bytes}. Replacement uploads delete the prior object after
 * the new one is durably stored; replace-time delete failures are logged
 * but never fail the call (the new key is already on the row).
 *
 * <p>Re-encodes via the shared {@link ListingPhotoProcessor} so EXIF / IPTC
 * metadata is stripped before bytes hit S3.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserDefaultCoverService {

    private static final Duration PRESIGN_TTL = Duration.ofMinutes(60);

    private final UserRepository userRepository;
    private final ObjectStorageService storage;
    private final ListingPhotoProcessor processor;

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

        ListingPhotoProcessor.ProcessedPhoto processed = processor.process(bytes);
        String oldKey = user.getDefaultCoverObjectKey();
        String contentType = processed.format().contentType();
        String newKey = "users/" + userId + "/default-cover-" + UUID.randomUUID()
                + "." + processed.format().extension();

        storage.put(newKey, processed.bytes(), contentType);

        user.setDefaultCoverObjectKey(newKey);
        user.setDefaultCoverContentType(contentType);
        user.setDefaultCoverSizeBytes(processed.sizeBytes());
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
                userId, newKey, processed.sizeBytes());
        return new UserDefaultCoverDto(presign(newKey), contentType, processed.sizeBytes());
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
        if (user.getDefaultCoverObjectKey() == null) {
            throw new UserDefaultCoverNotFoundException(userId);
        }
        return storage.get(user.getDefaultCoverObjectKey());
    }

    @Transactional(readOnly = true)
    public UserDefaultCoverDto get(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        if (user.getDefaultCoverObjectKey() == null) {
            throw new UserDefaultCoverNotFoundException(userId);
        }
        return new UserDefaultCoverDto(
                presign(user.getDefaultCoverObjectKey()),
                user.getDefaultCoverContentType(),
                user.getDefaultCoverSizeBytes());
    }

    @Transactional
    public void delete(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        String key = user.getDefaultCoverObjectKey();
        if (key == null) {
            return;
        }
        user.setDefaultCoverObjectKey(null);
        user.setDefaultCoverContentType(null);
        user.setDefaultCoverSizeBytes(null);
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
