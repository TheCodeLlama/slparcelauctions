# Epic 02 — Sub-spec 2a: Profile API + Avatar Upload

**Date:** 2026-04-14
**Epic:** 02 — Player Verification
**Sub-spec scope:** Task 02-03 (user profile backend API + image upload)
**Status:** design
**Author:** brainstorming session

---

## 1. Summary

Ship the backend half of Epic 02's profile work: an authenticated user can `PUT /api/v1/users/me` to edit their display name and bio, `POST /api/v1/users/me/avatar` to upload a JPG/PNG/WebP image (≤2MB), and any client (authenticated or not) can `GET /api/v1/users/{id}/avatar/{size}` to fetch the avatar bytes at one of three sizes (64/128/256px). Avatars live in a private S3-compatible bucket: MinIO in dev via endpoint override, real AWS S3 in prod via the SDK's `DefaultCredentialsProvider`. The backend proxies all reads — the bucket never gets a publicly-readable policy.

Frontend work (dashboard UI with the verification flow, public profile page, `<Avatar>` component, profile-edit form) is **out of scope**. Sub-spec 2b covers that once this backend ships and is Postman-testable.

---

## 2. Scope

### In scope

1. **MinIO in docker-compose.** New `minio` service on the existing `slpa-net` network, with a healthcheck that the backend service `depends_on` via `service_healthy` condition.
2. **`storage/` vertical slice.** New package containing S3 client configuration (profile-aware: dev endpoint override + static creds, prod DefaultCredentialsProvider), `ObjectStorageService` interface + `S3ObjectStorageService` impl (`put` / `get` / `delete` / `deletePrefix` / `exists`), startup bucket validator, and the two storage-level exception types.
3. **`user/` slice extensions.** `AvatarImageProcessor` (Thumbnailator wrapper with ImageIO-based format sniffing), `AvatarService` (orchestrator: validate → process → put 3 sizes → update User row), extended `UserController` with `PUT /me` filled in plus the two new avatar endpoints, `UpdateUserRequest` hardened against unknown fields, `UserExceptionHandler` scoped to `user/` with handlers for avatar and update-specific exceptions.
4. **`GlobalExceptionHandler` extension.** Handler for `MaxUploadSizeExceededException` producing the same 413 `ProblemDetail` shape as `AvatarTooLargeException` (which lives in `UserExceptionHandler`) so clients see consistent responses regardless of which layer caught the bloat.
5. **Three classpath placeholder PNGs** shipped as test-fixture-adjacent resources under `backend/src/main/resources/static/placeholders/avatar-{64,128,256}.png`. Returned by the proxy endpoint when a user hasn't uploaded an avatar yet, or when their DB `profilePicUrl` is set but the S3 object is unexpectedly missing.
6. **One throw-in fix:** `docker-compose.yml` backend healthcheck line 69 currently references `/api/health`, which became a 401 after Epic 02 sub-spec 1's `/api/v1` rename. Fix to `/api/v1/health` in the same commit that adds the MinIO service.
7. **Three levels of tests** per CONVENTIONS §Backend Testing — unit, slice, integration — matching the conventions the Epic 02 sub-spec 1 integration tests established.

### Out of scope (deferred to sub-spec 2b or later)

- Task 02-04 dashboard UI (verification code flow, account settings form, tab structure).
- Task 02-05 public profile page at `/users/[id]`.
- `DELETE /api/v1/users/me` (account deletion) — existing stub in `UserController` stays 501 with a TODO comment pointing at a future GDPR sub-spec.
- Avatar deletion (`DELETE /me/avatar` to revert to placeholder) — not in task doc, not needed for Phase 1.
- Presigned-URL serving, public bucket policies, CDN integration — rejected in Q3 brainstorming.
- WebP output format — rejected in Q4b; PNG for all three sizes.
- Image cropping UI (manual crop tool) — frontend concern, not applicable to sub-spec 2a.

### Non-goals

- **No new Flyway migration.** `users.profile_pic_url text` already exists from V1 migration; sub-spec 2a only reads and writes that column.
- **No `users/me` DTO reshape unless fields are actually missing.** Task 5 inspects `UserResponse` for completeness against the task doc's private-profile field list and adds any gaps, but doesn't proactively redesign the record.
- **No WebSocket or realtime avatar events.** Re-uploading an avatar does not push updates to active sessions; browsers pick up changes via the `?v={user.updatedAt}` cache-buster that sub-spec 2b's `<Avatar>` component will append.

---

## 3. Background and references

- `docs/implementation/epic-02/02-player-verification.md` — phase goal.
- `docs/implementation/epic-02/task-03-user-profile-backend.md` — Task 02-03 spec.
- `docs/implementation/CONVENTIONS.md` — project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).
- `docs/superpowers/specs/2026-04-13-epic-02-sub-1-verification-backend.md` — immediate predecessor; established the `@Order(LOWEST_PRECEDENCE - 100)` slice-advice convention, the `/api/v1` URL prefix, the `@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("dev")` integration-test pattern, and the FOOTGUNS F.22-F.27 entries that sub-spec 2a will extend.
- `backend/src/main/java/com/slparcelauctions/backend/auth/exception/AuthExceptionHandler.java` — `@RestControllerAdvice(basePackages = ...)` + `@Order(LOWEST_PRECEDENCE - 100)` shape to match.
- `backend/src/main/java/com/slparcelauctions/backend/sl/StorageStartupValidator.java` — startup-validator pattern: `@EventListener(ApplicationReadyEvent.class)` + profile-aware fail-fast-in-prod / WARN-in-dev.
- `backend/src/main/java/com/slparcelauctions/backend/user/User.java` — entity with existing `profile_pic_url` column.
- `docker-compose.yml` — existing postgres, redis, backend, frontend services on `slpa-net`; sub-spec 2a adds `minio`.
- `backend/src/main/resources/db/migration/V1__core_tables.sql` — baseline `users` table schema.

---

## 4. API surface

### 4.1 Filled-in endpoint (from Epic 01 stub)

#### `PUT /api/v1/users/me`

**Auth:** JWT.
**Purpose:** Update the caller's `displayName` and/or `bio`.

**Request body:**
```json
{
  "displayName": "Alice Resident",
  "bio": "Designer of whimsical cottages in the Corsica region."
}
```

Both fields are optional:
- `null` → do not touch the column.
- Present + valid → update.
- Present + invalid → 400 validation error.

`@JsonIgnoreProperties(ignoreUnknown = false)` on the DTO rejects any extra field (`email`, `role`, `verified`, etc) with a 400. This is load-bearing against privilege escalation via field injection.

**Validation rules:**
- `displayName`: `@Size(min=1, max=50)` — empty string is rejected as below min, 51+ chars rejected. Null passes (`@Size` does not fire on null; the service then skips the column).
- `bio`: `@Size(max=500)` — no min. Empty string is allowed (explicitly clears the bio). 501+ chars rejected.

**Response — 200:** full `UserResponse` (private shape, includes email + verification details).

**Response — 400:**
- Field validation: `ProblemDetail` with `type: https://slpa.example/problems/validation`, existing `GlobalExceptionHandler.handleValidation`. `errors` property enumerates field-level failures.
- Unknown field: `ProblemDetail` with `type: https://slpa.example/problems/user/unknown-field`, `title: "Unknown field"`, `detail` naming the rejected property via `UnrecognizedPropertyException.getPropertyName()`.

**Response — 401:** no JWT → existing `JwtAuthenticationEntryPoint` handles.

### 4.2 New endpoint — avatar upload

#### `POST /api/v1/users/me/avatar`

**Auth:** JWT.
**Purpose:** Upload a profile picture. Server resizes to 64/128/256 PNGs and puts all three to S3/MinIO.
**Content-Type:** `multipart/form-data`, field name `file`.

**Request:** single multipart part named `file` containing a JPG, PNG, or WebP image ≤2MB.

**Response — 200:** full `UserResponse` with `profilePicUrl` set to `/api/v1/users/{id}/avatar/256` (the relative proxy path to the largest size). The frontend rewrites the trailing segment to request smaller sizes.

**Response — 413 Payload Too Large:**
- Spring multipart parser caught oversized upload before the handler ran → `MaxUploadSizeExceededException` → `GlobalExceptionHandler.handleMaxUploadSize` → `ProblemDetail` with `type: https://slpa.example/problems/user/upload-too-large`, `title: "Upload too large"`, `detail: "Avatar must be 2MB or less."`
- Controller-level defensive check (file size re-read from `MultipartFile.getSize()`) → `AvatarTooLargeException` → `UserExceptionHandler.handleAvatarTooLarge` → same 413 shape, same URI, same title. Clients cannot distinguish which layer caught it (by design).

**Response — 400 Bad Request:**
- Unsupported format (ImageIO sniff rejects, or sniff succeeds but format ∉ {jpeg, png, webp}) → `UnsupportedImageFormatException` → `UserExceptionHandler.handleUnsupportedImageFormat` → `ProblemDetail` with `type: https://slpa.example/problems/user/unsupported-image-format`, `title: "Unsupported image format"`, `detail: "Upload must be a JPEG, PNG, or WebP image."`
- Missing `file` multipart part → existing Spring handling returns 400 via `GlobalExceptionHandler.handleValidation` or similar (no new handler).

