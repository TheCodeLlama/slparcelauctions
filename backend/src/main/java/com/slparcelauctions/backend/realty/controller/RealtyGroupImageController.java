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
import com.slparcelauctions.backend.realty.service.RealtyGroupImageService;
import com.slparcelauctions.backend.storage.StoredObject;
import com.slparcelauctions.backend.user.Role;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Realty-group image surface: multipart logo + cover uploads (gated by
 * {@link RealtyGroupPermission#EDIT_GROUP_PROFILE}) and public byte-serving GETs.
 *
 * <p>Variant-aware as of plan Task 2: every surface ({@code cover}, {@code logo})
 * accepts a {@code {variant}} path param on POST/DELETE and a {@code ?variant=}
 * query param on GET. The variant token is one of {@code light} or {@code dark}
 * (case-insensitive); anything else surfaces as
 * {@code 400 INVALID_VARIANT} via {@link com.slparcelauctions.backend.common.image.InvalidVariantException}.
 *
 * <p>Lives as a sibling of {@code RealtyGroupController} so the upload flow's binary
 * handling and S3 wiring don't crowd the JSON CRUD surface. The POST/DELETE
 * endpoints route through the central {@link com.slparcelauctions.backend.storage.ImageStorageService}
 * chokepoint inside {@link RealtyGroupImageService} — every byte that touches
 * S3 is sniffed, decoded, resized, and re-encoded to WebP first. The GET endpoints are
 * {@code permitAll} at the security config layer (CLAUDE.md SSR caveat: {@code <img src>}
 * cannot carry the JWT) and serve the underlying S3 object via the
 * {@link com.slparcelauctions.backend.storage.ObjectStorageService}.
 *
 * <p>Object keys use {@code realty-groups/{publicId}/logo-{variant}} and
 * {@code .../cover-{variant}} with no extension — {@code ImageStorageService}
 * appends {@code .webp} on write. Replacement uploads overwrite the same key
 * (idempotent S3 PUT). The cached {@code (logo|cover)(Light|Dark)ObjectKey} columns
 * on the entity hold the final {@code .webp}-suffixed key, so the GET path looks up
 * the object by that exact column value for the requested variant.
 */
@RestController
@RequestMapping("/api/v1/realty-groups")
@RequiredArgsConstructor
@Slf4j
public class RealtyGroupImageController {

    private final RealtyGroupRepository groups;
    private final RealtyGroupAuthorizer authorizer;
    private final RealtyGroupDtoMapper mapper;
    private final RealtyGroupImageService imageService;

    // ─────────────────────── LOGO uploads + delete ───────────────────────

    @PostMapping(path = "/{publicId}/logo/{variant}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public RealtyGroupPublicDto uploadLogo(
            @PathVariable UUID publicId,
            @PathVariable String variant,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestPart("file") MultipartFile file) {
        ImageVariant v = ImageVariant.parse(variant);
        authorize(publicId, principal);
        RealtyGroup updated = imageService.uploadLogo(publicId, v, file);
        return mapper.toPublicDto(updated, principal.userId(), principal.role() == Role.ADMIN);
    }

    @DeleteMapping("/{publicId}/logo/{variant}")
    @Transactional
    public RealtyGroupPublicDto deleteLogo(
            @PathVariable UUID publicId,
            @PathVariable String variant,
            @AuthenticationPrincipal AuthPrincipal principal) {
        ImageVariant v = ImageVariant.parse(variant);
        authorize(publicId, principal);
        RealtyGroup updated = imageService.deleteLogo(publicId, v);
        return mapper.toPublicDto(updated, principal.userId(), principal.role() == Role.ADMIN);
    }

    // ─────────────────────── COVER uploads + delete ───────────────────────

    @PostMapping(path = "/{publicId}/cover/{variant}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public RealtyGroupPublicDto uploadCover(
            @PathVariable UUID publicId,
            @PathVariable String variant,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestPart("file") MultipartFile file) {
        ImageVariant v = ImageVariant.parse(variant);
        authorize(publicId, principal);
        RealtyGroup updated = imageService.uploadCover(publicId, v, file);
        return mapper.toPublicDto(updated, principal.userId(), principal.role() == Role.ADMIN);
    }

    @DeleteMapping("/{publicId}/cover/{variant}")
    @Transactional
    public RealtyGroupPublicDto deleteCover(
            @PathVariable UUID publicId,
            @PathVariable String variant,
            @AuthenticationPrincipal AuthPrincipal principal) {
        ImageVariant v = ImageVariant.parse(variant);
        authorize(publicId, principal);
        RealtyGroup updated = imageService.deleteCover(publicId, v);
        return mapper.toPublicDto(updated, principal.userId(), principal.role() == Role.ADMIN);
    }

    // ─────────────────────── byte serving ───────────────────────

    @GetMapping("/{publicId}/logo/image")
    public ResponseEntity<byte[]> getLogoImage(
            @PathVariable UUID publicId,
            @RequestParam("variant") String variant) {
        ImageVariant v = ImageVariant.parse(variant);
        StoredObject obj = imageService.fetchLogoBytes(publicId, v);
        return serve(obj);
    }

    @GetMapping("/{publicId}/cover/image")
    public ResponseEntity<byte[]> getCoverImage(
            @PathVariable UUID publicId,
            @RequestParam("variant") String variant) {
        ImageVariant v = ImageVariant.parse(variant);
        StoredObject obj = imageService.fetchCoverBytes(publicId, v);
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
