package com.slparcelauctions.backend.realty.controller;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.dto.RealtyGroupDtoMapper;
import com.slparcelauctions.backend.realty.dto.RealtyGroupPublicDto;
import com.slparcelauctions.backend.realty.exception.GroupDissolvedException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupImageNotFoundException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.storage.ImagePurpose;
import com.slparcelauctions.backend.storage.ImageStorageContext;
import com.slparcelauctions.backend.storage.ImageStorageService;
import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.storage.StoredImage;
import com.slparcelauctions.backend.storage.StoredObject;
import com.slparcelauctions.backend.storage.exception.ObjectNotFoundException;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Realty-group image surface: multipart logo + cover uploads (gated by
 * {@link RealtyGroupPermission#EDIT_GROUP_PROFILE}) and public byte-serving GETs.
 *
 * <p>Lives as a sibling of {@link RealtyGroupController} so the upload flow's binary
 * handling and S3 wiring don't crowd the JSON CRUD surface. The POST endpoints route
 * through the central {@link ImageStorageService} chokepoint — every byte that touches
 * S3 is sniffed, decoded, resized, and re-encoded to WebP first. The GET endpoints are
 * {@code permitAll} at the security config layer (CLAUDE.md SSR caveat: {@code <img src>}
 * cannot carry the JWT) and serve the underlying S3 object via the
 * {@link ObjectStorageService}.
 *
 * <p>Object keys use {@code realty-groups/{publicId}/logo} and {@code .../cover} with no
 * extension — {@link ImageStorageService} appends {@code .webp} on write. Replacement
 * uploads overwrite the same key (idempotent S3 PUT). The cached {@code logoObjectKey} /
 * {@code coverObjectKey} column on the entity holds the final {@code .webp}-suffixed key,
 * so the GET path looks up the object by that exact column value.
 */
@RestController
@RequestMapping("/api/v1/realty-groups")
@RequiredArgsConstructor
@Slf4j
public class RealtyGroupImageController {

    private final RealtyGroupRepository groups;
    private final RealtyGroupAuthorizer authorizer;
    private final RealtyGroupDtoMapper mapper;
    private final ImageStorageService imageStorage;
    private final ObjectStorageService objectStorage;

    // ─────────────────────── uploads ───────────────────────

    @PostMapping(path = "/{publicId}/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public RealtyGroupPublicDto uploadLogo(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestPart("file") MultipartFile file) {
        return uploadImage(publicId, principal, file, ImagePurpose.LOGO);
    }

    @PostMapping(path = "/{publicId}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public RealtyGroupPublicDto uploadCover(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestPart("file") MultipartFile file) {
        return uploadImage(publicId, principal, file, ImagePurpose.COVER);
    }

    private RealtyGroupPublicDto uploadImage(
            UUID publicId, AuthPrincipal principal, MultipartFile file, ImagePurpose purpose) {
        RealtyGroup group = loadActive(publicId);
        authorizer.assertCan(principal.userId(), group.getId(), RealtyGroupPermission.EDIT_GROUP_PROFILE);

        String suffix = purpose == ImagePurpose.LOGO ? "logo" : "cover";
        // Stable per-group key — re-uploads overwrite the same WebP object. No
        // collision risk because the publicId + suffix is unique per group.
        String keyWithoutExt = "realty-groups/" + group.getPublicId() + "/" + suffix;

        StoredImage stored;
        try {
            stored = imageStorage.storeImage(
                    file.getInputStream(),
                    new ImageStorageContext(purpose, keyWithoutExt));
        } catch (IOException e) {
            throw new UnsupportedImageFormatException("Failed to read upload: " + e.getMessage(), e);
        }

        if (purpose == ImagePurpose.LOGO) {
            group.setLogoObjectKey(stored.objectKey());
            group.setLogoContentType(stored.contentType());
            group.setLogoSizeBytes(stored.sizeBytes());
        } else {
            group.setCoverObjectKey(stored.objectKey());
            group.setCoverContentType(stored.contentType());
            group.setCoverSizeBytes(stored.sizeBytes());
        }
        // JPA dirty checking flushes setters on transaction commit.
        log.info("Realty group {} {} image updated by user {}: key={} ({} bytes)",
                group.getPublicId(), suffix, principal.userId(),
                stored.objectKey(), stored.sizeBytes());
        return mapper.toPublicDto(
                group, principal.userId(), principal.role() == Role.ADMIN);
    }

    // ─────────────────────── byte serving ───────────────────────

    @GetMapping("/{publicId}/logo/image")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> getLogoImage(@PathVariable UUID publicId) {
        return serveImage(publicId, RealtyGroupImageNotFoundException.Kind.LOGO);
    }

    @GetMapping("/{publicId}/cover/image")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> getCoverImage(@PathVariable UUID publicId) {
        return serveImage(publicId, RealtyGroupImageNotFoundException.Kind.COVER);
    }

    private ResponseEntity<byte[]> serveImage(UUID publicId, RealtyGroupImageNotFoundException.Kind kind) {
        RealtyGroup group = loadActive(publicId);
        String key = kind == RealtyGroupImageNotFoundException.Kind.LOGO
                ? group.getLogoObjectKey()
                : group.getCoverObjectKey();
        if (key == null) {
            throw new RealtyGroupImageNotFoundException(group.getPublicId(), kind);
        }
        StoredObject obj;
        try {
            obj = objectStorage.get(key);
        } catch (ObjectNotFoundException e) {
            // Column points at a key S3 doesn't have — treat as image-not-set to the
            // caller (a 5xx would be misleading; the client can't fix it either way).
            log.warn("Realty group {} {} object missing in storage (key={})",
                    publicId, kind.name().toLowerCase(), key);
            throw new RealtyGroupImageNotFoundException(group.getPublicId(), kind);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, obj.contentType())
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(obj.contentLength()))
                .cacheControl(CacheControl.maxAge(300, TimeUnit.SECONDS).cachePublic())
                .body(obj.bytes());
    }

    // ─────────────────────── helpers ───────────────────────

    private RealtyGroup loadActive(UUID publicId) {
        RealtyGroup group = groups.findByPublicId(publicId)
                .orElseThrow(() -> new RealtyGroupNotFoundException(publicId));
        if (group.isDissolved()) {
            throw new GroupDissolvedException(group.getPublicId());
        }
        return group;
    }
}