**Response — 500:** S3 put fails with a non-404 `SdkException` → bubbles to `GlobalExceptionHandler.handleUnexpected`.

### 4.3 New endpoint — avatar serve (public proxy)

#### `GET /api/v1/users/{id}/avatar/{size}`

**Auth:** Public (no JWT required). Same rationale as `GET /api/v1/users/{id}` — avatars are intentionally public.
**Purpose:** Proxy avatar bytes from S3 through the backend.

**Path variables:**
- `id` — user ID (positive integer).
- `size` — one of `64`, `128`, `256`. Any other value → 400.

**Response — 200:**
- `Content-Type: image/png`
- `Cache-Control: public, max-age=86400, immutable`
- `Content-Length: <bytes>`
- Body: PNG bytes.

The `immutable` directive means browsers cache aggressively and never revalidate. Sub-spec 2b's `<Avatar>` component appends `?v={user.updatedAt}` to the URL so re-uploads cache-bust naturally.

**Response flow (AvatarService.fetch):**

1. Load user from repository; if not found → `UserNotFoundException` → 404 `ProblemDetail` with `type: https://slpa.example/problems/user/not-found`.
2. If `user.profilePicUrl == null` → load classpath placeholder at `static/placeholders/avatar-{size}.png`, return 200 with those bytes.
3. Else compute S3 key `avatars/{userId}/{size}.png`, call `storage.get(key)`:
   - **Success** → return the bytes.
   - **`ObjectNotFoundException`** (S3 returned 404 despite DB saying the avatar exists) → log at `ERROR` with `"Orphaned profile_pic_url for userId={} (S3 key {} missing). Returning placeholder."`, return the placeholder bytes. The error log is the ops signal; the user gets a graceful fallback.
   - **Any other `SdkException`** → bubbles to `GlobalExceptionHandler.handleUnexpected` → 500.

**Response — 400:** `size ∉ {64, 128, 256}` → `InvalidAvatarSizeException` → `UserExceptionHandler.handleInvalidAvatarSize` → `ProblemDetail` with `type: https://slpa.example/problems/user/invalid-avatar-size`, `title: "Invalid avatar size"`, `detail: "Avatar size must be 64, 128, or 256."`

**Response — 404:** user does not exist → existing `UserNotFoundException` handler → `type: https://slpa.example/problems/user/not-found`.

### 4.4 Routes that stay 501

#### `DELETE /api/v1/users/me`

**Still returns 501.** The existing `notYetImplemented()` stub is kept, with its comment updated to:

```java
// Account deletion has GDPR / soft-delete / cascading-data implications
// that belong in a dedicated sub-spec. Deferred to a future Epic 02 or
// Epic 07 task. Keep stub until then.
```

Scope explicitly locked to prevent feature creep.

---

## 5. Package structure

New `storage/` package + extensions to the existing `user/` package.

```
com.slparcelauctions.backend/
├── storage/                                    [NEW PACKAGE]
│   ├── StorageConfigProperties.java            record @ConfigurationProperties("slpa.storage")
│   ├── S3ClientConfig.java                     @Configuration producing S3Client bean
│   ├── StorageStartupValidator.java            @EventListener(ApplicationReadyEvent.class)
│   ├── ObjectStorageService.java               interface: put, get, delete, deletePrefix, exists
│   ├── S3ObjectStorageService.java             @Service impl wrapping software.amazon.awssdk.services.s3.S3Client
│   ├── StoredObject.java                       record { byte[] bytes, String contentType, long contentLength }
│   └── exception/
│       ├── ObjectNotFoundException.java        thrown on S3 404
│       └── ObjectStorageException.java         wraps non-404 SdkException
├── user/   (existing slice — extended)
│   ├── User.java                               unchanged
│   ├── UserRepository.java                     unchanged
│   ├── UserService.java                        existing updateUser method used as-is for PUT /me
│   ├── UserController.java                     EXTENDED: PUT /me filled in, + 2 new @-mappings
│   ├── AvatarService.java                      [NEW] orchestrator, see §10
│   ├── AvatarImageProcessor.java               [NEW] Thumbnailator wrapper, see §9
│   ├── UserExceptionHandler.java               [NEW] @RestControllerAdvice(basePackages = "...user")
│   ├── dto/
│   │   ├── UpdateUserRequest.java              EXTENDED: @JsonIgnoreProperties + @Size
│   │   └── (existing DTOs unchanged)
│   └── exception/
│       ├── (existing UserNotFoundException, UserAlreadyExistsException)
│       ├── UnsupportedImageFormatException.java [NEW]
│       ├── AvatarTooLargeException.java         [NEW]
│       └── InvalidAvatarSizeException.java      [NEW]
└── common/exception/
    └── GlobalExceptionHandler.java             EXTENDED: new @ExceptionHandler for MaxUploadSizeExceededException
```

**Dependency direction:** `user/ → storage/`. `storage/` depends on nothing else in the project. Package-private where possible; the `storage/` interface + exceptions + `StoredObject` record are public (needed by `user/`).

---

## 6. Data model

**No schema changes.** `users.profile_pic_url` is `text NULL` from V1 migration. Sub-spec 2a writes the relative proxy path (`/api/v1/users/{id}/avatar/256`) to it after successful upload, or leaves it `NULL` for new users.

**`profile_pic_url` as canary for "has avatar":** null means the user never uploaded; non-null means the three S3 keys at `avatars/{userId}/{64,128,256}.png` should exist. A re-upload overwrites all three keys, then updates the DB row. If the S3 puts fail partway, the DB stays pointing at the old path (possibly null), so a re-attempt works.

