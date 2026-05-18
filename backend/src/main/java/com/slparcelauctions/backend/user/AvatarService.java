package com.slparcelauctions.backend.user;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.slparcelauctions.backend.storage.ImagePurpose;
import com.slparcelauctions.backend.storage.ImageStorageContext;
import com.slparcelauctions.backend.storage.ImageStorageService;
import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.storage.StoredObject;
import com.slparcelauctions.backend.storage.exception.ObjectNotFoundException;
import com.slparcelauctions.backend.user.dto.UserResponse;
import com.slparcelauctions.backend.user.exception.AvatarTooLargeException;
import com.slparcelauctions.backend.user.exception.InvalidAvatarSizeException;
import com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrator for avatar upload + fetch. Single {@code @Transactional} on
 * {@link #upload(Long, MultipartFile)} spans validation, image processing,
 * all three S3 puts, and the DB update. See spec section 10 transaction
 * boundary note for why the narrower boundary was walked back during spec
 * self-review (Spring AOP same-class proxy limitation — FOOTGUNS section F.29).
 *
 * <p>S3 puts run BEFORE the DB update so a mid-upload failure leaves the DB
 * still pointing at the previous (possibly null) URL.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AvatarService {

    private static final long MAX_UPLOAD_BYTES = 2L * 1024 * 1024;
    private static final Set<Integer> VALID_SIZES = Set.of(64, 128, 256);
    /** Iteration order doesn't matter (each call is independent) but the
     *  smallest size first keeps log lines monotonic by size. */
    private static final int[] VALID_SIZES_ORDERED = {64, 128, 256};
    private static final String PLACEHOLDER_TEMPLATE = "classpath:static/placeholders/avatar-%d.png";

    private final UserRepository userRepository;
    private final ObjectStorageService storage;
    private final ImageStorageService imageStorage;
    private final ResourceLoader resourceLoader;

    @Transactional
    public UserResponse upload(Long userId, MultipartFile file) {
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new AvatarTooLargeException(file.getSize(), MAX_UPLOAD_BYTES);
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UnsupportedImageFormatException("Failed to read upload: " + e.getMessage(), e);
        }

        // Route every canonical size (64/128/256) through the central
        // WebP chokepoint so the helper handles decode, resize, encode,
        // and S3 write. We pass the same input bytes three times and let
        // the helper resize per the size override. Each call re-decodes
        // — accepted because avatar upload is rare and the spec says
        // "don't over-engineer this". Output key omits the .webp suffix
        // (the helper appends it) so we can keep computing canonical
        // size-suffixed keys.
        for (int size : VALID_SIZES_ORDERED) {
            imageStorage.storeImage(
                    new ByteArrayInputStream(bytes),
                    new ImageStorageContext(
                            ImagePurpose.AVATAR,
                            "avatars/" + userId + "/" + size,
                            size));
        }
        log.info("Avatar uploaded for user {} ({} bytes -> 3 sizes)", userId, bytes.length);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        user.setProfilePicUrl(UserAvatarUrl.forUser(user.getPublicId()));
        // Uploading an avatar implicitly completes the post-verify avatar
        // onboarding step. Idempotent — re-uploading is a no-op on the flag.
        if (!Boolean.TRUE.equals(user.getAvatarStepCompleted())) {
            user.setAvatarStepCompleted(true);
        }
        // JPA dirty checking flushes both setters on transaction commit.
        return UserResponse.from(user);
    }

    public StoredObject fetch(Long userId, int size) {
        if (!VALID_SIZES.contains(size)) {
            throw new InvalidAvatarSizeException(size);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        if (user.getProfilePicUrl() == null) {
            return loadPlaceholder(size);
        }

        // Try the new WebP key first (post-chokepoint migration), then
        // fall back to the legacy PNG key. Historical avatars uploaded
        // before the migration remain at .png; new uploads land at
        // .webp. Avatars have no content-type column on the user row,
        // so we drive the served content type from whichever S3 object
        // exists.
        String webpKey = "avatars/" + userId + "/" + size + ".webp";
        try {
            return storage.get(webpKey);
        } catch (ObjectNotFoundException ignored) {
            // Fall through to legacy PNG.
        }
        String pngKey = "avatars/" + userId + "/" + size + ".png";
        try {
            return storage.get(pngKey);
        } catch (ObjectNotFoundException e) {
            log.error("Orphaned profile_pic_url for userId={} (neither {} nor {} present). "
                    + "Returning placeholder.", userId, webpKey, pngKey);
            return loadPlaceholder(size);
        }
    }

    private StoredObject loadPlaceholder(int size) {
        String resourcePath = String.format(PLACEHOLDER_TEMPLATE, size);
        try (InputStream in = resourceLoader.getResource(resourcePath).getInputStream()) {
            byte[] placeholderBytes = in.readAllBytes();
            return new StoredObject(placeholderBytes, "image/png", placeholderBytes.length);
        } catch (IOException e) {
            throw new IllegalStateException("Placeholder classpath resource missing: " + resourcePath, e);
        }
    }
}
