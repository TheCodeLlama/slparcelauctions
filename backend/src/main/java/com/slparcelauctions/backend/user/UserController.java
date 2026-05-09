package com.slparcelauctions.backend.user;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.storage.StoredObject;
import com.slparcelauctions.backend.user.deletion.UserDeletionRequest;
import com.slparcelauctions.backend.user.deletion.UserDeletionService;
import com.slparcelauctions.backend.user.dto.CreateUserRequest;
import com.slparcelauctions.backend.user.dto.UpdateUserRequest;
import com.slparcelauctions.backend.user.dto.UserProfileResponse;
import com.slparcelauctions.backend.user.dto.UserResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final AvatarService avatarService;
    private final UserDefaultCoverService userDefaultCoverService;
    private final UserDeletionService userDeletionService;
    private final UserRepository userRepository;

    @PostMapping
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        UserResponse created = userService.createUser(request);
        return ResponseEntity
                .created(URI.create("/api/v1/users/" + created.publicId()))
                .body(created);
    }

    @GetMapping("/{publicId}")
    public UserProfileResponse getUserProfile(@PathVariable UUID publicId) {
        Long id = resolveUserId(publicId);
        return userService.getPublicProfile(id);
    }

    @GetMapping("/me")
    public UserResponse getMe(@AuthenticationPrincipal AuthPrincipal principal) {
        return userService.getUserById(principal.userId());
    }

    @PutMapping("/me")
    public UserResponse updateCurrentUser(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody UpdateUserRequest request) {
        return userService.updateUser(principal.userId(), request);
    }

    @PostMapping(path = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UserResponse uploadAvatar(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam("file") MultipartFile file) {
        return avatarService.upload(principal.userId(), file);
    }

    @GetMapping("/{publicId}/avatar/{size}")
    public ResponseEntity<byte[]> getAvatar(
            @PathVariable UUID publicId,
            @PathVariable int size) {
        Long id = resolveUserId(publicId);
        StoredObject obj = avatarService.fetch(id, size);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, obj.contentType())
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400, immutable")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(obj.contentLength()))
                .body(obj.bytes());
    }

    /**
     * Public default-cover image proxy. Mirrors the avatar pattern: bytes
     * are served {@code permitAll} so {@code <img src>} renders without an
     * Authorization header. The cover image is destined for use as a
     * listing photo (where it's already public) so the same bytes being
     * fetchable a few hours earlier is not a privacy leak. UUID-suffixed
     * S3 keys prevent enumeration.
     *
     * <p>404 when the user has no default cover set (mapped via
     * {@link UserDefaultCoverNotFoundException} → global handler).
     */
    @GetMapping("/{publicId}/default-cover/image")
    public ResponseEntity<byte[]> getDefaultCoverImage(@PathVariable UUID publicId) {
        Long id = resolveUserId(publicId);
        StoredObject obj = userDefaultCoverService.fetchBytes(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, obj.contentType())
                // Short cache so the navbar/settings card refresh after a
                // user updates their default cover doesn't lag behind the
                // actual bytes for a full day.
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=60")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(obj.contentLength()))
                .body(obj.bytes());
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCurrentUser(
            @Valid @RequestBody UserDeletionRequest body,
            @AuthenticationPrincipal AuthPrincipal principal) {
        userDeletionService.deleteSelf(principal.userId(), body.password());
    }

    private Long resolveUserId(UUID publicId) {
        return userRepository.findByPublicId(publicId)
                .map(User::getId)
                .orElseThrow(() -> new UserNotFoundException(publicId));
    }
}