**`UserResponse.profilePicUrl` default:** existing record already has the field. Sub-spec 2a does not change the record shape (unless Task 5's `/me` response inspection reveals missing fields — see §16 Task 5).

---

## 7. Config properties, S3 client wiring, startup validator

### 7.1 `StorageConfigProperties`

```java
package com.slparcelauctions.backend.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "slpa.storage")
public record StorageConfigProperties(
        String bucket,
        String region,
        String endpointOverride,
        boolean pathStyleAccess,
        String accessKeyId,
        String secretAccessKey
) {
    public StorageConfigProperties {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("slpa.storage.bucket must be configured");
        }
        if (region == null || region.isBlank()) {
            region = "us-east-1";
        }
    }

    public boolean hasStaticCredentials() {
        return accessKeyId != null && !accessKeyId.isBlank()
            && secretAccessKey != null && !secretAccessKey.isBlank();
    }

    public boolean hasEndpointOverride() {
        return endpointOverride != null && !endpointOverride.isBlank();
    }
}
```

Bound via `@EnableConfigurationProperties(StorageConfigProperties.class)` on `S3ClientConfig` (see §7.3) so a separate enabler class isn't needed.

### 7.2 `application.yml` additions

**`application.yml`** (base, appended):
```yaml
slpa:
  storage:
    bucket: slpa-uploads
    region: us-east-1
    path-style-access: false

spring:
  servlet:
    multipart:
      max-file-size: 2MB
      max-request-size: 2MB
```

**`application-dev.yml`** (appended):
```yaml
slpa:
  storage:
    endpoint-override: ${SLPA_STORAGE_ENDPOINT:http://localhost:9000}
    path-style-access: true
    access-key-id: ${MINIO_ROOT_USER:slpa-dev-key}
    secret-access-key: ${MINIO_ROOT_PASSWORD:slpa-dev-secret}
```

**`application-prod.yml`** (appended):
```yaml
slpa:
  storage:
    bucket: ${SLPA_STORAGE_BUCKET}
    region: ${AWS_REGION:us-east-1}
```

Prod profile intentionally omits `endpoint-override`, `access-key-id`, `secret-access-key`. The SDK's `DefaultCredentialsProvider` picks up credentials from env / IAM role / web identity token. The `StorageStartupValidator` fails fast in prod if the bucket is unreachable.

### 7.3 `S3ClientConfig`

```java
package com.slparcelauctions.backend.storage;

import java.net.URI;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

@Configuration
@EnableConfigurationProperties(StorageConfigProperties.class)
@RequiredArgsConstructor
@Slf4j
public class S3ClientConfig {

    private final StorageConfigProperties props;

    @Bean
    public S3Client s3Client() {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(props.region()));

        if (props.hasStaticCredentials()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.accessKeyId(), props.secretAccessKey())));
            log.info("S3 client using static credentials (dev/test profile)");
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
            log.info("S3 client using DefaultCredentialsProvider (prod profile)");
        }

        if (props.hasEndpointOverride()) {
            builder.endpointOverride(URI.create(props.endpointOverride()));
            log.info("S3 endpoint override: {}", props.endpointOverride());
        }

        if (props.pathStyleAccess()) {
            builder.forcePathStyle(true);
        }

        return builder.build();
    }
}
```

### 7.4 `StorageStartupValidator`

```java
package com.slparcelauctions.backend.storage;

import java.util.Arrays;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Fails fast on startup in the {@code prod} profile if the configured bucket is
 * unreachable or nonexistent. Auto-creates the bucket in non-prod profiles so
 * {@code docker compose up} just works on a fresh MinIO volume.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StorageStartupValidator {

    private final S3Client s3;
    private final StorageConfigProperties props;
    private final Environment environment;

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(props.bucket()).build());
            log.info("Storage bucket '{}' reachable", props.bucket());
        } catch (NoSuchBucketException e) {
            if (isProd) {
                throw new IllegalStateException(
                        "Storage bucket '" + props.bucket() + "' does not exist in prod profile. "
                        + "Provision via Terraform before deploying.", e);
            }
            log.warn("Storage bucket '{}' missing (non-prod profile); creating...", props.bucket());
            s3.createBucket(CreateBucketRequest.builder().bucket(props.bucket()).build());
            log.info("Storage bucket '{}' created", props.bucket());
        } catch (S3Exception e) {
            throw new IllegalStateException(
                    "Storage bucket '" + props.bucket() + "' is unreachable: " + e.getMessage(), e);
        }
    }
}
```

---

## 8. `ObjectStorageService` interface + `S3ObjectStorageService` impl

### 8.1 Interface

```java
package com.slparcelauctions.backend.storage;

public interface ObjectStorageService {
    void put(String key, byte[] bytes, String contentType);
    StoredObject get(String key);                          // throws ObjectNotFoundException on S3 404
    void delete(String key);
    void deletePrefix(String keyPrefix);                   // batch delete with pagination
    boolean exists(String key);
}
```

```java
package com.slparcelauctions.backend.storage;

public record StoredObject(byte[] bytes, String contentType, long contentLength) {}
```

**Why an interface when there's only one impl?** Mild over-engineering, kept because mocking the interface in `AvatarServiceTest` is trivial (5 methods) and avoids having to mock the real `S3Client` (which has ~50 methods and would pull the whole SDK into the unit test classpath).

### 8.2 `S3ObjectStorageService` impl

```java
package com.slparcelauctions.backend.storage;

import java.util.List;

import org.springframework.stereotype.Service;

import com.slparcelauctions.backend.storage.exception.ObjectNotFoundException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
@RequiredArgsConstructor
@Slf4j
public class S3ObjectStorageService implements ObjectStorageService {

    private final S3Client s3;
    private final StorageConfigProperties props;

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(props.bucket())
                        .key(key)
                        .contentType(contentType)
                        .contentLength((long) bytes.length)
                        .build(),
                RequestBody.fromBytes(bytes));
        log.debug("S3 put: bucket={} key={} size={}", props.bucket(), key, bytes.length);
    }

    /**
     * Loads the full object into memory as a {@code byte[]}. Appropriate for avatar-sized
     * PNGs (<150KB each). <strong>Do NOT reuse this method for larger objects</strong>
     * (parcel photos, listing images, review photos) without first refactoring to return
     * a streaming {@code ResponseInputStream<GetObjectResponse>} — a future caller that
     * tries to load a 5MB parcel photo via this method will blow the heap on a hot path.
     */
    @Override
    public StoredObject get(String key) {
        try {
            ResponseBytes<GetObjectResponse> response = s3.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(props.bucket())
                            .key(key)
                            .build());
            return new StoredObject(
                    response.asByteArray(),
                    response.response().contentType(),
                    response.response().contentLength());
        } catch (NoSuchKeyException e) {
            throw new ObjectNotFoundException(key, e);
        }
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(props.bucket())
                .key(key)
                .build());
    }

    /**
     * Batch delete every object under the given key prefix. Paginates via
     * {@code isTruncated} + continuation token so > 1000 objects are handled correctly.
     */
    @Override
    public void deletePrefix(String keyPrefix) {
        String continuationToken = null;
        int totalDeleted = 0;
        while (true) {
            ListObjectsV2Request.Builder listBuilder = ListObjectsV2Request.builder()
                    .bucket(props.bucket())
                    .prefix(keyPrefix);
            if (continuationToken != null) {
                listBuilder.continuationToken(continuationToken);
            }
            ListObjectsV2Response listed = s3.listObjectsV2(listBuilder.build());
            if (!listed.contents().isEmpty()) {
                List<ObjectIdentifier> ids = listed.contents().stream()
                        .map(o -> ObjectIdentifier.builder().key(o.key()).build())
                        .toList();
                s3.deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(props.bucket())
                        .delete(Delete.builder().objects(ids).build())
                        .build());
                totalDeleted += ids.size();
            }
            if (Boolean.FALSE.equals(listed.isTruncated())) {
                break;
            }
            continuationToken = listed.nextContinuationToken();
        }
        log.info("Deleted {} objects under prefix '{}'", totalDeleted, keyPrefix);
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder()
                    .bucket(props.bucket())
                    .key(key)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }
}
```

### 8.3 Storage exceptions

```java
package com.slparcelauctions.backend.storage.exception;

import lombok.Getter;

@Getter
public class ObjectNotFoundException extends RuntimeException {
    private final String key;

    public ObjectNotFoundException(String key, Throwable cause) {
        super("Object not found: " + key, cause);
        this.key = key;
    }
}
```

```java
package com.slparcelauctions.backend.storage.exception;

public class ObjectStorageException extends RuntimeException {
    public ObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`ObjectStorageException` is constructed at the rare failure points that aren't already wrapped by the AWS SDK (e.g., constructor-side bucket config errors). Most `SdkException` failures just propagate unchanged.

---

## 9. `AvatarImageProcessor`

Pure byte-in / byte-out component. Zero Spring dependencies in the happy path (the `@Component` annotation is so it participates in autowiring; the class itself has no `@Autowired` fields).

```java
package com.slparcelauctions.backend.user;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException;

import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.geometry.Positions;

@Component
@Slf4j
public class AvatarImageProcessor {

    public static final int[] SIZES = {64, 128, 256};
    private static final Set<String> ALLOWED_FORMATS = Set.of("jpeg", "png", "webp");

    /**
     * Sniff format via ImageIO, center-crop to square, resize to all three target sizes,
     * output PNG bytes. The sniff step trusts the bytes, not the multipart Content-Type
     * header (which is trivially client-controlled).
     *
     * @return map from target size (int) to PNG byte[], ordered {64, 128, 256}.
     * @throws UnsupportedImageFormatException on any failure — unrecognized format,
     *         format not in the allow-list, decode failure, resize failure.
     */
    public Map<Integer, byte[]> process(byte[] inputBytes) {
        BufferedImage original;
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(inputBytes))) {
            if (iis == null) {
                throw new UnsupportedImageFormatException("Failed to open image stream");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new UnsupportedImageFormatException("Unrecognized image format");
            }
            ImageReader reader = readers.next();
            String formatName = reader.getFormatName().toLowerCase(Locale.ROOT);
            if (!ALLOWED_FORMATS.contains(formatName)) {
                throw new UnsupportedImageFormatException(
                        "Format '" + formatName + "' not allowed. Use JPEG, PNG, or WebP.");
            }
            reader.setInput(iis);
            try {
                original = reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new UnsupportedImageFormatException("Failed to decode image: " + e.getMessage(), e);
        }

        Map<Integer, byte[]> out = new LinkedHashMap<>(3);
        for (int size : SIZES) {
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                Thumbnails.of(original)
                        .crop(Positions.CENTER)
                        .size(size, size)
                        .outputFormat("png")
                        .toOutputStream(baos);
                out.put(size, baos.toByteArray());
            } catch (IOException e) {
                throw new UnsupportedImageFormatException(
                        "Failed to resize image to " + size + "px: " + e.getMessage(), e);
            }
        }
        return out;
    }
}
```

**`reader.dispose()` in finally** — prevents `ImageReader` handles from leaking. Lesson from every Java image pipeline ever.

**`ByteArrayOutputStream` per size** — each size is a fresh buffer. Sizes are small enough (<150KB each) that this is negligible memory pressure compared to the original `BufferedImage`.

**Output format hardcoded to `"png"`** — matches Q4b decision. Every size is a lossless PNG.

---

## 10. `AvatarService`

Orchestrator for upload + fetch. Single `@Transactional` on the public `upload` method. See the "Transaction boundary" note below the code for why the Checkpoint 2 "narrow transaction" idea was walked back during spec self-review.

```java
package com.slparcelauctions.backend.user;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.storage.StoredObject;
import com.slparcelauctions.backend.storage.exception.ObjectNotFoundException;
import com.slparcelauctions.backend.user.dto.UserResponse;
import com.slparcelauctions.backend.user.exception.AvatarTooLargeException;
import com.slparcelauctions.backend.user.exception.InvalidAvatarSizeException;
import com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class AvatarService {

    private static final long MAX_UPLOAD_BYTES = 2L * 1024 * 1024;
    private static final Set<Integer> VALID_SIZES = Set.of(64, 128, 256);
    private static final String PLACEHOLDER_TEMPLATE = "classpath:static/placeholders/avatar-%d.png";

    private final UserRepository userRepository;
    private final ObjectStorageService storage;
    private final AvatarImageProcessor processor;
    private final ResourceLoader resourceLoader;

    /**
     * Upload pipeline: validate → process → put 3 objects → update DB row.
     *
     * <p>The single {@code @Transactional} annotation spans the entire method,
     * including the S3 puts. See the "Transaction boundary" note in spec §10
     * for why this is acceptable at Phase 1 scale. Put ordering is still
     * "all 3 S3 puts before the User row update" so a mid-upload failure
     * leaves the DB still pointing at the previous (possibly null) URL and
     * a retry is safe.
     */
    @Transactional
    public UserResponse upload(Long userId, MultipartFile file) {
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new AvatarTooLargeException(file.getSize(), MAX_UPLOAD_BYTES);
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UnsupportedImageFormatException("Failed to read upload: " + e.getMessage(), e);
        }

        Map<Integer, byte[]> resized = processor.process(bytes);

        for (Map.Entry<Integer, byte[]> entry : resized.entrySet()) {
            String key = "avatars/" + userId + "/" + entry.getKey() + ".png";
            storage.put(key, entry.getValue(), "image/png");
        }
        log.info("Avatar uploaded for user {} ({} bytes → 3 sizes)", userId, bytes.length);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        user.setProfilePicUrl("/api/v1/users/" + userId + "/avatar/256");
        // JPA dirty checking flushes the setProfilePicUrl on transaction commit;
        // no explicit save() needed.
        return UserResponse.from(user);
    }

    /**
     * Fetches avatar bytes for the proxy endpoint. Returns the classpath placeholder
     * when the user hasn't uploaded OR when S3 is unexpectedly missing the key.
     */
    public StoredObject fetch(Long userId, int size) {
        if (!VALID_SIZES.contains(size)) {
            throw new InvalidAvatarSizeException(size);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        if (user.getProfilePicUrl() == null) {
            return loadPlaceholder(size);
        }

        String key = "avatars/" + userId + "/" + size + ".png";
        try {
            return storage.get(key);
        } catch (ObjectNotFoundException e) {
            log.error("Orphaned profile_pic_url for userId={} (S3 key {} missing). Returning placeholder.",
                    userId, key);
            return loadPlaceholder(size);
        }
    }

    private StoredObject loadPlaceholder(int size) {
        String resourcePath = String.format(PLACEHOLDER_TEMPLATE, size);
        try (InputStream in = resourceLoader.getResource(resourcePath).getInputStream()) {
            byte[] placeholderBytes = in.readAllBytes();
            return new StoredObject(placeholderBytes, "image/png", placeholderBytes.length);
        } catch (IOException e) {
            throw new IllegalStateException("Placeholder classpath resource missing: " + resourcePath, e);
        }
    }
}
```

**Transaction boundary note (spec self-review correction).** Checkpoint 2 pushed back on `@Transactional` spanning the whole upload (including S3 puts) with the suggestion to narrow the boundary to a private `updateProfilePicUrl` helper. That approach is **not workable in Spring**: the `@Transactional` annotation relies on AOP proxying, and a same-class method call (`this.updateProfilePicUrl(...)`) bypasses the proxy regardless of the target method's visibility — so `@Transactional` on a private or protected helper method called from the same class would never open a transaction, and JPA dirty checking would silently fail to flush the `setProfilePicUrl` update.

Three real options existed:
1. Extract the DB mutation to a separate bean (`UserProfilePicUpdater`), inject it, call through the bean reference (the proxy fires correctly).
2. Self-inject `AvatarService` via `@Autowired @Lazy AvatarService self;` and call `self.updateProfilePicUrl(...)`.
3. Keep the single `@Transactional` on the public `upload()` method.

**Chosen: option 3.** The Checkpoint 2 pushback framed the narrow boundary as "minor optimization, not blocking." At Phase 1 scale (few hundred users, local MinIO ~50-100ms per put, real AWS S3 ~100-300ms per put), `upload()` holds a DB connection for maybe 300-900ms per call. The DB connection pool has ~10 connections and concurrent avatar uploads are rare — it's fine. Adding a second bean or self-injection plumbing to shave ~500ms of connection hold time is not worth the maintenance surface. If future profiling shows avatar uploads are actually starving the DB pool, revisit with option 1 (the cleanest of the three).

**Important consequence:** Exception propagation from any of the S3 puts will roll back the DB transaction — which is correct (no DB update happened either way, so nothing to roll back in practice). Exception from the `userRepository.findById` or `setProfilePicUrl` will also roll back, but the S3 puts have already committed at that point — the three S3 objects exist but the DB row doesn't point at them yet. A retry re-runs all three puts (overwriting the existing keys) and then updates the DB. No cleanup needed because the S3 put order is deterministic and overwrite-on-put is the normal S3 semantics.

---

## 11. Controller wiring

### 11.1 `UserController` extensions

```java
package com.slparcelauctions.backend.user;

