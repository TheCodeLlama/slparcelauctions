package com.slparcelauctions.backend.realty.controller;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.common.image.ImageVariant;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.dto.RealtyGroupDtoMapper;
import com.slparcelauctions.backend.realty.dto.RealtyGroupPublicDto;
import com.slparcelauctions.backend.realty.exception.GroupDissolvedException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.realty.service.RealtyGroupDefaultListingImageService;
import com.slparcelauctions.backend.storage.StoredObject;
import com.slparcelauctions.backend.user.Role;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Realty-group default listing picture surface (plan Task 3). Mirrors
 * {@link RealtyGroupImageController} so the wire-shape conventions for the
 * variant token, authorization gate, and byte-serving Cache-Control are
 * identical across cover, logo, and default-listing.
 *
 * <p>POST/DELETE under {@code /api/v1/realty-groups/{publicId}/default-listing/{variant}}
 * are gated by {@link RealtyGroupPermission#EDIT_GROUP_PROFILE}. GET under
 * {@code /api/v1/realty-groups/{publicId}/default-listing/image?variant=light|dark}
 * is {@code permitAll} so the browser's {@code <img>} fetcher (which cannot
 * carry the JWT) can pull bytes anonymously per the CLAUDE.md SSR caveat.
 *
 * <p>Variant token is one of {@code light} / {@code dark} (case-insensitive);
 * anything else surfaces as {@code 400 INVALID_VARIANT} via
 * {@link com.slparcelauctions.backend.common.image.InvalidVariantException}.
 *
 * <p>Object keys: {@code realty-groups/{publicId}/default-listing-{variant}}
 * with no extension - {@link com.slparcelauctions.backend.storage.ImageStorageService}
 * appends {@code .webp} on write. Replacement uploads overwrite the same key
 * (idempotent S3 PUT). The cached {@code defaultListing{Light|Dark}ObjectKey}
 * column on the entity holds the final {@code .webp}-suffixed key.
 */
@RestController
@RequestMapping("/api/v1/realty-groups")
@RequiredArgsConstructor
@Slf4j
public class RealtyGroupDefaultListingImageController {

    private final RealtyGroupRepository groups;
    private final RealtyGroupAuthorizer authorizer;
    private final RealtyGroupDtoMapper mapper;
    private final RealtyGroupDefaultListingImageService imageService;

    // ─────────────────────── POST / DELETE ───────────────────────

    @PostMapping(path = "/{publicId}/default-listing/{variant}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public RealtyGroupPublicDto upload(
            @PathVariable UUID publicId,
            @PathVariable String variant,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestPart("file") MultipartFile file) {
        ImageVariant v = ImageVariant.parse(variant);
        authorize(publicId, principal);
        RealtyGroup updated = imageService.upload(publicId, v, file);
        return mapper.toPublicDto(updated, principal.userId(), principal.role() == Role.ADMIN);
    }

    @DeleteMapping("/{publicId}/default-listing/{variant}")
    @Transactional
    public RealtyGroupPublicDto delete(
            @PathVariable UUID publicId,
            @PathVariable String variant,
            @AuthenticationPrincipal AuthPrincipal principal) {
        ImageVariant v = ImageVariant.parse(variant);
        authorize(publicId, principal);
        RealtyGroup updated = imageService.delete(publicId, v);
        return mapper.toPublicDto(updated, principal.userId(), principal.role() == Role.ADMIN);
    }

    // ─────────────────────── byte serving ───────────────────────

    @GetMapping("/{publicId}/default-listing/image")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> getImage(
            @PathVariable UUID publicId,
            @RequestParam("variant") String variant) {
        ImageVariant v = ImageVariant.parse(variant);
        StoredObject obj = imageService.fetchBytes(publicId, v);
        return serve(obj);
    }

    // ─────────────────────── helpers ───────────────────────

    private void authorize(UUID publicId, AuthPrincipal principal) {
        RealtyGroup group = groups.findByPublicId(publicId)
                .orElseThrow(() -> new RealtyGroupNotFoundException(publicId));
        if (group.isDissolved()) {
            throw new GroupDissolvedException(group.getPublicId());
        }
        authorizer.assertCan(principal.userId(), group.getId(), RealtyGroupPermission.EDIT_GROUP_PROFILE);
    }

    private static ResponseEntity<byte[]> serve(StoredObject obj) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, obj.contentType())
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(obj.contentLength()))
                .cacheControl(CacheControl.maxAge(300, TimeUnit.SECONDS).cachePublic())
                .body(obj.bytes());
    }
}
