package com.slparcelauctions.backend.user;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.common.image.ImageVariant;
import com.slparcelauctions.backend.user.dto.UserDefaultCoverDto;

import lombok.RequiredArgsConstructor;

/**
 * REST endpoints for the per-user default cover image, variant-aware as of
 * plan Task 4 of theme-image-variants.
 *
 * <ul>
 *   <li>{@code GET /api/v1/users/me/default-cover/{variant}} — returns the
 *       current cover for the requested variant (presigned URL + MIME + size).
 *       404 when unset.</li>
 *   <li>{@code POST /api/v1/users/me/default-cover/{variant}} — multipart
 *       upload that replaces any existing cover in the {variant} slot.
 *       Returns the new DTO.</li>
 *   <li>{@code DELETE /api/v1/users/me/default-cover/{variant}} — removes the
 *       cover from the {variant} slot. 204; idempotent on already-unset.</li>
 * </ul>
 *
 * <p>{@code {variant}} is {@code light} or {@code dark} (case-insensitive);
 * anything else surfaces as {@code 400 INVALID_VARIANT} via
 * {@link com.slparcelauctions.backend.common.image.InvalidVariantException}
 * mapped by the global exception handler.
 *
 * <p>Auth is required for all three (the JWT auth filter resolves
 * {@link AuthPrincipal#userId()} via {@code @AuthenticationPrincipal}). The
 * public byte-proxy GET that serves these covers to anonymous {@code <img
 * src>} tags lives in {@link UserController}.
 */
@RestController
@RequestMapping("/api/v1/users/me/default-cover")
@RequiredArgsConstructor
public class UserDefaultCoverController {

    private final UserDefaultCoverService service;

    @GetMapping("/{variant}")
    public UserDefaultCoverDto get(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String variant) {
        ImageVariant v = ImageVariant.parse(variant);
        return service.get(principal.userId(), v);
    }

    @PostMapping(path = "/{variant}", consumes = "multipart/form-data")
    public UserDefaultCoverDto upload(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String variant,
            @RequestPart("file") MultipartFile file) {
        ImageVariant v = ImageVariant.parse(variant);
        return service.upload(principal.userId(), v, file);
    }

    @DeleteMapping("/{variant}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String variant) {
        ImageVariant v = ImageVariant.parse(variant);
        service.delete(principal.userId(), v);
        return ResponseEntity.noContent().build();
    }
}