// imports (existing + additions):
// - jakarta.validation.Valid
// - org.springframework.http.HttpHeaders
// - org.springframework.http.MediaType
// - org.springframework.http.ResponseEntity
// - org.springframework.web.bind.annotation.RequestParam
// - org.springframework.web.multipart.MultipartFile
// - com.slparcelauctions.backend.storage.StoredObject

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final AvatarService avatarService;

    // -- existing endpoints (POST /, GET /{id}, GET /me) unchanged --

    /**
     * Replaces the previous notYetImplemented stub. Updates the caller's display name
     * and/or bio. Validation errors → 400 via GlobalExceptionHandler.handleValidation.
     * Unknown fields → 400 via UserExceptionHandler.handleUnknownField.
     */
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

    /**
     * STILL 501. Account deletion has GDPR / soft-delete / cascading-data implications
     * that belong in a dedicated sub-spec (future Epic 02 or Epic 07 task).
     */
    @DeleteMapping("/me")
    public ResponseEntity<ProblemDetail> deleteCurrentUser() {
        return notYetImplemented();
    }

    // -- existing notYetImplemented() helper unchanged --
}
```

### 11.2 `UpdateUserRequest` hardened

```java
package com.slparcelauctions.backend.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PUT /api/v1/users/me}. Both fields are optional — null means
 * "do not touch this column." An empty-string {@code bio} explicitly clears it.
 *
 * <p><strong>{@code ignoreUnknown = false} is load-bearing.</strong> It rejects any
 * extra field a client tries to sneak in ({@code email}, {@code role}, {@code verified},
 * etc), guarding against privilege escalation via field injection. Do not remove this
 * annotation. The security canary test
 * {@code UserControllerUpdateMeSliceTest.rejectsUnknownFields} enforces this rule.
 *
 * <p><strong>{@code @Size(min=1)} null-passthrough semantics:</strong> Jakarta Bean
 * Validation's {@code @Size} does not fire when the value is null. So {@code
 * {"displayName": null}} passes (service skips the column), while
 * {@code {"displayName": ""}} fails (empty string has {@code size() == 0}, below min).
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record UpdateUserRequest(
        @Size(min = 1, max = 50, message = "displayName must be 1-50 characters") String displayName,
        @Size(max = 500, message = "bio must be at most 500 characters") String bio
) {}
```

### 11.3 `SecurityConfig` matcher additions

Three new matchers inserted BEFORE the existing `/api/v1/**` authenticated catch-all. Order matters: more-specific paths must precede less-specific (FOOTGUNS §B.5).

Existing `GET /api/v1/users/{id}` permit matcher must stay BELOW the new `GET /api/v1/users/{id}/avatar/{size}` matcher, because Spring's path-variable matching can otherwise resolve `/users/1/avatar/128` against `/users/{id}` (where `{id}` matches `"1"`) depending on the pattern parser. The more-specific pattern comes first.

```java
// Inserted after /ws/** and before /api/v1/** authenticated catch-all:

// GET /api/v1/users/{id}/avatar/{size} — public avatar proxy, no JWT required.
// MUST come before the /users/{id} public matcher and the /api/v1/** catch-all.
.requestMatchers(HttpMethod.GET, "/api/v1/users/{id}/avatar/{size}").permitAll()
// POST /api/v1/users/me/avatar — authenticated multipart upload.
.requestMatchers(HttpMethod.POST, "/api/v1/users/me/avatar").authenticated()
// PUT /api/v1/users/me — authenticated update. The /api/v1/** catch-all would
// already authenticate this; the explicit matcher is for grep-ability.
.requestMatchers(HttpMethod.PUT, "/api/v1/users/me").authenticated()
```

---

## 12. `docker-compose.yml` changes

Three edits, all in the same commit as the `pom.xml` dependency additions (Task 1).

### 12.1 New `minio` service

```yaml
  minio:
    image: minio/minio:latest
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER:-slpa-dev-key}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD:-slpa-dev-secret}
    ports:
      - "${MINIO_S3_PORT:-9000}:9000"
      - "${MINIO_CONSOLE_PORT:-9001}:9001"
    volumes:
      - minio-data:/data
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:9000/minio/health/live || exit 1"]
      interval: 5s
      timeout: 5s
      retries: 10
    networks:
      - slpa-net
```

### 12.2 Backend service edits

Add `minio` to `depends_on`:
```yaml
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
      minio:
        condition: service_healthy
```

Add new env var:
```yaml
    environment:
      # ... existing entries ...
      SLPA_STORAGE_ENDPOINT: http://minio:9000
      MINIO_ROOT_USER: ${MINIO_ROOT_USER:-slpa-dev-key}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD:-slpa-dev-secret}
```

**Fix the stale healthcheck on line 69:**
```yaml
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:8080/api/v1/health || exit 1"]
      # ... interval/timeout/retries/start_period unchanged ...
```

The change from `/api/health` to `/api/v1/health` is a throw-in fix for the Epic 02 sub-spec 1 rename sweep. Documented in the commit message and FOOTGUNS F.28 (see §17).

### 12.3 New volume

```yaml
volumes:
  # ... existing volumes ...
  minio-data:
```

---

## 13. Error handling and exception mapping

### 13.1 Exception classes (new)

```
storage/exception/
├── ObjectNotFoundException.java      (see §8.3)
└── ObjectStorageException.java       (see §8.3)

user/exception/
├── UnsupportedImageFormatException.java
├── AvatarTooLargeException.java
└── InvalidAvatarSizeException.java
```

`UnsupportedImageFormatException`:
```java
package com.slparcelauctions.backend.user.exception;

public class UnsupportedImageFormatException extends RuntimeException {
    public UnsupportedImageFormatException(String message) { super(message); }
    public UnsupportedImageFormatException(String message, Throwable cause) { super(message, cause); }
}
```

`AvatarTooLargeException`:
```java
package com.slparcelauctions.backend.user.exception;

import lombok.Getter;

@Getter
public class AvatarTooLargeException extends RuntimeException {
    private final long actualBytes;
    private final long maxBytes;

    public AvatarTooLargeException(long actualBytes, long maxBytes) {
        super("Avatar too large: " + actualBytes + " bytes exceeds limit of " + maxBytes);
        this.actualBytes = actualBytes;
        this.maxBytes = maxBytes;
    }
}
```

`InvalidAvatarSizeException`:
```java
package com.slparcelauctions.backend.user.exception;

import lombok.Getter;

@Getter
public class InvalidAvatarSizeException extends RuntimeException {
    private final int requestedSize;

    public InvalidAvatarSizeException(int requestedSize) {
        super("Invalid avatar size: " + requestedSize + ". Must be 64, 128, or 256.");
        this.requestedSize = requestedSize;
    }
}
```

### 13.2 `UserExceptionHandler` (new, package-scoped)

```java
package com.slparcelauctions.backend.user;

import java.net.URI;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.slparcelauctions.backend.user.exception.AvatarTooLargeException;
import com.slparcelauctions.backend.user.exception.InvalidAvatarSizeException;
import com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Slice-scoped exception handler for the {@code user/} package. Uses the Epic 02 sub-spec 1
 * convention: {@code @Order(Ordered.LOWEST_PRECEDENCE - 100)} so it wins over
 * {@code GlobalExceptionHandler} but can stack predictably with other slice handlers.
 *
 * <p>Note: {@code MaxUploadSizeExceededException} is NOT handled here. It is thrown by
 * Spring's multipart resolver before the request reaches any {@code @RestController},
 * so package-scoped advice never sees it. That handler lives in
 * {@link com.slparcelauctions.backend.common.exception.GlobalExceptionHandler} — see FOOTGUNS §F.28.
 */
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.user")
@Order(Ordered.LOWEST_PRECEDENCE - 100)
@Slf4j
public class UserExceptionHandler {

