package com.slparcelauctions.backend.user;

import java.net.URI;

import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.user.dto.CreateUserRequest;
import com.slparcelauctions.backend.user.dto.UpdateUserRequest;
import com.slparcelauctions.backend.user.dto.UserProfileResponse;
import com.slparcelauctions.backend.user.dto.UserResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        UserResponse created = userService.createUser(request);
        return ResponseEntity
                .created(URI.create("/api/users/" + created.id()))
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
    public ResponseEntity<ProblemDetail> updateCurrentUser(@Valid @RequestBody UpdateUserRequest request) {
        // Profile edit lands in Task 01-XX (TBD) — needs design pass on field-level edit rules
        return notYetImplemented();
    }

    @DeleteMapping("/me")
    public ResponseEntity<ProblemDetail> deleteCurrentUser() {
        // Profile edit lands in Task 01-XX (TBD) — needs design pass on soft-vs-hard delete + GDPR
        return notYetImplemented();
    }

    private ResponseEntity<ProblemDetail> notYetImplemented() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_IMPLEMENTED,
                "Endpoint not yet implemented");
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(problem);
    }
}
