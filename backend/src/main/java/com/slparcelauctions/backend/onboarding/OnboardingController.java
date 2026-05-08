package com.slparcelauctions.backend.onboarding;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.onboarding.dto.OnboardingDisplayNameRequest;
import com.slparcelauctions.backend.sl.SlProfilePhotoService;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.user.dto.UserResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Endpoints for the post-verify forced-onboarding flow:
 *
 * <ul>
 *   <li>{@code GET /sl-profile-photo} — proxies the user's SL profile
 *       photo (scraped from world.secondlife.com, fetched from
 *       picture-service.secondlife.com, Redis-cached). Returns 404 when
 *       the user has no SL avatar UUID set or no profile photo.</li>
 *   <li>{@code POST /avatar/skip} — flips {@code avatar_step_completed}
 *       to true without writing a profile-pic URL.</li>
 *   <li>{@code POST /display-name} — writes a trimmed displayName when
 *       non-blank, otherwise treats as "skip"; in either case flips
 *       {@code display_name_step_completed}.</li>
 * </ul>
 *
 * <p>All three endpoints require an authenticated principal. The verify
 * gate is enforced at the route layer in the frontend, not server-side,
 * so admin tools and onboarding tests can call these on unverified rows
 * without surprise.
 */
@RestController
@RequestMapping("/api/v1/users/me/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final SlProfilePhotoService slProfilePhotoService;
    private final OnboardingService onboardingService;
    private final UserRepository userRepository;

    @GetMapping("/sl-profile-photo")
    public ResponseEntity<byte[]> slProfilePhoto(@AuthenticationPrincipal AuthPrincipal principal) {
        User u = userRepository.findById(principal.userId())
                .orElseThrow(() -> new UserNotFoundException(principal.userId()));
        if (u.getSlAvatarUuid() == null) {
            return ResponseEntity.notFound().build();
        }
        return slProfilePhotoService.fetchProfilePhoto(u.getSlAvatarUuid())
                .map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                        .body(bytes))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/avatar/skip")
    public UserResponse skipAvatar(@AuthenticationPrincipal AuthPrincipal principal) {
        return onboardingService.skipAvatar(principal.userId());
    }

    @PostMapping("/display-name")
    public UserResponse setDisplayName(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody OnboardingDisplayNameRequest body) {
        return onboardingService.setDisplayName(principal.userId(), body.displayName());
    }
}