    @ExceptionHandler(AvatarTooLargeException.class)
    public ProblemDetail handleAvatarTooLarge(AvatarTooLargeException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.PAYLOAD_TOO_LARGE, "Avatar must be 2MB or less.");
        pd.setType(URI.create("https://slpa.example/problems/user/upload-too-large"));
        pd.setTitle("Upload too large");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "USER_UPLOAD_TOO_LARGE");
        pd.setProperty("maxBytes", e.getMaxBytes());
        return pd;
    }

    @ExceptionHandler(UnsupportedImageFormatException.class)
    public ProblemDetail handleUnsupportedImageFormat(
            UnsupportedImageFormatException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Upload must be a JPEG, PNG, or WebP image.");
        pd.setType(URI.create("https://slpa.example/problems/user/unsupported-image-format"));
        pd.setTitle("Unsupported image format");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "USER_UNSUPPORTED_IMAGE_FORMAT");
        return pd;
    }

    @ExceptionHandler(InvalidAvatarSizeException.class)
    public ProblemDetail handleInvalidAvatarSize(
            InvalidAvatarSizeException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Avatar size must be 64, 128, or 256.");
        pd.setType(URI.create("https://slpa.example/problems/user/invalid-avatar-size"));
        pd.setTitle("Invalid avatar size");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "USER_INVALID_AVATAR_SIZE");
        pd.setProperty("requestedSize", e.getRequestedSize());
        return pd;
    }

    @ExceptionHandler(UnrecognizedPropertyException.class)
    public ProblemDetail handleUnknownField(
            UnrecognizedPropertyException e, HttpServletRequest req) {
        log.warn("UpdateUserRequest rejected unknown field: {}", e.getPropertyName());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Unknown field in request body: '" + e.getPropertyName() + "'.");
        pd.setType(URI.create("https://slpa.example/problems/user/unknown-field"));
        pd.setTitle("Unknown field");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "USER_UNKNOWN_FIELD");
        pd.setProperty("field", e.getPropertyName());
        return pd;
    }
}
```

### 13.3 `GlobalExceptionHandler` extension

Add one new `@ExceptionHandler` method to the existing `com.slparcelauctions.backend.common.exception.GlobalExceptionHandler`:

```java
@ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
public ProblemDetail handleMaxUploadSize(
        org.springframework.web.multipart.MaxUploadSizeExceededException e,
        HttpServletRequest req) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.PAYLOAD_TOO_LARGE, "Avatar must be 2MB or less.");
    pd.setType(URI.create("https://slpa.example/problems/user/upload-too-large"));
    pd.setTitle("Upload too large");
    pd.setInstance(URI.create(req.getRequestURI()));
    pd.setProperty("code", "USER_UPLOAD_TOO_LARGE");
    return pd;
}
```

Same URI and title as the `AvatarTooLargeException` handler in `UserExceptionHandler` so clients cannot tell which layer caught the oversized upload. The `maxBytes` property is only set on the service-layer path (the Spring-layer path doesn't know the configured limit), which is acceptable asymmetry — the `type` URI is the authoritative discriminator.

### 13.4 Full exception mapping table

| Exception | Thrown from | HTTP | `title` | `type` URI | Handler location | Log level |
|---|---|---|---|---|---|---|
| `MaxUploadSizeExceededException` (Spring) | multipart resolver, pre-controller | 413 | "Upload too large" | `.../user/upload-too-large` | `GlobalExceptionHandler` | INFO |
| `AvatarTooLargeException` | `AvatarService.upload` defensive check | 413 | "Upload too large" | `.../user/upload-too-large` | `UserExceptionHandler` | INFO |
| `UnsupportedImageFormatException` | `AvatarImageProcessor.process` | 400 | "Unsupported image format" | `.../user/unsupported-image-format` | `UserExceptionHandler` | INFO |
| `InvalidAvatarSizeException` | `AvatarService.fetch` | 400 | "Invalid avatar size" | `.../user/invalid-avatar-size` | `UserExceptionHandler` | INFO |
| `UnrecognizedPropertyException` (Jackson) | `@JsonIgnoreProperties(ignoreUnknown = false)` trigger | 400 | "Unknown field" | `.../user/unknown-field` | `UserExceptionHandler` | **WARN** |
| `MethodArgumentNotValidException` (Spring) | `@Valid @RequestBody` failure | 400 | "Validation failed" | `.../validation` | existing `GlobalExceptionHandler` | INFO |
| `UserNotFoundException` | `AvatarService.fetch` / `UserService` load | 404 | existing | existing | existing user handler | INFO |
| `ObjectNotFoundException` | `S3ObjectStorageService.get` on S3 404 | N/A | N/A | N/A | **caught inline in `AvatarService.fetch`** → placeholder response | ERROR (orphan) |
| `ObjectStorageException` / generic `SdkException` | `S3ObjectStorageService` | 500 | "Internal server error" | `.../internal-server-error` | existing `GlobalExceptionHandler.handleUnexpected` | ERROR |

---

## 14. Security considerations

### 14.1 Public endpoints

- `GET /api/v1/users/{id}/avatar/{size}` is `permitAll()` by design — avatars are intentionally public (matches the existing `GET /api/v1/users/{id}` public profile rule). The attack surface is "someone iterates user IDs and downloads every avatar." Mitigation: user IDs are sequential but avatars are user-uploaded content the uploader already knows is visible. Not sensitive.

### 14.2 Privilege escalation via field injection

- `PUT /me` rejects unknown fields via `@JsonIgnoreProperties(ignoreUnknown = false)`. Without this, a client could send `{"email": "hacker@example.com", "role": "admin"}` and hope Jackson + Spring silently ignores the unknown fields and the DB stays sane. The slice test `UserControllerUpdateMeSliceTest.rejectsUnknownFields` exercises this path with a request body containing an `email` field and asserts 400.

### 14.3 Upload size exhaustion

- Spring multipart config enforces `max-file-size: 2MB` and `max-request-size: 2MB` as hard cuts at the request parser. Anything larger is rejected before a single byte reaches the service code. The defensive controller-level re-check (`file.getSize() > MAX_UPLOAD_BYTES`) is belt-and-suspenders — it fires when the parser config is somehow bypassed (misconfiguration, future parser replacement).

### 14.4 Format sniffing vs client-supplied Content-Type

- `AvatarImageProcessor` uses `ImageIO.getImageReaders(ImageInputStream)` against the raw bytes. The multipart field's `Content-Type` header is not trusted. An attacker posting a `.exe` with `Content-Type: image/png` is rejected because no `ImageReader` matches the actual bytes.

### 14.5 S3 bucket exposure

- Bucket is private. No public-read policy. All reads go through the backend proxy, which applies `Cache-Control` headers but does not add any auth check (per design — avatars are public). A future "private avatars for banned users" feature would add an auth check at the proxy layer.

### 14.6 Credentials

- **Dev:** Static access/secret keys from `application-dev.yml`, matching the `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` env vars on the MinIO container. Both are dev-only. Docker Compose uses the same env var names for the backend and MinIO services so they stay in sync.
- **Prod:** No static credentials in YAML. `DefaultCredentialsProvider` chain discovers from env / IAM instance profile / web-identity token / etc. `StorageStartupValidator` fails fast if credentials can't be resolved (the `headBucket` call will throw a credential exception).

### 14.7 Log hygiene

- `UserExceptionHandler.handleUnknownField` logs the rejected field name at WARN. This is attacker-controlled input — strings up to 255 chars, no control characters (Jackson enforces), safe to log unescaped. Other handlers log at INFO or not at all.

---

## 15. Testing strategy

Three levels per CONVENTIONS §Backend Testing + Epic 02 sub-spec 1 precedent. Every test class follows the naming and annotation patterns established by `VerificationCodeServiceTest`, `SlVerificationControllerSliceTest`, and `SlVerificationFlowIntegrationTest`.

### 15.1 Unit tests

**`AvatarImageProcessorTest`** (8 cases, pure byte[] in/out, no Spring context)

- `process_validPng_producesThreeSizes` — golden 512×512 PNG fixture, assert the output map has 3 entries at sizes {64, 128, 256}, each decodable as a 64×64 / 128×128 / 256×256 PNG.
- `process_validJpeg_producesThreeSizes` — golden JPEG fixture.
- `process_validWebp_producesThreeSizes` — golden WebP fixture (proves `webp-imageio-sejda` is SPI-registered).
- `process_jpegWithExifRotation_outputIsCorrectlyOriented` — golden JPEG fixture with EXIF orientation flag "rotate 90° CW"; assert the output's center-row pixel is where the fixture's portrait-mode subject lives.
- `process_nonSquareInput_centerCrops` — golden 800×400 PNG with a known color band at horizontal center; assert the output's center pixel matches the expected color.
- `process_invalidBytes_throwsUnsupportedImageFormat` — `new byte[]{0x00, 0x01, 0x02}`.
- `process_unsupportedFormat_throwsUnsupportedImageFormat` — valid BMP (ImageIO reads BMP natively, so it passes sniff but fails the ALLOWED_FORMATS check).
- `process_truncatedPng_throwsUnsupportedImageFormat` — first 20 bytes of a valid PNG.

Golden fixtures live at `backend/src/test/resources/fixtures/avatar-*.{png,jpg,webp,bmp}`. Generated or sourced. Total size < 500KB.

**`AvatarServiceTest`** (9 cases, Mockito)

- `upload_happyPath_putsThreeObjectsAndUpdatesUser` — `ArgumentCaptor<PutObjectRequest>` verifies all 3 S3 puts with correct keys (`avatars/{id}/64.png`, `128.png`, `256.png`) and content type `image/png`. Verifies `user.profilePicUrl == "/api/v1/users/{id}/avatar/256"`.
- `upload_oversizedFile_throwsAvatarTooLarge` — file.getSize() > 2MB; verifies processor and storage are never called.
- `upload_unsupportedFormat_propagatesFromProcessor` — processor throws; verifies storage is never called and DB row is not touched.
- `upload_firstPutFails_doesNotUpdateUserRow` — storage.put throws on first call; verifies the user row's `profilePicUrl` was never set.
- `fetch_userDoesNotExist_throwsUserNotFound` — repository returns empty.
- `fetch_userHasNoAvatar_returnsPlaceholder` — `profilePicUrl == null`; verifies storage.get is never called and the returned bytes match the classpath placeholder for the requested size.
- `fetch_userHasAvatar_returnsProxiedBytes` — storage.get returns stored bytes, service passes them through unchanged.
- `fetch_userHasAvatarButS3Returns404_logsErrorAndReturnsPlaceholder` — storage.get throws `ObjectNotFoundException`; verifies an ERROR log line + placeholder bytes returned.
- `fetch_invalidSize_throwsInvalidAvatarSize` — `size = 99` throws, no repository call.

**`S3ObjectStorageServiceTest`** (~6 cases, Mockito)

- `put_callsS3WithCorrectBucketAndKey` — `ArgumentCaptor<PutObjectRequest>` verifies all fields.
- `get_happyPath_returnsStoredObject` — mocked `ResponseBytes` returned, assertions on bytes + contentType + contentLength.
- `get_noSuchKey_throwsObjectNotFound` — mocked `NoSuchKeyException` thrown; service wraps in `ObjectNotFoundException`.
- `delete_callsS3WithKey`.
- `exists_returnsTrueOn200`.
- `exists_returnsFalseOnNoSuchKey`.
- `deletePrefix_paginatesAcrossMultipleBatches` — mock returns `isTruncated=true` on first `listObjectsV2` call + 1500 keys total across 2 batches; verifies 2 list calls and 2 deleteObjects calls totaling 1500 keys.

### 15.2 Slice tests

**`UserControllerUpdateMeSliceTest`** (`@WebMvcTest(UserController.class)`, 7 cases)

- `put_me_happyPath_returns200` — `@WithMockUser`, body `{"displayName":"Alice"}`, service mock returns updated user, asserts 200 + service called with expected args.
- `put_me_displayNameTooLong_returns400` — 51-char displayName, asserts 400 + `errors` property lists `displayName` field.
- `put_me_bioTooLong_returns400` — 501-char bio, asserts 400 + `errors` property lists `bio` field.
- `put_me_displayNameEmpty_returns400` — empty string fails `@Size(min=1)`.
- `put_me_nullFields_skipsUpdate` — body `{"displayName": null, "bio": null}`, asserts 200, service called with null fields (no NPE).
- **`put_me_rejectsUnknownFields_returns400`** — body `{"displayName":"Alice","email":"hacker@example.com","role":"admin"}` → 400 with `type: .../user/unknown-field`, `field` property = `"email"` (or `"role"` — Jackson returns the first unknown field it encounters; test accepts either). **Security canary; cannot be removed.**
- `put_me_unauthenticated_returns401`.

**`UserControllerAvatarSliceTest`** (`@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("dev") @Transactional`, 8 cases)

Uses full Spring context because `@WebMvcTest` does not wire Spring Security's multipart handling or the full filter chain. This matches the `SlVerificationControllerSliceTest` precedent from Epic 02 sub-spec 1 §13.2.

- `get_avatar_publicEndpointNoAuth_returns200WithPlaceholder` — new user (via `/api/v1/auth/register`), no avatar uploaded, `GET /avatar/128` returns 200 + `Content-Type: image/png` + `Cache-Control: public, max-age=86400, immutable` + non-empty body. **Also asserts `s3Client.headBucket(slpa-uploads)` succeeds** — proves `StorageStartupValidator` ran the bucket auto-create path.
- `get_avatar_invalidSize_returns400` — `size = 99`, asserts 400 + `type: .../user/invalid-avatar-size`.
- `get_avatar_nonexistentUser_returns404`.
- `get_avatar_allThreeSizesSucceed` — parameterized on {64, 128, 256}.
- `post_avatar_unauthenticated_returns401`.
- `post_avatar_oversizedMultipart_returns413` — upload a 3MB file; asserts 413 + `type: .../user/upload-too-large`. **Exercises `GlobalExceptionHandler.handleMaxUploadSize`** (the Spring multipart resolver path).
- `post_avatar_unsupportedFormat_returns400` — upload a BMP fixture; asserts 400 + `type: .../user/unsupported-image-format`.
- `post_avatar_missingFileParam_returns400` — no `file` multipart part.

### 15.3 Integration tests

**`AvatarUploadFlowIntegrationTest`** (`@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("dev") @TestPropertySource(properties = "auth.cleanup.enabled=false") @Transactional`, 3 cases)

Each test creates a user, uploads via the real backend, reads back via the real backend, and cleans up S3 state in `@AfterEach` by calling `ObjectStorageService.deletePrefix("avatars/{userId}/")`.

- `fullFlow_registerUploadFetchReadBack` — register → login → upload a golden 512×512 PNG → response has `profilePicUrl = "/api/v1/users/{id}/avatar/256"` → `GET /users/{id}/avatar/{64|128|256}` three times → each response is a 200 PNG of the expected dimensions.
- `fullFlow_reuploadOverwritesPriorObject` — upload image A, fetch avatar/256, assert bytes match A's resized output. Upload image B, fetch avatar/256, assert bytes match B's resized output (and differ from A).
- `fullFlow_uploadedUserHasProfilePicUrlInGetMe` — upload, then `GET /me` with the same bearer token, assert the JSON response's `profilePicUrl` matches the proxy path.

**Not in integration test scope:** The 413/400/401 error paths are covered by `UserControllerAvatarSliceTest` above. Integration tests focus on real-MinIO round-trips that slice tests can't exercise.

**`UpdateUserFlowIntegrationTest`** (1-2 cases)

- `fullFlow_registerUpdateMeReadBack` — register → `PUT /me` with new displayName + bio → response reflects the update → `GET /me` returns the same values. Uses the existing integration-test pattern; may fit inside `UserIntegrationTest` if it exists, or ships as its own class.

### 15.4 `/me` response alignment test

Task 5 includes an inspection step: read `UserResponse.java` and cross-reference against the task doc's private-profile field list:

- Public fields (task doc): display name, bio, profile pic URL, verified status, SL avatar name, account age, seller/buyer ratings, completion stats
- Private fields (task doc, in addition to public): email, notification prefs, verification details

If `UserResponse` is missing fields from the private list (likely it has most but maybe not `notifyEmail` / `notifySlIm` / `emailVerified` / `slPayinfo`), Task 5 adds them to the record, then adds an assertion to `UpdateUserFlowIntegrationTest.getMe_returnsAllPrivateFields` that confirms the response shape.

### 15.5 Coverage target

Existing `verify-coverage.sh` (or the backend coverage gate — re-confirm during Task 1) must stay green at the established threshold. The new classes add ~30-35 tests bringing the backend suite from 141 to ~175.

### 15.6 What NOT to test

- **The `StorageStartupValidator` create-bucket branch in isolation.** Per Checkpoint 4 pushback: the branch is implicitly exercised by every MinIO-touching integration test. A dedicated test would spin up a second Spring context and is not worth the time/fragility cost. A single `headBucket` assertion inside `UserControllerAvatarSliceTest.get_avatar_publicEndpointNoAuth_returns200WithPlaceholder` is the explicit canary.
- **The `ResourceLoader` classpath placeholder lookup in isolation.** Covered by the mock-free `fetch_userHasNoAvatar_returnsPlaceholder` unit test (which uses a real `ClassPathResource` via Spring's `DefaultResourceLoader`).
- **The `DefaultCredentialsProvider` prod-profile branch.** Booting a prod-profile context in tests was established as a dead end in Epic 02 sub-spec 1 (F.25). If someone wants to prove `hasStaticCredentials()` returns false in prod, a unit test on `StorageConfigProperties` itself suffices.

---

## 16. Task breakdown

Seven tasks inside sub-spec 2a, in strict execution order.

**Task 1 — Dependencies + docker-compose MinIO + healthcheck fix** (~45 min)

1. Add `software.amazon.awssdk:s3`, `net.coobird:thumbnailator`, `org.sejda.imageio:webp-imageio` to `backend/pom.xml` (pick latest stable 2.x for the SDK at implementation time).
2. Add `minio` service to `docker-compose.yml` with healthcheck, volume, and network entries.
3. Add `minio-data` to the `volumes:` block.
4. Backend service gets `depends_on: minio: service_healthy`, new env var `SLPA_STORAGE_ENDPOINT: http://minio:9000`, and pass-through `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD`.
5. Fix the stale backend healthcheck on line 69 from `/api/health` to `/api/v1/health` (throw-in).
6. `mvn compile` passes with the new deps.
7. Manual smoke: `docker compose up`, `docker compose ps` shows all 5 services healthy, `curl http://localhost:9000/minio/health/live` returns 200, backend `/api/v1/health` returns 200.
8. Commit: `chore(deps): add AWS SDK v2 S3, Thumbnailator, webp-imageio for avatar upload`.

