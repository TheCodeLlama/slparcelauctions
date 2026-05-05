package com.slparcelauctions.backend.user;

import java.text.Normalizer;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.notification.NotificationService;
import com.slparcelauctions.backend.user.dto.CreateUserRequest;
import com.slparcelauctions.backend.user.dto.UpdateUserRequest;
import com.slparcelauctions.backend.user.dto.UserProfileResponse;
import com.slparcelauctions.backend.user.dto.UserResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final NotificationService notificationService;

    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        String normalized = normalizeUsername(request.username());
        if (userRepository.existsByUsername(normalized)) {
            throw UserAlreadyExistsException.username(normalized);
        }

        User user = User.builder()
                .username(normalized)
                .passwordHash(passwordEncoder.encode(request.password()))
                .build();

        try {
            User saved = userRepository.save(user);
            userRepository.flush();
            log.info("Created user id={} username={}", saved.getId(), saved.getUsername());
            return UserResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            // The functional unique index on LOWER(username) caught a concurrent
            // insert that landed between our existsByUsername check and save.
            // Translate to the same 409 the caller would have gotten without
            // the race.
            throw UserAlreadyExistsException.username(normalized);
        }
    }

    @Transactional(readOnly = true)
    public UserResponse getUserById(Long id) {
        return toResponse(loadUser(id));
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getPublicProfile(Long id) {
        return UserProfileResponse.from(loadUser(id));
    }

    @Transactional
    public UserResponse updateUser(Long id, UpdateUserRequest request) {
        User user = loadUser(id);
        if (request.displayName() != null) {
            user.setDisplayName(request.displayName());
        }
        if (request.bio() != null) {
            user.setBio(request.bio());
        }
        return UserResponse.from(user);
    }

    @Transactional
    public void deleteUser(Long id) {
        User user = loadUser(id);
        userRepository.delete(user);
        log.info("Deleted user id={}", id);
    }

    /**
     * Increments the user's {@code tokenVersion} counter, immediately invalidating every
     * outstanding access token for this account.
     *
     * <p>Call this on any security-sensitive lifecycle event:
     * <ul>
     *   <li>password change (auth slice)</li>
     *   <li>administrative ban or suspension (moderation slice)</li>
     *   <li>role change (admin slice)</li>
     *   <li>logout-all-devices (auth slice)</li>
     *   <li>account deletion (user slice)</li>
     * </ul>
     *
     * <p>The method mutates the managed entity in place; JPA dirty checking flushes the
     * updated {@code token_version} column at transaction commit — no explicit
     * {@code save()} call is needed.
     *
     * @param userId the primary key of the user whose tokens should be invalidated
     * @throws UserNotFoundException if no user with that id exists
     */
    @Transactional
    public void bumpTokenVersion(Long userId) {
        User user = loadUser(userId);
        user.setTokenVersion(user.getTokenVersion() + 1);
        log.info("Bumped tokenVersion for user id={} to {}", userId, user.getTokenVersion());
    }

    /**
     * Converts a {@link User} entity to a {@link UserResponse} DTO, populating
     * {@code unreadNotificationCount} from {@link NotificationService}.
     */
    @Transactional(readOnly = true)
    public UserResponse toResponse(User user) {
        long unreadCount = notificationService.unreadCount(user.getId());
        return UserResponse.from(user, unreadCount);
    }

    private User loadUser(Long id) {
        return userRepository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    }

    /**
     * Normalize a username for storage and lookup: trim, NFC-normalize, then
     * collapse runs of internal whitespace to a single regular space. The
     * resulting string is case-preserved — case-insensitivity is enforced by
     * {@code LOWER(username)} at query and unique-index time, never at the app
     * layer.
     */
    private static String normalizeUsername(String raw) {
        String nfc = Normalizer.normalize(raw, Normalizer.Form.NFC);
        String trimmed = nfc.trim();
        return trimmed.replaceAll("\\s+", " ");
    }
}
