package com.slparcelauctions.backend.user;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

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
    private static final String PLACEHOLDER_TEMPLATE = "classpath:static/placeholders/avatar-%d.png";

    private final UserRepository userRepository;
    private final ObjectStorageService storage;
    private final AvatarImageProcessor processor;
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

        Map<Integer, byte[]> resized = processor.process(bytes);

        for (Map.Entry<Integer, byte[]> entry : resized.entrySet()) {
            String key = "avatars/" + userId + "/" + entry.getKey() + ".png";
            storage.put(key, entry.getValue(), "image/png");
        }
        log.info("Avatar uploaded for user {} ({} bytes -> 3 sizes)", userId, bytes.length);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        user.setProfilePicUrl("/api/v1/users/" + userId + "/avatar/256");
        // JPA dirty checking flushes the setProfilePicUrl on transaction commit.
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

        String key = "avatars/" + userId + "/" + size + ".png";
        try {
            return storage.get(key);
        } catch (ObjectNotFoundException e) {
            log.error("Orphaned profile_pic_url for userId={} (S3 key {} missing). Returning placeholder.",
                    userId, key);
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