**Task 2 — `storage/` slice** (~2 hours)

1. `StorageConfigProperties` record.
2. `application.yml` / `application-dev.yml` / `application-prod.yml` `slpa.storage` blocks + `spring.servlet.multipart` config.
3. `S3ClientConfig` with profile-aware wiring.
4. `StorageStartupValidator` with `@EventListener(ApplicationReadyEvent.class)`.
5. `ObjectStorageService` interface + `StoredObject` record.
6. `S3ObjectStorageService` impl with pagination-aware `deletePrefix` and memory-bounded warning javadoc on `get`.
7. `ObjectNotFoundException`, `ObjectStorageException`.
8. `S3ObjectStorageServiceTest` with pagination test.
9. Commit: `feat(storage): add S3-backed object storage with profile-aware client wiring`.

**Task 3 — `AvatarImageProcessor`** (~1.5 hours)

1. `AvatarImageProcessor` component with ImageIO sniffing + Thumbnailator pipeline.
2. `UnsupportedImageFormatException` class.
3. Create `backend/src/test/resources/fixtures/` directory with 6 golden files: `avatar-valid.png`, `avatar-valid.jpg`, `avatar-valid.webp`, `avatar-rotated.jpg` (EXIF orientation = 6), `avatar-wide.png` (800×400 with center color band), `avatar-invalid.bmp`.
4. `AvatarImageProcessorTest` — 8 cases.
5. Commit: `feat(user): add AvatarImageProcessor with ImageIO sniffing and Thumbnailator resize pipeline`.

