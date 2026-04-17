package com.slparcelauctions.backend.parcel;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.parcel.dto.ParcelLookupRequest;
import com.slparcelauctions.backend.parcel.dto.ParcelResponse;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Parcel lookup endpoint. Gated on authenticated + SL-verified user:
 * Spring Security enforces authentication (401 on missing/bad JWT) via
 * {@code SecurityConfig}; the verified-flag check runs inline below and
 * throws {@link AccessDeniedException} (mapped to 403 by
 * {@code GlobalExceptionHandler}) for authenticated-but-unverified users.
 *
 * <p>No dedicated {@code @PreAuthorize} infrastructure exists in this
 * codebase yet; introducing one would be premature. When a second
 * verified-gated endpoint arrives (Task 4: auctions), extract this guard
 * into a shared helper.
 */
@RestController
@RequestMapping("/api/v1/parcels")
@RequiredArgsConstructor
public class ParcelController {

    private final ParcelLookupService service;
    private final UserRepository userRepository;

    @PostMapping("/lookup")
    public ParcelResponse lookup(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody ParcelLookupRequest body) {
        requireVerified(principal.userId());
        return service.lookup(body.slParcelUuid());
    }

    private void requireVerified(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        if (!Boolean.TRUE.equals(user.getVerified())) {
            throw new AccessDeniedException(
                    "SL avatar verification required to look up parcels.");
        }
    }
}
