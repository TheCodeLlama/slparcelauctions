package com.slparcelauctions.backend.user;

import java.net.URI;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.storage.StoredObject;
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

    @PostMapping
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        UserResponse created = userService.createUser(request);
        return ResponseEntity
                .created(URI.create("/api/v1/users/" + created.id()))
                .body(created);
    }

    @GetMapping("/{id}")
    public UserProfileResponse getUserProfile(@PathVariable Long id) {
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

    @GetMapping("/{id}/avatar/{size}")
    public ResponseEntity<byte[]> getAvatar(
            @PathVariable Long id,
            @PathVariable int size) {
        StoredObject obj = avatarService.fetch(id, size);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, obj.contentType())
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400, immutable")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(obj.contentLength()))
                .body(obj.bytes());
    }

    @DeleteMapping("/me")
    public ResponseEntity<ProblemDetail> deleteCurrentUser() {
        // Account deletion has GDPR / soft-delete / cascading-data implications
        // that belong in a dedicated sub-spec. Deferred to a future Epic 02 or
        // Epic 07 task. Keep stub until then.
        return notYetImplemented();
    }

    private ResponseEntity<ProblemDetail> notYetImplemented() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_IMPLEMENTED,
                "Endpoint not yet implemented");
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(problem);
    }
}
