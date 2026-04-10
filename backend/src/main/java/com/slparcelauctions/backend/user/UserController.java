package com.slparcelauctions.backend.user;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    public ResponseEntity<ProblemDetail> getCurrentUser() {
        return notYetImplemented();
    }

    @PutMapping("/me")
    public ResponseEntity<ProblemDetail> updateCurrentUser(@Valid @RequestBody UpdateUserRequest request) {
        return notYetImplemented();
    }

    @DeleteMapping("/me")
    public ResponseEntity<ProblemDetail> deleteCurrentUser() {
        return notYetImplemented();
    }

    private ResponseEntity<ProblemDetail> notYetImplemented() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED,
                "Authentication not yet implemented (Task 01-07)");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }
}