**Task 4a — PUT /me (profile edit)** (~1.5 hours)

1. Harden `UpdateUserRequest` with `@JsonIgnoreProperties(ignoreUnknown = false)`, `@Size(min=1, max=50)` on `displayName`, `@Size(max=500)` on `bio`, plus doc comments.
2. Replace `UserController.updateCurrentUser` stub with the real implementation.
3. Create `UserExceptionHandler` with the `@ExceptionHandler(UnrecognizedPropertyException.class)` method. (Other handlers added in Task 4b.)
4. `UserControllerUpdateMeSliceTest` with 7 cases including the `rejectsUnknownFields` security canary.
5. Update `UpdateUserFlowIntegrationTest` (or extend existing user integration test) with the full register → update → read-back round trip.
6. `./mvnw test` green.
7. Commit: `feat(user): wire PUT /me with hardened validation against unknown fields`.

**Task 4b — Avatar upload + serve** (~2.5 hours)

1. Create the three placeholder PNGs at `backend/src/main/resources/static/placeholders/avatar-{64,128,256}.png`. Simple silhouette, 1-2KB each. Generated or sourced.
2. `AvatarTooLargeException`, `InvalidAvatarSizeException` classes.
3. `AvatarService` with single `@Transactional` on public `upload` (wraps validation + process + S3 puts + DB update; see spec §10 transaction boundary note) + `fetch` method with placeholder fallback.
4. Add `AvatarService` autowire to `UserController`; implement `POST /me/avatar` and `GET /{id}/avatar/{size}`.
5. Extend `UserExceptionHandler` with handlers for `AvatarTooLargeException`, `UnsupportedImageFormatException`, `InvalidAvatarSizeException`.
6. Add `MaxUploadSizeExceededException` handler to `GlobalExceptionHandler` with matching 413 + URI shape.
7. `SecurityConfig` matcher additions: `GET /api/v1/users/{id}/avatar/{size}` permitAll, `POST /api/v1/users/me/avatar` authenticated, `PUT /api/v1/users/me` explicit authenticated entry.
8. `AvatarServiceTest` with 9 cases.
9. `UserControllerAvatarSliceTest` with 8 cases using `@SpringBootTest`.
10. `./mvnw test` green.
11. Commit: `feat(user): add avatar upload and proxy serving with placeholder fallback`.

**Task 5 — Integration tests + `/me` response alignment** (~1.5 hours)

1. `AvatarUploadFlowIntegrationTest` with 3 round-trip tests against real dev MinIO.
2. `@AfterEach` cleanup via `ObjectStorageService.deletePrefix("avatars/{userId}/")` for users created during the test.
3. Start-of-test assertion: `s3Client.headBucket(slpa-uploads)` succeeds (proves startup validator ran).
4. Inspect `UserResponse` record vs task doc's private-profile field list; if missing fields, add them (and add `from(User)` conversions), update the Epic 01 tests if the shape change requires it.
5. Add `getMe_returnsAllPrivateFields` assertion to `UpdateUserFlowIntegrationTest`.
6. `./mvnw test` green, full suite.
7. Commit: `test(user): add avatar integration tests and align /me response shape`.

**Task 6 — Postman + README + FOOTGUNS + PR** (~1 hour)

1. Postman MCP:
   - Add to existing `Users/` folder: `Update current user` (PUT /me, body with `displayName` + `bio`, saved 200 + 400-validation + 400-unknown-field examples).
   - Add: `Upload avatar` (POST /me/avatar, multipart/form-data with `file` part — mark file as "source from local file" placeholder, saved 200 + 413 + 400-unsupported-format examples).
   - Add: `Get user avatar` (GET /users/{{userId}}/avatar/{{avatarSize}} — add new env var `avatarSize` with default `256`, saved 200 example showing binary response + 404 + 400 examples).
2. `README.md` sweep:
   - Mention the MinIO service in docker-compose.
   - Document the `/api/v1/users/me/avatar` + `/api/v1/users/{id}/avatar/{size}` endpoints.
   - Add one paragraph on avatar storage (S3 prod / MinIO dev, proxy serving, placeholder fallback).
   - Bump the backend test count (approx 141 → 175).
