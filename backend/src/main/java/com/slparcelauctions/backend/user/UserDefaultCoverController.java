package com.slparcelauctions.backend.user;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.user.dto.UserDefaultCoverDto;

import lombok.RequiredArgsConstructor;

/**
 * REST endpoints for the per-user default cover image.
 *
 * <ul>
 *   <li>{@code GET /api/v1/users/me/default-cover} — returns the current
 *       cover (presigned URL + MIME + size). 404 when unset.</li>
 *   <li>{@code PUT /api/v1/users/me/default-cover} — multipart upload that
 *       replaces any existing cover. Returns the new DTO.</li>
 *   <li>{@code DELETE /api/v1/users/me/default-cover} — removes the cover.
 *       204; idempotent on already-unset.</li>
 * </ul>
 *
 * <p>Auth is required for all three (the JWT auth filter resolves
 * {@link AuthPrincipal#userId()} via {@code @AuthenticationPrincipal}).
 */
@RestController
@RequestMapping("/api/v1/users/me/default-cover")
@RequiredArgsConstructor
public class UserDefaultCoverController {

    private final UserDefaultCoverService service;

    @GetMapping
    public UserDefaultCoverDto get(@AuthenticationPrincipal AuthPrincipal principal) {
        return service.get(principal.userId());
    }

    @PutMapping(consumes = "multipart/form-data")
    public UserDefaultCoverDto upload(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestPart("file") MultipartFile file) {
        return service.upload(principal.userId(), file);
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthPrincipal principal) {
        service.delete(principal.userId());
        return ResponseEntity.noContent().build();
    }
}