3. `docs/implementation/FOOTGUNS.md` gets three new entries:
   - **F.28** — `MaxUploadSizeExceededException` fires before the dispatcher, so slice-scoped `@RestControllerAdvice` advice never sees it. Handler must live in `GlobalExceptionHandler` (or another non-package-scoped advice). Referenced from the Task 02-03 review.
   - **F.29** — Spring's `@Transactional` uses AOP proxying, so **same-class method calls bypass the proxy** regardless of the target method's visibility (private, protected, or public). A pattern like `public void outer() { doDbStuff(); } @Transactional private void doDbStuff() {}` silently fails: `doDbStuff()` never runs inside a transaction. Three working options to narrow a transaction boundary: (a) extract the DB mutation to a separate bean and call through the injected reference, (b) self-inject via `@Autowired @Lazy` and call through the self-reference, (c) use `TransactionTemplate` programmatically. Or — if the extra machinery isn't worth it — accept the broader boundary. `AvatarService.upload` chose the last option; see spec §10 transaction boundary note. Referenced from the Epic 02 sub-spec 2a brainstorm self-review.
   - **F.30** — `@JsonIgnoreProperties(ignoreUnknown = false)` is a privilege-escalation guard that must have explicit test coverage. Without a test, a future refactor can silently remove it. Canary test: `UserControllerUpdateMeSliceTest.put_me_rejectsUnknownFields_returns400`. **Scoping note:** The `UnrecognizedPropertyException` handler currently lives in `UserExceptionHandler` (scoped to the `user/` package), because `UpdateUserRequest` is the only DTO in the codebase with `ignoreUnknown = false`. If a future slice (e.g., `auction/`, `parcel/`) adopts the same hardening, that slice's `UnrecognizedPropertyException` will fall through to `GlobalExceptionHandler` and get a generic 400 instead of the tailored "unknown field" response. Two fixes when that happens: (a) add a sibling `UnrecognizedPropertyException` handler to the new slice's `@RestControllerAdvice`, or (b) move the handler to `GlobalExceptionHandler` so every slice benefits. Option (b) is the cleaner long-term fix once more than one slice needs it.
4. Open PR into `dev` branch (NOT `main`) via `gh pr create --base dev`. PR body matches the Epic 02 sub-spec 1 template — summary, test plan with Postman env var setup instructions, done-definition checklist.
5. After PR opens, `git checkout dev` so the working tree leaves the integration branch.
6. Commit: `docs: README sweep and FOOTGUNS F.28-F.30 for Epic 02 sub-spec 2a`.

---

## 17. Done definition

Sub-spec 2a is done when all of the following are true on the `task/02-sub-2a-profile-api-avatar-upload` branch off `dev`:

- [ ] All 7 tasks committed in order per §16.
- [ ] `./mvnw test` passes (existing 141 + new ~30-35 = ~171-176 tests all green).
- [ ] `cd frontend && npm test -- --run` still passes (no frontend changes in 2a; smoke check only).
- [ ] Backend coverage gate passes at the existing threshold.
- [ ] `docker compose up` stands up all 5 services healthy (postgres, redis, backend, frontend, minio). Backend healthcheck passes against the renamed `/api/v1/health` endpoint.
- [ ] Postman manual smoke: register → login → Update current user → Upload avatar (select a local file) → Get user avatar (shows PNG) → Get user avatar for a no-avatar user (shows placeholder PNG).
- [ ] `README.md` swept with the new endpoints, MinIO service, avatar storage paragraph, updated test count.
- [ ] `docs/implementation/FOOTGUNS.md` has F.28, F.29, F.30 in the established tone.
- [ ] PR opened into `dev` (not `main`); no AI/tool attribution anywhere in commits or PR body.
- [ ] Local branch is `dev` after the PR opens (not the feature branch).

---

## 18. Open questions deferred to sub-spec 2b

None of these block sub-spec 2a, but they're the shape for the next brainstorm.

1. **Dashboard layout composition** — which stitch reference (the existing `user_dashboard` mockup is a bidding dashboard, not a verification dashboard), how to decompose the pre-verification flow (code generation + countdown + copy-to-clipboard) vs the post-verification flow (verified identity card + tabs + empty-state bids/listings), and whether both states are in one page component or two.
2. **UI primitives to build** — `Tabs` (reused by public profile too), `CountdownTimer` (reused by auction detail in Epic 04), `VerificationCodeDisplay` (large mono type + copy button), `ProfilePictureUploader` (file input + preview + upload). Locations: `components/ui/` or `components/user/`?
3. **Public profile shell at `/users/[id]`** — empty states, "new seller" badge threshold, star rating component, placeholder sections for reviews/listings.
4. **`<Avatar>` component** — always hits `/api/v1/users/{id}/avatar/{size}?v={user.updatedAt}`, no 404 handling needed (placeholder fallback happens server-side).
5. **Auth-aware route behavior** — dashboard at `/dashboard` needs `RequireAuth`; public profile at `/users/[id]` is unauthenticated; profile edit form lives inside the dashboard.
6. **Reputation data** — all the `avg_seller_rating` / `avg_buyer_rating` / `completed_sales` columns are zero-valued until Epic 06 reviews + Epic 04 auctions ship. Display strategy: show "No ratings yet" placeholder; no zero-star display.

---

## 19. Decisions log (from brainstorm)

Locked via Q&A on 2026-04-14:

- **Q1 scope** → B: sub-spec 2a = backend profile API only (task 02-03); sub-spec 2b = dashboard + public profile (tasks 02-04 + 02-05).
- **Q2 storage backend** → corrected from "local filesystem" to **S3 in prod, MinIO in dev** via AWS SDK v2 with endpoint override. Directive from user, not a multiple-choice pick.
- **Q3 serving strategy** → A: backend proxy. Bucket stays private, backend streams bytes through, `profile_pic_url` stores the relative proxy path.
- **Q4a image processing library** → Thumbnailator (EXIF-aware, fluent pipeline).
- **Q4b output format** → PNG for all three sizes (lossless, universal ImageIO support, file size negligible at avatar dimensions).
- **Q4c MIME validation** → ImageIO sniffing via `getImageReaders(ImageInputStream)` + `webp-imageio-sejda` for WebP reader SPI registration. Multipart `Content-Type` header is not trusted.
- **Q4d max upload size** → 2MB enforced at BOTH Spring multipart config (hard cut) AND controller-level defensive re-check. `MaxUploadSizeExceededException` and `AvatarTooLargeException` produce the same 413 `ProblemDetail` shape.
- **Q5a bucket naming** → single `slpa-uploads` bucket with per-domain prefixes (`avatars/`, `parcels/` later, etc).
- **Q5b object key schema** → `avatars/{userId}/{size}.png`.
- **Q5c bucket creation** → Java-side head-and-create on startup via `StorageStartupValidator` (`@EventListener(ApplicationReadyEvent.class)`). Prod profile fails fast if the bucket is missing; non-prod auto-creates.
- **Q5c pushback** → MinIO `depends_on` condition in compose must be `service_healthy`, not `service_started`, to avoid race with `StorageStartupValidator.check()`.
- **Q5d credentials** → dev profile uses `StaticCredentialsProvider` with keys from `application-dev.yml`; prod profile uses `DefaultCredentialsProvider` (env / IAM / web identity).
- **Q5e endpoint override + path style** → dev overrides endpoint via `SLPA_STORAGE_ENDPOINT` env var (defaults to `http://localhost:9000` for bare-JVM runs), forces `pathStyleAccess = true`. Prod omits both.
- **Q5f endpoint var shape** → matches existing `SPRING_DATASOURCE_URL` pattern: env var lives in docker-compose.yml's backend service env block, application-dev.yml provides the fallback.
- **Q6a test strategy** → mocks for unit tests (processor, service, storage service), shared dev MinIO for integration tests. `@AfterEach` cleans up with `deletePrefix("avatars/{userId}/")`. No Testcontainers, no dedicated start-up test (implicit coverage via upload tests' `headBucket` assertion).
- **Q6b validation rules** → `displayName` `@Size(min=1, max=50)`, `bio` `@Size(max=500)`, `@JsonIgnoreProperties(ignoreUnknown = false)` on the DTO.
- **Q6b pushback** → add explicit `rejectsUnknownFields` slice test to the test inventory; the security guard only works if it's verified.
- **Q6c stubs** → `PUT /me` filled in; `DELETE /me` stays 501 with updated TODO comment pointing at future GDPR sub-spec.
- **Q6d `/me` response** → inspect-and-align as a throw-in in Task 5 (may or may not add fields).
- **Checkpoint 1 pushback** → 404 for "user has no avatar" replaced with **placeholder image served from the same endpoint**. Different code path for "user does not exist" vs "user exists but no avatar": former returns 404, latter returns placeholder PNG bytes. Orphaned `profile_pic_url` (DB says yes, S3 says no) also returns placeholder plus an ERROR log.
- **Checkpoint 2 pushback** → `S3ObjectStorageService.get` gets a javadoc warning about avatar-sized use only. `deletePrefix` implements full pagination via `isTruncated` + continuation token. The narrow-transaction suggestion (move `@Transactional` off public `upload()` onto a private helper) was **walked back during spec self-review**: Spring AOP proxying doesn't support same-class `@Transactional` calls regardless of target visibility, so the "private helper" pattern would silently fail to open a transaction and JPA dirty checking would not flush the update. Options were separate bean, self-injection with `@Lazy`, or keep the single `@Transactional` on `upload()`. Chosen: **keep `@Transactional` on `upload()`** — the Checkpoint 2 pushback was framed as "not blocking," the Phase 1 scale argument holds, and adding plumbing for a non-blocking optimization isn't worth it. See spec §10 transaction boundary note.
- **Checkpoint 3 pushback** → `MaxUploadSizeExceededException` handler lives in `GlobalExceptionHandler`, not `UserExceptionHandler` (exception is thrown before the dispatcher, so package-scoped advice can never see it).
- **Checkpoint 4 pushbacks** → Task 4 split into 4a (PUT /me) and 4b (avatar); `StorageStartupValidatorIntegrationTest` dropped in favor of a single `headBucket` assertion in the upload integration test.
