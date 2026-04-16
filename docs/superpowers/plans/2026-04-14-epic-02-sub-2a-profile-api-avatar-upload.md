# Epic 02 Sub-spec 2a — Profile API + Avatar Upload Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the backend profile API + avatar upload flow end-to-end — authenticated users can edit their display name and bio via `PUT /api/v1/users/me`, upload an avatar via `POST /api/v1/users/me/avatar`, and any client can fetch a user's avatar at one of three sizes via `GET /api/v1/users/{id}/avatar/{size}`.

**Architecture:** Two vertical slices. `storage/` owns the S3 client (MinIO in dev via endpoint override, real AWS S3 in prod via `DefaultCredentialsProvider`), bucket startup validation, and an `ObjectStorageService` interface. `user/` is extended with `AvatarImageProcessor` (Thumbnailator + ImageIO sniffing), `AvatarService` (orchestrator), and new controller endpoints. `user/ → storage/`; never the reverse. Avatars are served through a backend proxy — the bucket stays private. Placeholder PNGs ship as classpath resources and are returned when a user has no avatar OR the S3 object is unexpectedly missing.

**Tech Stack:** Spring Boot 4, Java 26, AWS SDK v2 (S3), Thumbnailator 0.4.20, webp-imageio-sejda 0.1.6, MinIO (dev), JUnit 5 + Mockito + MockMvc, Postman (via MCP).

**Spec:** `docs/superpowers/specs/2026-04-14-epic-02-sub-2a-profile-api-avatar-upload.md`

**Branch:** `task/02-sub-2a-profile-api-avatar-upload` off `dev`. PRs target `dev`, not `main`. No AI/tool attribution in commits or PR body (project memory rule).

**Predecessor:** Epic 02 sub-spec 1 (verification backend) — already merged to `dev` and `main`. Establishes the `/api/v1` prefix, the `@Order(LOWEST_PRECEDENCE - 100)` slice-advice convention, the `@SpringBootTest @ActiveProfiles("dev") @Transactional` integration-test pattern, and FOOTGUNS F.22-F.27.

---

## File Structure

### Backend — new files

```
backend/src/main/java/com/slparcelauctions/backend/
├── storage/                                     [NEW PACKAGE]
│   ├── StorageConfigProperties.java             @ConfigurationProperties("slpa.storage") record
│   ├── S3ClientConfig.java                      @Configuration producing S3Client bean
│   ├── StorageStartupValidator.java             @EventListener(ApplicationReadyEvent.class)
│   ├── ObjectStorageService.java                interface: put, get, delete, deletePrefix, exists
│   ├── S3ObjectStorageService.java              @Service impl
│   ├── StoredObject.java                        record { byte[] bytes, String contentType, long contentLength }
│   └── exception/
│       ├── ObjectNotFoundException.java         thrown on S3 404
│       └── ObjectStorageException.java          wraps non-404 SdkException
└── user/   (existing slice — extended)
    ├── AvatarService.java                       [NEW] orchestrator
    ├── AvatarImageProcessor.java                [NEW] Thumbnailator + ImageIO sniffing
    ├── UserExceptionHandler.java                [NEW] @RestControllerAdvice(basePackages = "...user")
    └── exception/
        ├── UnsupportedImageFormatException.java [NEW]
        ├── AvatarTooLargeException.java         [NEW]
        └── InvalidAvatarSizeException.java      [NEW]
```

### Backend — modified files

```
backend/
├── pom.xml                                      add s3, thumbnailator, webp-imageio-sejda deps
├── src/main/resources/
│   ├── application.yml                          add slpa.storage base + spring multipart
│   ├── application-dev.yml                      add slpa.storage dev block (endpoint override + creds)
│   ├── application-prod.yml                     add slpa.storage prod block
│   └── static/placeholders/                     [NEW DIR]
│       ├── avatar-64.png                        [NEW BINARY RESOURCE]
│       ├── avatar-128.png                       [NEW BINARY RESOURCE]
│       └── avatar-256.png                       [NEW BINARY RESOURCE]
└── src/main/java/com/slparcelauctions/backend/
    ├── user/
    │   ├── UserController.java                  PUT /me filled in, POST /me/avatar, GET /{id}/avatar/{size}
    │   └── dto/
    │       ├── UpdateUserRequest.java           @JsonIgnoreProperties + tightened @Size
    │       └── UserResponse.java                add slAvatarName/slBornDate/slPayinfo/verifiedAt
    ├── common/exception/
    │   └── GlobalExceptionHandler.java          + handleMaxUploadSize
    └── config/
        └── SecurityConfig.java                  + 3 matchers for avatar + explicit PUT /me

docker-compose.yml                               + minio service, fix stale healthcheck
```

### Backend — new test files

```
backend/src/test/java/com/slparcelauctions/backend/
├── storage/
│   └── S3ObjectStorageServiceTest.java          unit, Mockito on S3Client
├── user/
│   ├── AvatarImageProcessorTest.java            unit, pure byte[] I/O
│   ├── AvatarServiceTest.java                   unit, Mockito
│   ├── UserControllerUpdateMeSliceTest.java     @WebMvcTest, 7 cases including security canary
│   ├── UserControllerAvatarSliceTest.java       @SpringBootTest slice (multipart aware)
│   ├── AvatarUploadFlowIntegrationTest.java     @SpringBootTest @ActiveProfiles("dev")
│   └── UpdateUserFlowIntegrationTest.java       @SpringBootTest @ActiveProfiles("dev")

backend/src/test/resources/fixtures/             [NEW DIR]
├── avatar-valid.png                             [NEW BINARY] 512×512 PNG
├── avatar-valid.jpg                             [NEW BINARY]
├── avatar-valid.webp                            [NEW BINARY]
├── avatar-rotated.jpg                           [NEW BINARY] EXIF orientation=6
├── avatar-wide.png                              [NEW BINARY] 800×400 with center color band
├── avatar-invalid.bmp                           [NEW BINARY] 512×512 BMP
├── avatar-truncated.png                         [NEW BINARY] first 20 bytes of a PNG
└── avatar-3mb.png                               [NEW BINARY] 3MB PNG for 413 test
```

### Docs

```
README.md                                        extend with MinIO + avatar endpoints + storage paragraph
docs/implementation/FOOTGUNS.md                  add F.28, F.29, F.30
```

---

## Preflight

- [ ] **Preflight 1: Confirm clean working tree on dev**

Run:
```bash
git status --short && git rev-parse --abbrev-ref HEAD
```

Expected: empty or only `.superpowers/` (gitignored), branch `dev`. If dirty, resolve before proceeding.

- [ ] **Preflight 2: Pull latest dev**

```bash
git pull --ff-only origin dev
```

Expected: `Already up to date.` or fast-forward.

- [ ] **Preflight 3: Create feature branch**

```bash
git checkout -b task/02-sub-2a-profile-api-avatar-upload
```

Expected: `Switched to a new branch`.

- [ ] **Preflight 4: Baseline — backend tests green**

```bash
cd backend && ./mvnw test 2>&1 | grep -E 'BUILD (SUCCESS|FAILURE)|Tests run:' | tail -5
```

Expected: `BUILD SUCCESS`, ~141 tests passing. Do not proceed on red baseline.

- [ ] **Preflight 5: Baseline — frontend tests green**

```bash
cd /c/Users/heath/Repos/Personal/slpa/frontend && npm test -- --run 2>&1 | tail -10
```

Expected: ~185 passing + 1 todo. Do not proceed on red baseline.

- [ ] **Preflight 6: Confirm dev containers are up (postgres + redis)**

```bash
docker ps --format '{{.Names}}' | grep -E 'slpa-postgres|slpa-redis|postgres|redis'
```

Expected: both containers listed. If not, start them:
```bash
cd /c/Users/heath/Repos/Personal/slpa && docker compose up -d postgres redis
```

- [ ] **Preflight 7: Confirm Postman SLPA workspace resolves**

Call `mcp__postman__getCollection` with `collectionId: 8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288` and confirm the response has `collection.info.name == "SLPA"`.

---

## Task 1: Dependencies + docker-compose MinIO + healthcheck fix

**Estimated time:** 45 minutes.
**Commits:** 1.

### Files

- Modify: `backend/pom.xml`
- Modify: `docker-compose.yml`

### Steps

- [ ] **Step 1.1: Add AWS SDK v2 S3, Thumbnailator, webp-imageio-sejda to `backend/pom.xml`**

Open `backend/pom.xml`. Find the closing `</dependencies>` tag around line 151. Insert these three dependencies just before it (after the last existing dependency on line 150):

```xml
        <dependency>
            <groupId>software.amazon.awssdk</groupId>
            <artifactId>s3</artifactId>
            <version>2.29.52</version>
        </dependency>
        <dependency>
            <groupId>net.coobird</groupId>
            <artifactId>thumbnailator</artifactId>
            <version>0.4.20</version>
        </dependency>
        <dependency>
            <groupId>org.sejda.imageio</groupId>
            <artifactId>webp-imageio</artifactId>
            <version>0.1.6</version>
        </dependency>
```

Note on the S3 SDK version: `2.29.52` was current as of early 2026-04. If a newer 2.x is available at implementation time, use it — the API surface we use (`S3Client`, `PutObjectRequest`, `GetObjectRequest`, `ListObjectsV2Request`, `HeadBucketRequest`, `CreateBucketRequest`, `StaticCredentialsProvider`, `DefaultCredentialsProvider`) has been stable across the 2.x line.

- [ ] **Step 1.2: Verify maven can resolve the new deps**

```bash
cd backend && ./mvnw dependency:resolve -q 2>&1 | tail -20
```

Expected: no errors. If Maven complains about the S3 SDK version, downgrade to `2.29.0` or pick another recent 2.x release and retry.

- [ ] **Step 1.3: Add MinIO service to `docker-compose.yml`**

Open `docker-compose.yml`. After the `redis` service (which ends around line 34 — `networks: - slpa-net`), insert a new `minio` service block. Your edit target is the section right before `backend:`.

Insert this block (with leading blank line for readability):

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

- [ ] **Step 1.4: Add `minio: condition: service_healthy` to backend `depends_on`**

In `docker-compose.yml`, find the `backend:` service's `depends_on:` block (around line 40-44). It currently has:

```yaml
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
```

Add the minio entry after redis:

```yaml
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
      minio:
        condition: service_healthy
```

- [ ] **Step 1.5: Add `SLPA_STORAGE_ENDPOINT` and MinIO env vars to backend service**

In `docker-compose.yml`, find the `backend:` service's `environment:` block (around line 45-52). Append three new lines after the existing `CORS_ALLOWED_ORIGIN` entry:

```yaml
      CORS_ALLOWED_ORIGIN: ${CORS_ALLOWED_ORIGIN:-http://localhost:3000}
      SLPA_STORAGE_ENDPOINT: http://minio:9000
      MINIO_ROOT_USER: ${MINIO_ROOT_USER:-slpa-dev-key}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD:-slpa-dev-secret}
```

- [ ] **Step 1.6: Fix stale backend healthcheck**

In `docker-compose.yml`, find the `backend:` service's `healthcheck:` block (around line 68-73). The current test line reads:

```yaml
      test: ["CMD-SHELL", "curl -fsS http://localhost:8080/api/health || exit 1"]
```

Change to:

```yaml
      test: ["CMD-SHELL", "curl -fsS http://localhost:8080/api/v1/health || exit 1"]
```

This is a throw-in fix for Epic 02 sub-spec 1's `/api/v1` rename sweep that missed this file. Commit it in the same Task 1 commit with a note in the message.

- [ ] **Step 1.7: Add `minio-data` volume declaration**

In `docker-compose.yml`, find the `volumes:` block at the bottom of the file (around line 97-102). Append `minio-data:` to the list:

```yaml
volumes:
  postgres-data:
  redis-data:
  maven-cache:
  frontend-node-modules:
  frontend-next-cache:
  minio-data:
```

- [ ] **Step 1.8: Compile backend to catch any pom issues early**

```bash
cd backend && ./mvnw compile -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`. No compilation errors (no new code yet, just dep resolution).

- [ ] **Step 1.9: Start the compose stack and confirm MinIO is healthy**

```bash
cd /c/Users/heath/Repos/Personal/slpa && docker compose up -d minio
```

Wait ~10 seconds, then:

```bash
docker compose ps minio
```

Expected: `minio` service status shows `healthy`.

- [ ] **Step 1.10: Smoke test MinIO health endpoint directly**

```bash
curl -fsS http://localhost:9000/minio/health/live && echo OK
```

Expected: `OK` printed (MinIO returns a 200 with empty body on the live endpoint).

- [ ] **Step 1.11: Verify existing backend still boots with the new dep list**

```bash
cd backend && ./mvnw test -q 2>&1 | grep -E 'BUILD (SUCCESS|FAILURE)|Tests run:' | tail -5
```

Expected: `BUILD SUCCESS`, ~141 tests passing. The new deps shouldn't affect existing tests — they're just sitting on the classpath.

- [ ] **Step 1.12: Commit Task 1**

```bash
cd /c/Users/heath/Repos/Personal/slpa
git add backend/pom.xml docker-compose.yml
git status --short
```

Expected: only `pom.xml` and `docker-compose.yml` staged (no untracked `.superpowers/` surprises).

```bash
git commit -m "chore(deps): add AWS SDK v2 S3, Thumbnailator, webp-imageio for avatar upload

Adds three dependencies needed for Epic 02 sub-spec 2a avatar flow:
- software.amazon.awssdk:s3 for the S3 / MinIO client
- net.coobird:thumbnailator for resize + center-crop pipeline
- org.sejda.imageio:webp-imageio for WebP input support via ImageIO SPI

Adds the minio service to docker-compose.yml on slpa-net with a
/minio/health/live healthcheck, and wires the backend service with
depends_on: minio: service_healthy + SLPA_STORAGE_ENDPOINT env var
matching the existing SPRING_DATASOURCE_URL pattern.

Throw-in fix: backend healthcheck on line 69 was still referencing
/api/health from before the Epic 02 sub-spec 1 /api/v1 rename sweep.
Now points at /api/v1/health."
```

- [ ] **Step 1.13: Push feature branch**

```bash
git push -u origin task/02-sub-2a-profile-api-avatar-upload
```

Expected: branch pushed with upstream tracking set.

---

## Task 2: `storage/` slice

**Estimated time:** 2 hours.
**Commits:** 1.

### Files

New files under `backend/src/main/java/com/slparcelauctions/backend/storage/` plus YAML updates.

### Steps

- [ ] **Step 2.1: Create `StorageConfigProperties`**

File: `backend/src/main/java/com/slparcelauctions/backend/storage/StorageConfigProperties.java`

```java
package com.slparcelauctions.backend.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for S3-compatible object storage (MinIO in dev, real AWS S3 in prod).
 * Bound to {@code slpa.storage.*}.
 *
 * <p>The compact canonicalizing constructor supplies safe defaults: {@code region}
 * defaults to {@code "us-east-1"} if null/blank, {@code bucket} is required.
 */
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

- [ ] **Step 2.2: Add `slpa.storage` block to `application.yml` (base)**

File: `backend/src/main/resources/application.yml`

Append at end of file:

```yaml

slpa:
  storage:
    bucket: slpa-uploads
    region: us-east-1
    path-style-access: false
```

Also append the multipart config under the existing `spring:` block. If `spring:` already exists, add the `servlet:` nested block. If not, create it. Insert at the appropriate place — before the `slpa:` block:

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 2MB
      max-request-size: 2MB
```

If `spring:` already has other nested keys, merge `servlet.multipart` under the existing `spring:` key instead of duplicating the top-level key. Read the current `application.yml` first and merge carefully.

- [ ] **Step 2.3: Add `slpa.storage` block to `application-dev.yml`**

File: `backend/src/main/resources/application-dev.yml`

Append at end of file:

```yaml

slpa:
  storage:
    endpoint-override: ${SLPA_STORAGE_ENDPOINT:http://localhost:9000}
    path-style-access: true
    access-key-id: ${MINIO_ROOT_USER:slpa-dev-key}
    secret-access-key: ${MINIO_ROOT_PASSWORD:slpa-dev-secret}
```

If `application-dev.yml` already has a top-level `slpa:` key (from Epic 02 sub-spec 1's `slpa.sl` config), merge the `storage:` key under the existing `slpa:` block. Read the file first and merge carefully.

- [ ] **Step 2.4: Add `slpa.storage` block to `application-prod.yml`**

File: `backend/src/main/resources/application-prod.yml`

Append at end:

```yaml

slpa:
  storage:
    bucket: ${SLPA_STORAGE_BUCKET}
    region: ${AWS_REGION:us-east-1}
```

Same merge rule as dev if a `slpa:` key already exists.

- [ ] **Step 2.5: Create `S3ClientConfig`**

File: `backend/src/main/java/com/slparcelauctions/backend/storage/S3ClientConfig.java`

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

- [ ] **Step 2.6: Create exception classes**

File: `backend/src/main/java/com/slparcelauctions/backend/storage/exception/ObjectNotFoundException.java`

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

File: `backend/src/main/java/com/slparcelauctions/backend/storage/exception/ObjectStorageException.java`

```java
package com.slparcelauctions.backend.storage.exception;

public class ObjectStorageException extends RuntimeException {
    public ObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 2.7: Create `StoredObject` record and `ObjectStorageService` interface**

File: `backend/src/main/java/com/slparcelauctions/backend/storage/StoredObject.java`

```java
package com.slparcelauctions.backend.storage;

public record StoredObject(byte[] bytes, String contentType, long contentLength) {}
```

File: `backend/src/main/java/com/slparcelauctions/backend/storage/ObjectStorageService.java`

```java
package com.slparcelauctions.backend.storage;

public interface ObjectStorageService {

    /** Puts a single object. Overwrites any existing object at the same key. */
    void put(String key, byte[] bytes, String contentType);

    /**
     * Fetches a single object's bytes. Throws {@link com.slparcelauctions.backend.storage.exception.ObjectNotFoundException}
     * on S3 404.
     */
    StoredObject get(String key);

    /** Deletes a single object. No-op if the key doesn't exist (S3 delete is idempotent). */
    void delete(String key);

    /**
     * Batch-deletes every object under the given key prefix. Paginates via
     * {@code isTruncated} + continuation token so >1000 objects are handled.
     */
    void deletePrefix(String keyPrefix);

    /** Returns true if the object exists at the given key. */
    boolean exists(String key);
}
```

- [ ] **Step 2.8: Write the failing `S3ObjectStorageServiceTest` — put happy path first**

File: `backend/src/test/java/com/slparcelauctions/backend/storage/S3ObjectStorageServiceTest.java`

```java
package com.slparcelauctions.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.slparcelauctions.backend.storage.exception.ObjectNotFoundException;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

class S3ObjectStorageServiceTest {

    private S3Client s3;
    private StorageConfigProperties props;
    private S3ObjectStorageService service;

    @BeforeEach
    void setup() {
        s3 = mock(S3Client.class);
        props = new StorageConfigProperties(
                "test-bucket", "us-east-1", null, false, null, null);
        service = new S3ObjectStorageService(s3, props);
    }

    @Test
    void put_callsS3WithCorrectBucketKeyContentType() {
        byte[] bytes = new byte[]{1, 2, 3};

        service.put("avatars/1/256.png", bytes, "image/png");

        ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(reqCaptor.capture(), any(RequestBody.class));
        PutObjectRequest captured = reqCaptor.getValue();
        assertThat(captured.bucket()).isEqualTo("test-bucket");
        assertThat(captured.key()).isEqualTo("avatars/1/256.png");
        assertThat(captured.contentType()).isEqualTo("image/png");
        assertThat(captured.contentLength()).isEqualTo(3L);
    }
}
```

- [ ] **Step 2.9: Run the test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=S3ObjectStorageServiceTest -q 2>&1 | tail -20
```

Expected: compile failure — `S3ObjectStorageService` doesn't exist yet.

- [ ] **Step 2.10: Create `S3ObjectStorageService`**

File: `backend/src/main/java/com/slparcelauctions/backend/storage/S3ObjectStorageService.java`

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
     * Loads the full object into memory as a {@code byte[]}. Appropriate for
     * avatar-sized PNGs (&lt;150KB each). <strong>Do NOT reuse this method for
     * larger objects</strong> (parcel photos, listing images, review photos)
     * without first refactoring to return a streaming
     * {@code ResponseInputStream<GetObjectResponse>} — a future caller that tries
     * to load a 5MB parcel photo via this method will blow the heap on a hot path.
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
            if (!Boolean.TRUE.equals(listed.isTruncated())) {
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

- [ ] **Step 2.11: Run test — expect green**

```bash
cd backend && ./mvnw test -Dtest=S3ObjectStorageServiceTest -q 2>&1 | tail -20
```

Expected: 1 test passing.

- [ ] **Step 2.12: Add remaining S3ObjectStorageServiceTest cases**

Append to `S3ObjectStorageServiceTest.java` (inside the class body, after `put_callsS3WithCorrectBucketKeyContentType`):

```java
    @Test
    void get_happyPath_returnsStoredObject() {
        byte[] bytes = new byte[]{10, 20, 30};
        GetObjectResponse response = GetObjectResponse.builder()
                .contentType("image/png")
                .contentLength(3L)
                .build();
        ResponseBytes<GetObjectResponse> responseBytes = ResponseBytes.fromByteArray(response, bytes);
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(responseBytes);

        StoredObject result = service.get("avatars/1/256.png");

        assertThat(result.bytes()).isEqualTo(bytes);
        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.contentLength()).isEqualTo(3L);
    }

    @Test
    void get_noSuchKey_throwsObjectNotFound() {
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("not found").build());

        assertThatThrownBy(() -> service.get("missing"))
                .isInstanceOf(ObjectNotFoundException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void delete_callsS3WithKey() {
        service.delete("avatars/1/256.png");

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo("test-bucket");
        assertThat(captor.getValue().key()).isEqualTo("avatars/1/256.png");
    }

    @Test
    void exists_returnsTrueWhenHeadSucceeds() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build());

        assertThat(service.exists("avatars/1/256.png")).isTrue();
    }

    @Test
    void exists_returnsFalseOnNoSuchKey() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("not found").build());

        assertThat(service.exists("missing")).isFalse();
    }

    @Test
    void deletePrefix_paginatesAcrossMultipleBatches() {
        // First call: 2 objects + isTruncated=true
        ListObjectsV2Response firstPage = ListObjectsV2Response.builder()
                .contents(
                        S3Object.builder().key("avatars/1/64.png").build(),
                        S3Object.builder().key("avatars/1/128.png").build())
                .isTruncated(true)
                .nextContinuationToken("TOKEN")
                .build();
        // Second call: 1 object + isTruncated=false
        ListObjectsV2Response secondPage = ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("avatars/1/256.png").build())
                .isTruncated(false)
                .build();
        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(firstPage, secondPage);

        service.deletePrefix("avatars/1/");

        verify(s3).listObjectsV2(any(ListObjectsV2Request.class));
        // Two delete calls (one per page), each with the page's objects
        ArgumentCaptor<DeleteObjectsRequest> deleteCaptor = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(s3, org.mockito.Mockito.times(2)).deleteObjects(deleteCaptor.capture());
        assertThat(deleteCaptor.getAllValues()).hasSize(2);
        assertThat(deleteCaptor.getAllValues().get(0).delete().objects()).hasSize(2);
        assertThat(deleteCaptor.getAllValues().get(1).delete().objects()).hasSize(1);
    }

    @Test
    void deletePrefix_emptyPrefix_makesNoDeleteCall() {
        ListObjectsV2Response emptyPage = ListObjectsV2Response.builder()
                .contents(java.util.Collections.emptyList())
                .isTruncated(false)
                .build();
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(emptyPage);

        service.deletePrefix("avatars/99/");

        verify(s3, org.mockito.Mockito.never()).deleteObjects(any(DeleteObjectsRequest.class));
    }
}
```

- [ ] **Step 2.13: Run full S3ObjectStorageServiceTest suite**

```bash
cd backend && ./mvnw test -Dtest=S3ObjectStorageServiceTest -q 2>&1 | tail -20
```

Expected: 7 tests passing.

- [ ] **Step 2.14: Create `StorageStartupValidator`**

File: `backend/src/main/java/com/slparcelauctions/backend/storage/StorageStartupValidator.java`

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
 * {@code docker compose up} on a fresh MinIO volume just works.
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

- [ ] **Step 2.15: Full backend test run + manual smoke**

```bash
cd backend && ./mvnw test -q 2>&1 | grep -E 'BUILD (SUCCESS|FAILURE)|Tests run:' | tail -5
```

Expected: `BUILD SUCCESS`, ~148 tests (141 baseline + 7 new).

Manual smoke: start the backend against the running MinIO and confirm the startup validator creates the bucket:

```bash
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev 2>&1 | grep -i 'storage bucket' &
# Wait ~15 seconds for Spring Boot startup
sleep 15
# Then kill the backend
kill %1 2>/dev/null
```

Expected: log output contains `Storage bucket 'slpa-uploads' missing (non-prod profile); creating...` followed by `Storage bucket 'slpa-uploads' created`. If the bucket already exists from a previous run, you'll see `Storage bucket 'slpa-uploads' reachable` instead — both are acceptable.

- [ ] **Step 2.16: Commit Task 2**

```bash
cd /c/Users/heath/Repos/Personal/slpa
git add backend/src/main/java/com/slparcelauctions/backend/storage/ \
        backend/src/main/resources/application.yml \
        backend/src/main/resources/application-dev.yml \
        backend/src/main/resources/application-prod.yml \
        backend/src/test/java/com/slparcelauctions/backend/storage/
git status --short
```

Expected: only `storage/` package files + 3 yaml files + the test file.

```bash
git commit -m "feat(storage): add S3-backed object storage with profile-aware client wiring

New storage/ vertical slice:
- StorageConfigProperties: @ConfigurationProperties(slpa.storage) record
  with defensive defaults and hasStaticCredentials/hasEndpointOverride helpers
- S3ClientConfig: produces S3Client with StaticCredentialsProvider in dev
  and DefaultCredentialsProvider in prod, applies endpoint override and
  forcePathStyle only when configured
- StorageStartupValidator: @EventListener(ApplicationReadyEvent) fails fast
  in prod if bucket is missing, auto-creates in non-prod so docker compose
  up on a fresh MinIO volume just works
- ObjectStorageService interface + StoredObject record + S3ObjectStorageService
  impl with full pagination support on deletePrefix and a javadoc warning on
  get() that it's avatar-sized-only

Configuration added to all three profiles. dev reads MINIO_ROOT_USER /
MINIO_ROOT_PASSWORD and SLPA_STORAGE_ENDPOINT from env (with localhost
defaults for bare-JVM runs outside compose). prod requires SLPA_STORAGE_BUCKET
and uses DefaultCredentialsProvider for IAM/env-var discovery.

Multipart config capped at 2MB for both max-file-size and max-request-size."
git push
```

---

## Task 3: `AvatarImageProcessor`

**Estimated time:** 1.5 hours.
**Commits:** 1.

### Files

New processor class + fixture binaries + unit test.

### Steps

- [ ] **Step 3.1: Create the fixtures directory**

```bash
cd /c/Users/heath/Repos/Personal/slpa
mkdir -p backend/src/test/resources/fixtures
ls backend/src/test/resources/fixtures/
```

Expected: directory exists, empty.

- [ ] **Step 3.2: Generate the golden fixtures programmatically**

The fixtures are small test assets. Rather than sourcing them, generate them on the fly using a throwaway Java program or ImageMagick. Pick whichever is available.

**Option A — ImageMagick (if installed):**

```bash
cd backend/src/test/resources/fixtures

# 512x512 valid PNG with a red center dot on white background
magick -size 512x512 xc:white -fill red -draw "circle 256,256 256,200" avatar-valid.png

# Same image as JPEG
magick -size 512x512 xc:white -fill red -draw "circle 256,256 256,200" avatar-valid.jpg

# Same image as WebP
magick -size 512x512 xc:white -fill red -draw "circle 256,256 256,200" avatar-valid.webp

# Wide 800x400 PNG with a blue vertical band at horizontal center (pixels 380-420)
magick -size 800x400 xc:white -fill blue -draw "rectangle 380,0 420,400" avatar-wide.png

# 512x512 BMP for the "valid decode but format not allowed" test
magick -size 512x512 xc:white -fill green -draw "circle 256,256 256,200" avatar-invalid.bmp

# JPEG with EXIF orientation=6 (rotate 90 CW on display)
magick -size 400x600 xc:white -fill orange -draw "rectangle 0,0 400,100" -orient right-top avatar-rotated.jpg
```

**Option B — Java throwaway program:** If ImageMagick is not available, write a one-off Java main class that generates all six images using `BufferedImage` + `ImageIO.write`. Delete the main class after the fixtures are created. The fixtures themselves get committed; the generator is scratch work.

**Option C — Source real images:** If you have access to small public-domain test images in each format, use those. Any 512×512 JPEG/PNG/WebP works for the "valid" fixtures.

Also create:

```bash
# Truncated PNG — first 20 bytes of avatar-valid.png
head -c 20 avatar-valid.png > avatar-truncated.png
# 3MB PNG for the oversized test (see Task 4b)
magick -size 3000x3000 xc:"#ff00ff" avatar-3mb.png
```

Verify file sizes are reasonable:

```bash
ls -la backend/src/test/resources/fixtures/
```

Expected: 8 files. The valid ones < 200KB each, `avatar-3mb.png` between 2.5MB and 4MB.

- [ ] **Step 3.3: Create `UnsupportedImageFormatException`**

File: `backend/src/main/java/com/slparcelauctions/backend/user/exception/UnsupportedImageFormatException.java`

```java
package com.slparcelauctions.backend.user.exception;

public class UnsupportedImageFormatException extends RuntimeException {
    public UnsupportedImageFormatException(String message) {
        super(message);
    }

    public UnsupportedImageFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 3.4: Write the failing test — valid PNG produces three sizes**

File: `backend/src/test/java/com/slparcelauctions/backend/user/AvatarImageProcessorTest.java`

```java
package com.slparcelauctions.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException;

class AvatarImageProcessorTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    private AvatarImageProcessor processor;

    @BeforeEach
    void setup() {
        processor = new AvatarImageProcessor();
    }

    private byte[] loadFixture(String name) throws IOException {
        return Files.readAllBytes(FIXTURES.resolve(name));
    }

    private BufferedImage decode(byte[] pngBytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(pngBytes));
    }

    @Test
    void process_validPng_producesThreeSizes() throws IOException {
        byte[] input = loadFixture("avatar-valid.png");

        Map<Integer, byte[]> out = processor.process(input);

        assertThat(out).containsOnlyKeys(64, 128, 256);

        BufferedImage small = decode(out.get(64));
        assertThat(small.getWidth()).isEqualTo(64);
        assertThat(small.getHeight()).isEqualTo(64);

        BufferedImage medium = decode(out.get(128));
        assertThat(medium.getWidth()).isEqualTo(128);
        assertThat(medium.getHeight()).isEqualTo(128);

        BufferedImage large = decode(out.get(256));
        assertThat(large.getWidth()).isEqualTo(256);
        assertThat(large.getHeight()).isEqualTo(256);
    }
}
```

- [ ] **Step 3.5: Run test — expect compile failure**

```bash
cd backend && ./mvnw test -Dtest=AvatarImageProcessorTest -q 2>&1 | tail -20
```

Expected: compile failure — `AvatarImageProcessor` doesn't exist yet.

- [ ] **Step 3.6: Create `AvatarImageProcessor`**

File: `backend/src/main/java/com/slparcelauctions/backend/user/AvatarImageProcessor.java`

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

/**
 * Pure byte[]-in / byte[]-out image processor. Sniffs format via ImageIO,
 * center-crops to square, resizes to three target sizes, and outputs PNG bytes.
 *
 * <p>Format sniffing trusts the bytes, not the multipart {@code Content-Type}
 * header (which is trivially client-controlled).
 */
@Component
@Slf4j
public class AvatarImageProcessor {

    public static final int[] SIZES = {64, 128, 256};
    private static final Set<String> ALLOWED_FORMATS = Set.of("jpeg", "png", "webp");

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

- [ ] **Step 3.7: Run test — expect green**

```bash
cd backend && ./mvnw test -Dtest=AvatarImageProcessorTest -q 2>&1 | tail -20
```

Expected: 1 test passing.

- [ ] **Step 3.8: Add remaining AvatarImageProcessorTest cases**

Append to `AvatarImageProcessorTest.java` (inside the class body, after the first test method):

```java
    @Test
    void process_validJpeg_producesThreeSizes() throws IOException {
        byte[] input = loadFixture("avatar-valid.jpg");

        Map<Integer, byte[]> out = processor.process(input);

        assertThat(out).containsOnlyKeys(64, 128, 256);
        assertThat(decode(out.get(64)).getWidth()).isEqualTo(64);
        assertThat(decode(out.get(128)).getWidth()).isEqualTo(128);
        assertThat(decode(out.get(256)).getWidth()).isEqualTo(256);
    }

    @Test
    void process_validWebp_producesThreeSizes() throws IOException {
        byte[] input = loadFixture("avatar-valid.webp");

        Map<Integer, byte[]> out = processor.process(input);

        assertThat(out).containsOnlyKeys(64, 128, 256);
        // Proves webp-imageio-sejda is on the classpath and SPI-registered.
        assertThat(decode(out.get(256)).getWidth()).isEqualTo(256);
    }

    @Test
    void process_nonSquareInput_centerCrops() throws IOException {
        // avatar-wide.png is 800x400 with a vertical blue band at pixels 380-420
        byte[] input = loadFixture("avatar-wide.png");

        Map<Integer, byte[]> out = processor.process(input);

        BufferedImage cropped = decode(out.get(256));
        assertThat(cropped.getWidth()).isEqualTo(256);
        assertThat(cropped.getHeight()).isEqualTo(256);
        // Center pixel should be blue (from the center band), not white (from the edges).
        int centerPixel = cropped.getRGB(128, 128);
        int blue = centerPixel & 0xFF;
        int green = (centerPixel >> 8) & 0xFF;
        int red = (centerPixel >> 16) & 0xFF;
        assertThat(blue).isGreaterThan(200);
        assertThat(red).isLessThan(100);
        assertThat(green).isLessThan(100);
    }

    @Test
    void process_jpegWithExifRotation_doesNotCrash() throws IOException {
        // avatar-rotated.jpg has EXIF orientation=6. Thumbnailator should handle this
        // internally. The strictest assertion we can make without a perfect color
        // reference is "output dimensions are correct" — if Thumbnailator crashed on
        // EXIF, the method would throw.
        byte[] input = loadFixture("avatar-rotated.jpg");

        Map<Integer, byte[]> out = processor.process(input);

        assertThat(out).containsOnlyKeys(64, 128, 256);
        assertThat(decode(out.get(256)).getWidth()).isEqualTo(256);
    }

    @Test
    void process_invalidBytes_throwsUnsupportedImageFormat() {
        byte[] garbage = new byte[]{0x00, 0x01, 0x02};

        assertThatThrownBy(() -> processor.process(garbage))
                .isInstanceOf(UnsupportedImageFormatException.class);
    }

    @Test
    void process_bmpFormat_throwsUnsupportedImageFormat() throws IOException {
        // BMP is readable by ImageIO (passes sniff) but not in ALLOWED_FORMATS.
        byte[] input = loadFixture("avatar-invalid.bmp");

        assertThatThrownBy(() -> processor.process(input))
                .isInstanceOf(UnsupportedImageFormatException.class)
                .hasMessageContaining("bmp");
    }

    @Test
    void process_truncatedPng_throwsUnsupportedImageFormat() throws IOException {
        byte[] truncated = loadFixture("avatar-truncated.png");

        assertThatThrownBy(() -> processor.process(truncated))
                .isInstanceOf(UnsupportedImageFormatException.class);
    }
```

- [ ] **Step 3.9: Run full AvatarImageProcessorTest**

```bash
cd backend && ./mvnw test -Dtest=AvatarImageProcessorTest -q 2>&1 | tail -20
```

Expected: 8 tests passing.

- [ ] **Step 3.10: Commit Task 3**

```bash
cd /c/Users/heath/Repos/Personal/slpa
git add backend/src/main/java/com/slparcelauctions/backend/user/AvatarImageProcessor.java \
        backend/src/main/java/com/slparcelauctions/backend/user/exception/UnsupportedImageFormatException.java \
        backend/src/test/java/com/slparcelauctions/backend/user/AvatarImageProcessorTest.java \
        backend/src/test/resources/fixtures/
git status --short
```

Expected: processor class + exception + test + 8 fixture files.

```bash
git commit -m "feat(user): add AvatarImageProcessor with ImageIO sniffing and Thumbnailator resize

Pure byte[]-in / byte[]-out component that:
- sniffs format via ImageIO.getImageReaders (not the client Content-Type)
- rejects anything other than JPEG/PNG/WebP
- decodes with the matching reader, dispose()s in a finally
- center-crops to square via Thumbnailator.crop(Positions.CENTER)
- resizes to 64/128/256 in a single fluent chain
- outputs PNG bytes for all three sizes

8 unit tests against golden fixtures: valid PNG/JPEG/WebP, non-square
input (proves center crop picks the correct pixel column), EXIF-rotated
JPEG (proves Thumbnailator's orientation handling doesn't crash), invalid
bytes, BMP (readable by ImageIO but not in allow-list), truncated PNG.

Fixtures committed under backend/src/test/resources/fixtures/ — 8 files,
total ~1MB. The 3MB oversized fixture is there for Task 4b's 413 test."
git push
```

---

## Task 4a: PUT /me — profile edit with hardened validation

**Estimated time:** 1.5 hours.
**Commits:** 1.

### Files

- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/dto/UpdateUserRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/user/UserExceptionHandler.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/UserController.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/user/UserControllerUpdateMeSliceTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/user/UpdateUserFlowIntegrationTest.java`

### Steps

- [ ] **Step 4a.1: Grep for any existing usage of `UpdateUserRequest` that might break under tighter validation**

The current `UpdateUserRequest` allows `displayName` up to 255 chars and `bio` up to 5000. Sub-spec 2a tightens these to 50 and 500 respectively. If any existing test sends a longer string, it'll break.

```bash
cd /c/Users/heath/Repos/Personal/slpa
grep -rn 'UpdateUserRequest\|PUT.*users/me\|updateCurrentUser' backend/src 2>&1
```

Expected: few or zero hits beyond the DTO itself, the controller stub, and possibly the `AuthFlowIntegrationTest`. If any test sends a >50-char `displayName`, edit it to a ≤50-char string in this task's same commit.

- [ ] **Step 4a.2: Harden `UpdateUserRequest`**

File: `backend/src/main/java/com/slparcelauctions/backend/user/dto/UpdateUserRequest.java`

Replace the entire file:

```java
package com.slparcelauctions.backend.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PUT /api/v1/users/me}. Both fields are optional — null
 * means "do not touch this column." An empty-string {@code bio} explicitly clears it.
 *
 * <p><strong>{@code ignoreUnknown = false} is load-bearing.</strong> It rejects
 * any extra field a client tries to sneak in ({@code email}, {@code role},
 * {@code verified}, etc), guarding against privilege escalation via field
 * injection. Do not remove this annotation. The security canary test
 * {@code UserControllerUpdateMeSliceTest.put_me_rejectsUnknownFields_returns400}
 * enforces this rule.
 *
 * <p><strong>{@code @Size(min=1)} null-passthrough semantics:</strong> Jakarta
 * Bean Validation's {@code @Size} does not fire when the value is null. So
 * {@code {"displayName": null}} passes validation (the service then skips the
 * column), while {@code {"displayName": ""}} fails (empty string has
 * {@code size() == 0}, below min).
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record UpdateUserRequest(
        @Size(min = 1, max = 50, message = "displayName must be 1-50 characters") String displayName,
        @Size(max = 500, message = "bio must be at most 500 characters") String bio
) {}
```

- [ ] **Step 4a.3: Create `UserExceptionHandler`**

File: `backend/src/main/java/com/slparcelauctions/backend/user/UserExceptionHandler.java`

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

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Slice-scoped exception handler for the {@code user/} package. Uses the Epic 02
 * sub-spec 1 convention: {@code @Order(Ordered.LOWEST_PRECEDENCE - 100)} so it
 * wins over {@code GlobalExceptionHandler} but can stack predictably with other
 * slice handlers.
 *
 * <p><strong>Note:</strong> {@code MaxUploadSizeExceededException} is NOT handled
 * here. It is thrown by Spring's multipart resolver before the request reaches any
 * {@code @RestController}, so package-scoped advice never sees it. That handler
 * lives in {@code common/exception/GlobalExceptionHandler.java}. See FOOTGUNS
 * §F.28.
 *
 * <p>Handlers for {@code AvatarTooLargeException}, {@code UnsupportedImageFormatException},
 * and {@code InvalidAvatarSizeException} will be added in Task 4b.
 */
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.user")
@Order(Ordered.LOWEST_PRECEDENCE - 100)
@Slf4j
public class UserExceptionHandler {

    @ExceptionHandler(UnrecognizedPropertyException.class)
    public ProblemDetail handleUnknownField(
            UnrecognizedPropertyException e, HttpServletRequest req) {
        log.warn("Request body rejected unknown field: {}", e.getPropertyName());
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

- [ ] **Step 4a.4: Fill in `PUT /me` in `UserController`**

File: `backend/src/main/java/com/slparcelauctions/backend/user/UserController.java`

Replace the existing `updateCurrentUser` method (lines 52-56) with a real implementation. Current:

```java
    @PutMapping("/me")
    public ResponseEntity<ProblemDetail> updateCurrentUser(@Valid @RequestBody UpdateUserRequest request) {
        // Profile edit lands in Task 01-XX (TBD) — needs design pass on field-level edit rules
        return notYetImplemented();
    }
```

Replace with:

```java
    @PutMapping("/me")
    public UserResponse updateCurrentUser(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody UpdateUserRequest request) {
        return userService.updateUser(principal.userId(), request);
    }
```

Also update the `DELETE /me` stub's comment to point at the future GDPR sub-spec (lines 58-62):

```java
    @DeleteMapping("/me")
    public ResponseEntity<ProblemDetail> deleteCurrentUser() {
        // Account deletion has GDPR / soft-delete / cascading-data implications
        // that belong in a dedicated sub-spec. Deferred to a future Epic 02 or
        // Epic 07 task. Keep stub until then.
        return notYetImplemented();
    }
```

`DELETE /me` still returns 501 — scope lock.

- [ ] **Step 4a.5: Write the failing slice test**

File: `backend/src/test/java/com/slparcelauctions/backend/user/UserControllerUpdateMeSliceTest.java`

```java
package com.slparcelauctions.backend.user;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Slice-ish tests for {@code PUT /api/v1/users/me}. Uses @SpringBootTest rather
 * than @WebMvcTest because exercising JWT-authenticated routes against the real
 * security filter chain is simpler than mocking it — same rationale as
 * SlVerificationControllerSliceTest from Epic 02 sub-spec 1.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "auth.cleanup.enabled=false")
@Transactional
class UserControllerUpdateMeSliceTest {

    @Autowired MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Register + login and return the accessToken. */
    private String registerAndLogin(String email) throws Exception {
        String body = String.format(
                "{\"email\":\"%s\",\"password\":\"hunter22abc\",\"displayName\":\"Tester\"}",
                email);
        MvcResult result = mockMvc.perform(put("/api/v1/users/me").contentType(MediaType.APPLICATION_JSON))
                .andReturn();
        // The above is a no-op placeholder — actual helper follows in Step 4a.6.
        return null;
    }

    @Test
    void put_me_unauthenticated_returns401() throws Exception {
        mockMvc.perform(put("/api/v1/users/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"New Name\"}"))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 4a.6: Run the failing test**

```bash
cd backend && ./mvnw test -Dtest=UserControllerUpdateMeSliceTest -q 2>&1 | tail -30
```

Expected: 1 test passing (the 401 check — the rest of the file is placeholder). The compile succeeds because all imports resolve.

- [ ] **Step 4a.7: Replace the placeholder test class with the full suite**

Replace `UserControllerUpdateMeSliceTest.java` entirely:

```java
package com.slparcelauctions.backend.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "auth.cleanup.enabled=false")
@Transactional
class UserControllerUpdateMeSliceTest {

    @Autowired MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Register + login and return the accessToken for use in Authorization headers. */
    private String registerAndLogin(String email) throws Exception {
        String body = String.format(
                "{\"email\":\"%s\",\"password\":\"hunter22abc\",\"displayName\":\"Tester\"}",
                email);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    @Test
    void put_me_happyPath_returns200() throws Exception {
        String token = registerAndLogin("put-me-happy@example.com");

        mockMvc.perform(put("/api/v1/users/me")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Alice Resident\",\"bio\":\"Designer of whimsical cottages\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Alice Resident"))
                .andExpect(jsonPath("$.bio").value("Designer of whimsical cottages"));
    }

    @Test
    void put_me_displayNameTooLong_returns400() throws Exception {
        String token = registerAndLogin("put-me-longname@example.com");
        String name51 = "A".repeat(51);

        mockMvc.perform(put("/api/v1/users/me")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"" + name51 + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://slpa.example/problems/validation"));
    }

    @Test
    void put_me_bioTooLong_returns400() throws Exception {
        String token = registerAndLogin("put-me-longbio@example.com");
        String bio501 = "x".repeat(501);

        mockMvc.perform(put("/api/v1/users/me")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bio\":\"" + bio501 + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://slpa.example/problems/validation"));
    }

    @Test
    void put_me_displayNameEmpty_returns400() throws Exception {
        String token = registerAndLogin("put-me-empty@example.com");

        mockMvc.perform(put("/api/v1/users/me")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void put_me_nullFields_skipsUpdate() throws Exception {
        String token = registerAndLogin("put-me-null@example.com");

        mockMvc.perform(put("/api/v1/users/me")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":null,\"bio\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Tester"));
    }

    /**
     * SECURITY CANARY — do not remove. This test enforces the
     * {@code @JsonIgnoreProperties(ignoreUnknown = false)} guard on
     * {@link com.slparcelauctions.backend.user.dto.UpdateUserRequest}. Without it
     * a client could sneak {@code email}, {@code role}, or {@code verified} fields
     * into a PUT /me body and potentially escalate privilege via field injection.
     */
    @Test
    void put_me_rejectsUnknownFields_returns400() throws Exception {
        String token = registerAndLogin("put-me-canary@example.com");

        mockMvc.perform(put("/api/v1/users/me")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Alice\",\"email\":\"hacker@example.com\",\"role\":\"admin\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://slpa.example/problems/user/unknown-field"))
                .andExpect(jsonPath("$.code").value("USER_UNKNOWN_FIELD"));
    }

    @Test
    void put_me_unauthenticated_returns401() throws Exception {
        mockMvc.perform(put("/api/v1/users/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"New Name\"}"))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 4a.8: Run the full slice test**

```bash
cd backend && ./mvnw test -Dtest=UserControllerUpdateMeSliceTest -q 2>&1 | tail -20
```

Expected: 7 tests passing. If the `rejectsUnknownFields` test fails because Jackson isn't actually rejecting the field, double-check that `@JsonIgnoreProperties(ignoreUnknown = false)` is on `UpdateUserRequest` and that `UserExceptionHandler.handleUnknownField` is wired (both should be from earlier steps).

- [ ] **Step 4a.9: Create `UpdateUserFlowIntegrationTest`**

File: `backend/src/test/java/com/slparcelauctions/backend/user/UpdateUserFlowIntegrationTest.java`

```java
package com.slparcelauctions.backend.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "auth.cleanup.enabled=false")
@Transactional
class UpdateUserFlowIntegrationTest {

    @Autowired MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void registerUpdateMeReadBack_persistsFields() throws Exception {
        // Register
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"update-flow@example.com\",\"password\":\"hunter22abc\",\"displayName\":\"Original\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String accessToken = objectMapper.readTree(reg.getResponse().getContentAsString())
                .get("accessToken").asText();

        // PUT /me to update both fields
        mockMvc.perform(put("/api/v1/users/me")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Updated Name\",\"bio\":\"Updated bio text\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Updated Name"))
                .andExpect(jsonPath("$.bio").value("Updated bio text"));

        // GET /me returns the persisted values
        mockMvc.perform(get("/api/v1/users/me")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Updated Name"))
                .andExpect(jsonPath("$.bio").value("Updated bio text"));
    }
}
```

- [ ] **Step 4a.10: Run integration test + full backend suite**

```bash
cd backend && ./mvnw test -Dtest=UpdateUserFlowIntegrationTest -q 2>&1 | tail -20
```

Expected: 1 test passing.

```bash
cd backend && ./mvnw test -q 2>&1 | grep -E 'BUILD (SUCCESS|FAILURE)|Tests run:' | tail -5
```

Expected: `BUILD SUCCESS`, ~157 tests (141 baseline + 7 from Task 2 + 8 from Task 3 + 7+1 from Task 4a = 164 — close enough; number may vary if some existing test class was trimmed by the tightened validation).

- [ ] **Step 4a.11: Commit Task 4a**

```bash
cd /c/Users/heath/Repos/Personal/slpa
git add backend/src/main/java/com/slparcelauctions/backend/user/dto/UpdateUserRequest.java \
        backend/src/main/java/com/slparcelauctions/backend/user/UserExceptionHandler.java \
        backend/src/main/java/com/slparcelauctions/backend/user/UserController.java \
        backend/src/test/java/com/slparcelauctions/backend/user/UserControllerUpdateMeSliceTest.java \
        backend/src/test/java/com/slparcelauctions/backend/user/UpdateUserFlowIntegrationTest.java
git status --short
```

```bash
git commit -m "feat(user): wire PUT /me with hardened validation against unknown fields

Replaces the notYetImplemented stub with a real PUT /me handler that
delegates to UserService.updateUser. Tightens UpdateUserRequest validation
from @Size(max=255)/5000 to @Size(min=1,max=50)/@Size(max=500) and adds
@JsonIgnoreProperties(ignoreUnknown=false) as a privilege-escalation guard.

Creates UserExceptionHandler, the user/ slice's @RestControllerAdvice at
@Order(LOWEST_PRECEDENCE - 100) (matching the Epic 02 sub-spec 1 convention).
Handles UnrecognizedPropertyException with a tailored 400 ProblemDetail
that names the rejected field. More handlers land in Task 4b.

Security canary test UserControllerUpdateMeSliceTest.put_me_rejectsUnknownFields
sends {email, role} in the request body and asserts the tailored 400. Do not
remove — see FOOTGUNS F.30 (added in Task 6).

DELETE /me stub stays 501 with its comment updated to point at a future
GDPR sub-spec. Scope lock."
git push
```

---

## Task 4b: Avatar upload + serve

**Estimated time:** 2.5 hours.
**Commits:** 1.

### Files

- Create: `backend/src/main/resources/static/placeholders/avatar-{64,128,256}.png`
- Create: `backend/src/main/java/com/slparcelauctions/backend/user/exception/AvatarTooLargeException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/user/exception/InvalidAvatarSizeException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/user/AvatarService.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/user/AvatarServiceTest.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/UserController.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/UserExceptionHandler.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/common/exception/GlobalExceptionHandler.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/user/UserControllerAvatarSliceTest.java`

### Steps

- [ ] **Step 4b.1: Create the three placeholder PNGs**

```bash
cd /c/Users/heath/Repos/Personal/slpa
mkdir -p backend/src/main/resources/static/placeholders
```

Generate three simple silhouette PNGs (gray circle on white background):

```bash
cd backend/src/main/resources/static/placeholders
# Using ImageMagick:
magick -size 64x64 xc:"#ffffff" -fill "#888888" -draw "circle 32,32 32,12" avatar-64.png
magick -size 128x128 xc:"#ffffff" -fill "#888888" -draw "circle 64,64 64,24" avatar-128.png
magick -size 256x256 xc:"#ffffff" -fill "#888888" -draw "circle 128,128 128,48" avatar-256.png
ls -la
```

Expected: three files, each <5KB. If ImageMagick isn't available, use the same throwaway-Java-main pattern from Task 3 Step 3.2.

- [ ] **Step 4b.2: Create `AvatarTooLargeException` and `InvalidAvatarSizeException`**

File: `backend/src/main/java/com/slparcelauctions/backend/user/exception/AvatarTooLargeException.java`

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

File: `backend/src/main/java/com/slparcelauctions/backend/user/exception/InvalidAvatarSizeException.java`

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

- [ ] **Step 4b.3: Write the failing AvatarServiceTest — upload happy path**

File: `backend/src/test/java/com/slparcelauctions/backend/user/AvatarServiceTest.java`

```java
package com.slparcelauctions.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.storage.StoredObject;
import com.slparcelauctions.backend.storage.exception.ObjectNotFoundException;
import com.slparcelauctions.backend.user.dto.UserResponse;
import com.slparcelauctions.backend.user.exception.AvatarTooLargeException;
import com.slparcelauctions.backend.user.exception.InvalidAvatarSizeException;
import com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException;

class AvatarServiceTest {

    private UserRepository userRepository;
    private ObjectStorageService storage;
    private AvatarImageProcessor processor;
    private AvatarService service;

    @BeforeEach
    void setup() {
        userRepository = mock(UserRepository.class);
        storage = mock(ObjectStorageService.class);
        processor = mock(AvatarImageProcessor.class);
        service = new AvatarService(userRepository, storage, processor, new DefaultResourceLoader());
    }

    @Test
    void upload_happyPath_putsThreeObjectsAndUpdatesUser() {
        User user = User.builder()
                .id(1L).email("a@b.c").passwordHash("x").verified(false).build();
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.png", "image/png", new byte[]{1, 2, 3});
        Map<Integer, byte[]> resized = Map.of(
                64, new byte[]{10},
                128, new byte[]{20},
                256, new byte[]{30});
        when(processor.process(any(byte[].class))).thenReturn(resized);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        UserResponse resp = service.upload(1L, file);

        verify(storage).put(eq("avatars/1/64.png"), any(byte[].class), eq("image/png"));
        verify(storage).put(eq("avatars/1/128.png"), any(byte[].class), eq("image/png"));
        verify(storage).put(eq("avatars/1/256.png"), any(byte[].class), eq("image/png"));
        assertThat(user.getProfilePicUrl()).isEqualTo("/api/v1/users/1/avatar/256");
        assertThat(resp.profilePicUrl()).isEqualTo("/api/v1/users/1/avatar/256");
    }
}
```

- [ ] **Step 4b.4: Run the failing test**

```bash
cd backend && ./mvnw test -Dtest=AvatarServiceTest -q 2>&1 | tail -20
```

Expected: compile failure — `AvatarService` doesn't exist yet.

- [ ] **Step 4b.5: Create `AvatarService`**

File: `backend/src/main/java/com/slparcelauctions/backend/user/AvatarService.java`

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

/**
 * Orchestrator for avatar upload + fetch. Single {@code @Transactional} on
 * {@link #upload(Long, MultipartFile)} spans validation, image processing,
 * all three S3 puts, and the DB update. See spec §10 transaction boundary
 * note for why the narrower boundary was walked back during spec self-review
 * (Spring AOP same-class proxy limitation — FOOTGUNS §F.29).
 */
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
        log.info("Avatar uploaded for user {} ({} bytes -> 3 sizes)", userId, bytes.length);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        user.setProfilePicUrl("/api/v1/users/" + userId + "/avatar/256");
        // JPA dirty checking flushes the setProfilePicUrl on transaction commit.
        return UserResponse.from(user);
    }

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

- [ ] **Step 4b.6: Run test — expect green**

```bash
cd backend && ./mvnw test -Dtest=AvatarServiceTest -q 2>&1 | tail -20
```

Expected: 1 test passing.

- [ ] **Step 4b.7: Add remaining AvatarServiceTest cases**

Append to `AvatarServiceTest.java` (inside the class, after `upload_happyPath_putsThreeObjectsAndUpdatesUser`):

```java
    @Test
    void upload_oversizedFile_throwsAvatarTooLarge() {
        byte[] oversized = new byte[(int) (2 * 1024 * 1024) + 1];
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.png", "image/png", oversized);

        assertThatThrownBy(() -> service.upload(1L, file))
                .isInstanceOf(AvatarTooLargeException.class);
        verify(processor, never()).process(any());
        verify(storage, never()).put(any(), any(), any());
    }

    @Test
    void upload_unsupportedFormat_propagatesFromProcessor() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "bad.bmp", "image/bmp", new byte[]{1, 2, 3});
        when(processor.process(any(byte[].class)))
                .thenThrow(new UnsupportedImageFormatException("bmp not allowed"));

        assertThatThrownBy(() -> service.upload(1L, file))
                .isInstanceOf(UnsupportedImageFormatException.class);
        verify(storage, never()).put(any(), any(), any());
        verify(userRepository, never()).findById(any());
    }

    @Test
    void fetch_userDoesNotExist_throwsUserNotFound() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.fetch(999L, 128))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void fetch_userHasNoAvatar_returnsPlaceholder() {
        User user = User.builder().id(1L).profilePicUrl(null).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        StoredObject result = service.fetch(1L, 128);

        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.bytes()).isNotEmpty();
        verify(storage, never()).get(any());
    }

    @Test
    void fetch_userHasAvatar_returnsProxiedBytes() {
        User user = User.builder().id(1L).profilePicUrl("/api/v1/users/1/avatar/256").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        byte[] stored = new byte[]{50, 51, 52};
        when(storage.get("avatars/1/128.png"))
                .thenReturn(new StoredObject(stored, "image/png", 3L));

        StoredObject result = service.fetch(1L, 128);

        assertThat(result.bytes()).isEqualTo(stored);
    }

    @Test
    void fetch_orphanedProfilePicUrl_returnsPlaceholder() {
        User user = User.builder().id(1L).profilePicUrl("/api/v1/users/1/avatar/256").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(storage.get("avatars/1/128.png"))
                .thenThrow(new ObjectNotFoundException("avatars/1/128.png", null));

        StoredObject result = service.fetch(1L, 128);

        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.bytes()).isNotEmpty();
    }

    @Test
    void fetch_invalidSize_throwsInvalidAvatarSize() {
        assertThatThrownBy(() -> service.fetch(1L, 99))
                .isInstanceOf(InvalidAvatarSizeException.class);
        verify(userRepository, never()).findById(any());
    }
```

- [ ] **Step 4b.8: Run full AvatarServiceTest**

```bash
cd backend && ./mvnw test -Dtest=AvatarServiceTest -q 2>&1 | tail -20
```

Expected: 8 tests passing.

- [ ] **Step 4b.9: Extend `UserExceptionHandler` with three new handlers**

File: `backend/src/main/java/com/slparcelauctions/backend/user/UserExceptionHandler.java`

Add these three `@ExceptionHandler` methods inside the existing class body (after `handleUnknownField`):

```java
    @ExceptionHandler(com.slparcelauctions.backend.user.exception.AvatarTooLargeException.class)
    public ProblemDetail handleAvatarTooLarge(
            com.slparcelauctions.backend.user.exception.AvatarTooLargeException e,
            HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.PAYLOAD_TOO_LARGE, "Avatar must be 2MB or less.");
        pd.setType(URI.create("https://slpa.example/problems/user/upload-too-large"));
        pd.setTitle("Upload too large");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "USER_UPLOAD_TOO_LARGE");
        pd.setProperty("maxBytes", e.getMaxBytes());
        return pd;
    }

    @ExceptionHandler(com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException.class)
    public ProblemDetail handleUnsupportedImageFormat(
            com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException e,
            HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Upload must be a JPEG, PNG, or WebP image.");
        pd.setType(URI.create("https://slpa.example/problems/user/unsupported-image-format"));
        pd.setTitle("Unsupported image format");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "USER_UNSUPPORTED_IMAGE_FORMAT");
        return pd;
    }

    @ExceptionHandler(com.slparcelauctions.backend.user.exception.InvalidAvatarSizeException.class)
    public ProblemDetail handleInvalidAvatarSize(
            com.slparcelauctions.backend.user.exception.InvalidAvatarSizeException e,
            HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Avatar size must be 64, 128, or 256.");
        pd.setType(URI.create("https://slpa.example/problems/user/invalid-avatar-size"));
        pd.setTitle("Invalid avatar size");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "USER_INVALID_AVATAR_SIZE");
        pd.setProperty("requestedSize", e.getRequestedSize());
        return pd;
    }
```

Prefer to move the three exception imports to the top of the file with the existing ones rather than the inline fully-qualified form; the inline form above is shown for clarity and compiles the same way.

- [ ] **Step 4b.10: Add `handleMaxUploadSize` to `GlobalExceptionHandler`**

File: `backend/src/main/java/com/slparcelauctions/backend/common/exception/GlobalExceptionHandler.java`

Add a new `@ExceptionHandler` method inside the class body (near the other handlers). Ensure the import list includes `org.springframework.web.multipart.MaxUploadSizeExceededException` (add if missing):

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

Same URI + title as the `UserExceptionHandler.handleAvatarTooLarge` version so clients cannot distinguish which layer caught the oversized upload.

- [ ] **Step 4b.11: Update `SecurityConfig` with three new matchers**

File: `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java`

Find the existing `.authorizeHttpRequests(auth -> auth ...)` block and insert the new matchers BEFORE the `/api/v1/**` authenticated catch-all. Order matters — more-specific paths must precede less-specific. The new entries go right after the existing `/api/v1/sl/verify` permit matcher from Epic 02 sub-spec 1:

```java
                        .requestMatchers(HttpMethod.POST, "/api/v1/sl/verify").permitAll()
                        // --- New in Epic 02 sub-spec 2a ---
                        // Public avatar proxy. Must come before /api/v1/users/{id} (also public)
                        // and the /api/v1/** catch-all. FOOTGUNS §B.5 matcher ordering.
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/{id}/avatar/{size}").permitAll()
                        // Authenticated avatar upload.
                        .requestMatchers(HttpMethod.POST, "/api/v1/users/me/avatar").authenticated()
                        // Authenticated profile edit (explicit for grep-ability; catch-all also covers it).
                        .requestMatchers(HttpMethod.PUT, "/api/v1/users/me").authenticated()
                        // --- End Epic 02 sub-spec 2a additions ---
                        .requestMatchers("/api/v1/dev/**").permitAll()
```

Note: the `/api/v1/dev/**` matcher from sub-spec 1's Task 4 should already be in the chain. If the block you're editing doesn't have it, this plan step is appending in the wrong place — re-read the file and find the correct insertion point.

- [ ] **Step 4b.12: Add avatar endpoints to `UserController`**

File: `backend/src/main/java/com/slparcelauctions/backend/user/UserController.java`

Add these two new mappings inside the class (after `updateCurrentUser`, before `deleteCurrentUser`). You'll also need to add imports:

```java
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import com.slparcelauctions.backend.storage.StoredObject;
```

And add `avatarService` to the field list (Lombok's `@RequiredArgsConstructor` wires it automatically):

```java
    private final UserService userService;
    private final AvatarService avatarService;
```

Then the two new handlers:

```java
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
```

- [ ] **Step 4b.13: Compile the backend**

```bash
cd backend && ./mvnw compile -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`. Fix any compile errors (missing imports, typos) before moving to the slice test.

- [ ] **Step 4b.14: Create `UserControllerAvatarSliceTest`**

File: `backend/src/test/java/com/slparcelauctions/backend/user/UserControllerAvatarSliceTest.java`

```java
package com.slparcelauctions.backend.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "auth.cleanup.enabled=false")
@Transactional
class UserControllerAvatarSliceTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    @Autowired MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String registerAndLogin(String email) throws Exception {
        String body = String.format(
                "{\"email\":\"%s\",\"password\":\"hunter22abc\",\"displayName\":\"Avatar Tester\"}",
                email);
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(reg.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private Long userIdFromToken(String token) throws Exception {
        MvcResult me = mockMvc.perform(get("/api/v1/users/me")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(me.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    void get_avatar_publicEndpointNoAuth_returnsPlaceholder() throws Exception {
        String token = registerAndLogin("avatar-noauth@example.com");
        Long userId = userIdFromToken(token);

        mockMvc.perform(get("/api/v1/users/" + userId + "/avatar/128"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", "public, max-age=86400, immutable"));
    }

    @ParameterizedTest
    @ValueSource(ints = {64, 128, 256})
    void get_avatar_allThreeSizesSucceed(int size) throws Exception {
        String token = registerAndLogin("avatar-size-" + size + "@example.com");
        Long userId = userIdFromToken(token);

        mockMvc.perform(get("/api/v1/users/" + userId + "/avatar/" + size))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"));
    }

    @Test
    void get_avatar_invalidSize_returns400() throws Exception {
        String token = registerAndLogin("avatar-invalid-size@example.com");
        Long userId = userIdFromToken(token);

        mockMvc.perform(get("/api/v1/users/" + userId + "/avatar/99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://slpa.example/problems/user/invalid-avatar-size"))
                .andExpect(jsonPath("$.code").value("USER_INVALID_AVATAR_SIZE"));
    }

    @Test
    void get_avatar_nonexistentUser_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/users/999999999/avatar/128"))
                .andExpect(status().isNotFound());
    }

    @Test
    void post_avatar_unauthenticated_returns401() throws Exception {
        byte[] pngBytes = Files.readAllBytes(FIXTURES.resolve("avatar-valid.png"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.png", "image/png", pngBytes);

        mockMvc.perform(multipart("/api/v1/users/me/avatar").file(file))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void post_avatar_unsupportedFormat_returns400() throws Exception {
        String token = registerAndLogin("avatar-bmp@example.com");
        byte[] bmpBytes = Files.readAllBytes(FIXTURES.resolve("avatar-invalid.bmp"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.bmp", "image/bmp", bmpBytes);

        mockMvc.perform(multipart("/api/v1/users/me/avatar")
                .file(file)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://slpa.example/problems/user/unsupported-image-format"));
    }

    @Test
    void post_avatar_oversizedMultipart_returns413() throws Exception {
        String token = registerAndLogin("avatar-oversized@example.com");
        byte[] bigBytes = Files.readAllBytes(FIXTURES.resolve("avatar-3mb.png"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "big.png", "image/png", bigBytes);

        mockMvc.perform(multipart("/api/v1/users/me/avatar")
                .file(file)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.type").value("https://slpa.example/problems/user/upload-too-large"));
    }

    @Test
    void post_avatar_missingFileParam_returns400() throws Exception {
        String token = registerAndLogin("avatar-missing-file@example.com");

        mockMvc.perform(multipart("/api/v1/users/me/avatar")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 4b.15: Run full backend suite**

```bash
cd backend && ./mvnw test -q 2>&1 | grep -E 'BUILD (SUCCESS|FAILURE)|Tests run:' | tail -5
```

Expected: `BUILD SUCCESS`, ~170+ tests. All passing.

- [ ] **Step 4b.16: Commit Task 4b**

```bash
cd /c/Users/heath/Repos/Personal/slpa
git add backend/src/main/resources/static/placeholders/ \
        backend/src/main/java/com/slparcelauctions/backend/user/exception/AvatarTooLargeException.java \
        backend/src/main/java/com/slparcelauctions/backend/user/exception/InvalidAvatarSizeException.java \
        backend/src/main/java/com/slparcelauctions/backend/user/AvatarService.java \
        backend/src/main/java/com/slparcelauctions/backend/user/UserController.java \
        backend/src/main/java/com/slparcelauctions/backend/user/UserExceptionHandler.java \
        backend/src/main/java/com/slparcelauctions/backend/common/exception/GlobalExceptionHandler.java \
        backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java \
        backend/src/test/java/com/slparcelauctions/backend/user/AvatarServiceTest.java \
        backend/src/test/java/com/slparcelauctions/backend/user/UserControllerAvatarSliceTest.java
git status --short
```

```bash
git commit -m "feat(user): add avatar upload and proxy serving with placeholder fallback

New endpoints:
- POST /api/v1/users/me/avatar (multipart, authenticated)
- GET /api/v1/users/{id}/avatar/{size} (public proxy, sizes 64/128/256)

AvatarService orchestrates: validate size -> process via AvatarImageProcessor
-> put 3 sizes to S3 -> update users.profile_pic_url to the proxy URL.
Single @Transactional spans the whole pipeline — see spec section 10
transaction boundary note and FOOTGUNS F.29 for why the narrow-boundary
attempt was walked back (Spring AOP same-class proxy limitation).

fetch() returns the classpath placeholder PNG for both null-profilePicUrl
(new user) and orphaned-profilePicUrl (DB says yes, S3 returns 404) cases.
Orphaned case logs at ERROR.

Three placeholder PNGs shipped under static/placeholders/ as small
silhouette images. Three new exception types (AvatarTooLargeException,
InvalidAvatarSizeException, and UnsupportedImageFormatException from Task 3)
get tailored ProblemDetail handlers in UserExceptionHandler.
MaxUploadSizeExceededException handler lives in GlobalExceptionHandler
because Spring's multipart resolver throws before any @RestController
can run, so package-scoped advice never sees it (FOOTGUNS F.28).

SecurityConfig gets three new matchers inserted before the /api/v1/** catch-all:
GET /api/v1/users/{id}/avatar/{size} permitAll, POST avatar authenticated,
and explicit PUT /me authenticated for grep-ability."
git push
```

---

## Task 5: Integration tests + /me response shape alignment

**Estimated time:** 1.5 hours.
**Commits:** 1.

### Files

- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/dto/UserResponse.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/user/AvatarUploadFlowIntegrationTest.java`

### Steps

- [ ] **Step 5.1: Align `UserResponse` shape with the task doc's private profile list**

File: `backend/src/main/java/com/slparcelauctions/backend/user/dto/UserResponse.java`

The task doc requires the private profile response to include: email, bio, profile pic URL, verified status, SL avatar name, account age (derivable from `createdAt`), seller/buyer ratings, completion stats, email verification, notification prefs, verification details (date, payment info).

Current `UserResponse` has: id, email, displayName, bio, profilePicUrl, slAvatarUuid, slUsername, slDisplayName, verified, emailVerified, notifyEmail, notifySlIm, createdAt.

Missing: `slAvatarName`, `slBornDate`, `slPayinfo`, `verifiedAt`.

Replace the record definition:

```java
package com.slparcelauctions.backend.user.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import com.slparcelauctions.backend.user.User;

public record UserResponse(
        Long id,
        String email,
        String displayName,
        String bio,
        String profilePicUrl,
        UUID slAvatarUuid,
        String slAvatarName,
        String slUsername,
        String slDisplayName,
        LocalDate slBornDate,
        Integer slPayinfo,
        Boolean verified,
        OffsetDateTime verifiedAt,
        Boolean emailVerified,
        Map<String, Object> notifyEmail,
        Map<String, Object> notifySlIm,
        OffsetDateTime createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getBio(),
                user.getProfilePicUrl(),
                user.getSlAvatarUuid(),
                user.getSlAvatarName(),
                user.getSlUsername(),
                user.getSlDisplayName(),
                user.getSlBornDate(),
                user.getSlPayinfo(),
                user.getVerified(),
                user.getVerifiedAt(),
                user.getEmailVerified(),
                user.getNotifyEmail(),
                user.getNotifySlIm(),
                user.getCreatedAt());
    }
}
```

- [ ] **Step 5.2: Compile and run the full suite**

```bash
cd backend && ./mvnw test -q 2>&1 | grep -E 'BUILD (SUCCESS|FAILURE)|Tests run:' | tail -5
```

Expected: `BUILD SUCCESS`. Any existing test that asserted on a specific `UserResponse` shape (e.g., counted its fields, deserialized to a local record with a fixed component list) may fail and will need updating in this same commit. Grep for such tests:

```bash
grep -rln 'UserResponse' backend/src/test 2>&1
```

Inspect each hit and fix any that break.

- [ ] **Step 5.3: Add `/me returns all private fields` assertion to `UpdateUserFlowIntegrationTest`**

File: `backend/src/test/java/com/slparcelauctions/backend/user/UpdateUserFlowIntegrationTest.java`

Append a new test method:

```java
    @Test
    void getMe_returnsAllPrivateFields() throws Exception {
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"full-fields@example.com\",\"password\":\"hunter22abc\",\"displayName\":\"Full\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String accessToken = objectMapper.readTree(reg.getResponse().getContentAsString())
                .get("accessToken").asText();

        mockMvc.perform(get("/api/v1/users/me")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                // Public fields
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.displayName").value("Full"))
                .andExpect(jsonPath("$.verified").value(false))
                // Private fields (extended in Task 5)
                .andExpect(jsonPath("$.email").value("full-fields@example.com"))
                .andExpect(jsonPath("$.emailVerified").exists())
                .andExpect(jsonPath("$.notifyEmail").exists())
                .andExpect(jsonPath("$.notifySlIm").exists())
                // SL fields (null for unverified user, but the keys must be present)
                .andExpect(jsonPath("$.slAvatarName").doesNotExist())   // Jackson omits null by default? confirm during run
                .andExpect(jsonPath("$.slBornDate").doesNotExist())
                .andExpect(jsonPath("$.slPayinfo").doesNotExist())
                .andExpect(jsonPath("$.verifiedAt").doesNotExist());
    }
```

Note on Jackson null handling: by default, Spring Boot's Jackson configuration serializes null fields as `null` (JSON), not omit them. The `.doesNotExist()` assertions above may need to change to `.value(nullValue())` depending on the actual serialization. Run the test first, adjust assertions based on the actual JSON shape.

- [ ] **Step 5.4: Run the integration test to observe Jackson null behavior**

```bash
cd backend && ./mvnw test -Dtest=UpdateUserFlowIntegrationTest -q 2>&1 | tail -30
```

Expected: 2 tests passing. If `getMe_returnsAllPrivateFields` fails on the SL null assertions, flip `.doesNotExist()` to `.value(org.hamcrest.Matchers.nullValue())` (and add the import) and re-run.

- [ ] **Step 5.5: Write `AvatarUploadFlowIntegrationTest`**

File: `backend/src/test/java/com/slparcelauctions/backend/user/AvatarUploadFlowIntegrationTest.java`

```java
package com.slparcelauctions.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.storage.StorageConfigProperties;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "auth.cleanup.enabled=false")
@Transactional
class AvatarUploadFlowIntegrationTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired ObjectStorageService storage;
    @Autowired S3Client s3Client;
    @Autowired StorageConfigProperties storageProps;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long id : createdUserIds) {
            try {
                storage.deletePrefix("avatars/" + id + "/");
            } catch (Exception e) {
                // best effort
            }
        }
        createdUserIds.clear();
    }

    private String registerAndTrack(String email) throws Exception {
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.format(
                        "{\"email\":\"%s\",\"password\":\"hunter22abc\",\"displayName\":\"Avatar Tester\"}",
                        email)))
                .andExpect(status().isCreated())
                .andReturn();
        String token = objectMapper.readTree(reg.getResponse().getContentAsString())
                .get("accessToken").asText();
        Long userId = objectMapper.readTree(reg.getResponse().getContentAsString())
                .get("user").get("id").asLong();
        createdUserIds.add(userId);
        return token;
    }

    private Long userIdFromToken(String token) throws Exception {
        MvcResult me = mockMvc.perform(get("/api/v1/users/me")
                .header("Authorization", "Bearer " + token))
                .andReturn();
        return objectMapper.readTree(me.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    void storageBucketExists_provesStartupValidatorRan() {
        // Single headBucket assertion that proves StorageStartupValidator ran
        // during context startup and either found or created the bucket.
        s3Client.headBucket(HeadBucketRequest.builder()
                .bucket(storageProps.bucket())
                .build());
    }

    @Test
    void fullFlow_registerUploadFetchReadBack() throws Exception {
        String token = registerAndTrack("avatar-roundtrip@example.com");
        Long userId = userIdFromToken(token);

        byte[] pngBytes = Files.readAllBytes(FIXTURES.resolve("avatar-valid.png"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.png", "image/png", pngBytes);

        // Upload
        mockMvc.perform(multipart("/api/v1/users/me/avatar")
                .file(file)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profilePicUrl").value("/api/v1/users/" + userId + "/avatar/256"));

        // Fetch all three sizes and assert valid PNG of expected dimensions
        for (int size : new int[]{64, 128, 256}) {
            MvcResult res = mockMvc.perform(get("/api/v1/users/" + userId + "/avatar/" + size))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "image/png"))
                    .andExpect(header().string("Cache-Control", "public, max-age=86400, immutable"))
                    .andReturn();
            byte[] body = res.getResponse().getContentAsByteArray();
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(body));
            assertThat(img).as("avatar at size " + size).isNotNull();
            assertThat(img.getWidth()).isEqualTo(size);
            assertThat(img.getHeight()).isEqualTo(size);
        }
    }

    @Test
    void fullFlow_reuploadOverwritesPriorObject() throws Exception {
        String token = registerAndTrack("avatar-reupload@example.com");
        Long userId = userIdFromToken(token);

        byte[] firstBytes = Files.readAllBytes(FIXTURES.resolve("avatar-valid.png"));
        byte[] secondBytes = Files.readAllBytes(FIXTURES.resolve("avatar-valid.jpg"));

        // Upload first image
        mockMvc.perform(multipart("/api/v1/users/me/avatar")
                .file(new MockMultipartFile("file", "first.png", "image/png", firstBytes))
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        MvcResult firstFetch = mockMvc.perform(get("/api/v1/users/" + userId + "/avatar/256"))
                .andExpect(status().isOk()).andReturn();
        byte[] firstStored = firstFetch.getResponse().getContentAsByteArray();

        // Upload second image
        mockMvc.perform(multipart("/api/v1/users/me/avatar")
                .file(new MockMultipartFile("file", "second.jpg", "image/jpeg", secondBytes))
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        MvcResult secondFetch = mockMvc.perform(get("/api/v1/users/" + userId + "/avatar/256"))
                .andExpect(status().isOk()).andReturn();
        byte[] secondStored = secondFetch.getResponse().getContentAsByteArray();

        assertThat(secondStored).as("second upload should produce different bytes").isNotEqualTo(firstStored);
    }

    @Test
    void fullFlow_uploadedUserHasProfilePicUrlInGetMe() throws Exception {
        String token = registerAndTrack("avatar-getme@example.com");
        Long userId = userIdFromToken(token);

        byte[] pngBytes = Files.readAllBytes(FIXTURES.resolve("avatar-valid.png"));
        mockMvc.perform(multipart("/api/v1/users/me/avatar")
                .file(new MockMultipartFile("file", "avatar.png", "image/png", pngBytes))
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/me")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profilePicUrl").value("/api/v1/users/" + userId + "/avatar/256"));
    }
}
```

- [ ] **Step 5.6: Run the integration test**

```bash
cd backend && ./mvnw test -Dtest=AvatarUploadFlowIntegrationTest -q 2>&1 | tail -30
```

Expected: 4 tests passing. Requires the dev MinIO container running.

**Common failure mode:** `@Transactional` on the integration test class rolls back the DB, but S3 writes are NOT transactional. The `@AfterEach` cleanup handles S3 garbage. If the test DB state leaks between tests (e.g., `full-fields@example.com` already exists), it's because a previous run of `UpdateUserFlowIntegrationTest` committed outside the transaction — which shouldn't happen with `@Transactional` but does if you run individual tests with different rollback settings. Truncate the users table if you see this:

```bash
docker compose exec postgres psql -U slpa -d slpa -c "TRUNCATE users CASCADE;"
```

- [ ] **Step 5.7: Full backend suite**

```bash
cd backend && ./mvnw test -q 2>&1 | grep -E 'BUILD (SUCCESS|FAILURE)|Tests run:' | tail -5
```

Expected: `BUILD SUCCESS`, ~175+ tests passing.

- [ ] **Step 5.8: Commit Task 5**

```bash
cd /c/Users/heath/Repos/Personal/slpa
git add backend/src/main/java/com/slparcelauctions/backend/user/dto/UserResponse.java \
        backend/src/test/java/com/slparcelauctions/backend/user/UpdateUserFlowIntegrationTest.java \
        backend/src/test/java/com/slparcelauctions/backend/user/AvatarUploadFlowIntegrationTest.java
# Add any existing tests you had to update for the UserResponse shape change
git status --short
```

```bash
git commit -m "test(user): add avatar upload integration tests and align /me response shape

UserResponse now includes the four fields the task doc required but were
missing: slAvatarName, slBornDate, slPayinfo, verifiedAt. UserResponse.from
wires them from the existing User entity columns (all populated by Epic 02
sub-spec 1's SL verify flow).

AvatarUploadFlowIntegrationTest exercises the full register -> upload ->
fetch round-trip against real dev MinIO:
- storageBucketExists: single headBucket assertion proving
  StorageStartupValidator ran during context startup
- fullFlow_registerUploadFetchReadBack: upload a golden 512x512 PNG, fetch
  all three sizes, assert each is a valid PNG with the expected dimensions
- fullFlow_reuploadOverwritesPriorObject: proves that re-uploading with a
  different image produces different stored bytes
- fullFlow_uploadedUserHasProfilePicUrlInGetMe: confirms /me reflects the
  upload

@AfterEach cleans up S3 objects via ObjectStorageService.deletePrefix for
every user created during the test — @Transactional rolls back the DB rows
but not the S3 writes.

UpdateUserFlowIntegrationTest gains a getMe_returnsAllPrivateFields test
that asserts every required private field is present in the /me response."
git push
```

---

## Task 6: Postman + README + FOOTGUNS + PR

**Estimated time:** 1 hour.
**Commits:** 2 (docs + smoke-test / PR open is not a commit).

### Steps

- [ ] **Step 6.1: Postman — add `Update current user` (PUT /me) request**

Call `mcp__postman__createCollectionRequest` with:
- `collectionId: 8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288`
- `folderId`: the existing `Users/` folder ID (`8070328-1069216e-1313-0c6c-cdf1-eb47ac96e3cd` per Epic 02 sub-spec 1's Task 1)
- name: `Update current user`
- method: `PUT`
- URL: `{{baseUrl}}/api/v1/users/me`
- headers: `Authorization: Bearer {{accessToken}}`, `Content-Type: application/json`
- raw body:
```json
{
  "displayName": "Alice Resident",
  "bio": "Designer of whimsical cottages in the Corsica region."
}
```
- description: "Update the authenticated user's displayName and/or bio. Both fields optional. Rejects unknown fields with 400."

Add saved examples via `mcp__postman__createCollectionResponse`:

**200 OK** example body (full UserResponse shape — pull exact JSON from the real backend by running `PUT /me` manually):
```json
{
  "id": 1,
  "email": "tester@example.com",
  "displayName": "Alice Resident",
  "bio": "Designer of whimsical cottages in the Corsica region.",
  "profilePicUrl": null,
  "slAvatarUuid": null,
  "slAvatarName": null,
  "slUsername": null,
  "slDisplayName": null,
  "slBornDate": null,
  "slPayinfo": null,
  "verified": false,
  "verifiedAt": null,
  "emailVerified": false,
  "notifyEmail": {},
  "notifySlIm": {},
  "createdAt": "2026-04-14T10:00:00Z"
}
```

**400 Unknown field** example body:
```json
{
  "type": "https://slpa.example/problems/user/unknown-field",
  "title": "Unknown field",
  "status": 400,
  "detail": "Unknown field in request body: 'email'.",
  "instance": "/api/v1/users/me",
  "code": "USER_UNKNOWN_FIELD",
  "field": "email"
}
```

**400 Validation** example body:
```json
{
  "type": "https://slpa.example/problems/validation",
  "title": "Validation failed",
  "status": 400,
  "detail": "Request validation failed.",
  "instance": "/api/v1/users/me",
  "code": "VALIDATION_FAILED",
  "errors": {
    "displayName": "displayName must be 1-50 characters"
  }
}
```

- [ ] **Step 6.2: Postman — add `Upload avatar` (POST /me/avatar) request**

Call `mcp__postman__createCollectionRequest` with:
- same `collectionId` and `folderId`
- name: `Upload avatar`
- method: `POST`
- URL: `{{baseUrl}}/api/v1/users/me/avatar`
- headers: `Authorization: Bearer {{accessToken}}` (do NOT set `Content-Type` manually — Postman will set `multipart/form-data; boundary=...` based on the body type)
- body mode: `formdata` with a single field named `file` of type `file`, no pre-set value (user selects from local disk when running the request in the UI)
- description: "Upload a JPG/PNG/WebP avatar (max 2MB). Server resizes to 64/128/256 PNGs and returns the updated user with profilePicUrl set."

Note: the Postman MCP `createCollectionRequest` tool may not directly support the `formdata` body mode. If not, create the request with an empty body and document that the user selects the file manually in the Postman UI. Use `mcp__postman__updateCollectionRequest` after creation to set the body mode if needed.

Saved examples:

**200 OK**: same full UserResponse shape as above but with `profilePicUrl: "/api/v1/users/1/avatar/256"`.

**413 Upload too large**:
```json
{
  "type": "https://slpa.example/problems/user/upload-too-large",
  "title": "Upload too large",
  "status": 413,
  "detail": "Avatar must be 2MB or less.",
  "instance": "/api/v1/users/me/avatar",
  "code": "USER_UPLOAD_TOO_LARGE"
}
```

**400 Unsupported format**:
```json
{
  "type": "https://slpa.example/problems/user/unsupported-image-format",
  "title": "Unsupported image format",
  "status": 400,
  "detail": "Upload must be a JPEG, PNG, or WebP image.",
  "instance": "/api/v1/users/me/avatar",
  "code": "USER_UNSUPPORTED_IMAGE_FORMAT"
}
```

- [ ] **Step 6.3: Postman — add `Get user avatar` (GET /users/{id}/avatar/{size}) request**

First, add a new environment variable `avatarSize` with default value `256` to the `SLPA Dev` environment. Use `mcp__postman__patchEnvironment` on environment id `8070328-765124a7-76b4-4981-b30a-62dce654950b`:

```
{"values": [{"key": "avatarSize", "value": "256", "type": "default", "enabled": true}]}
```

Then create the request:
- name: `Get user avatar`
- method: `GET`
- URL: `{{baseUrl}}/api/v1/users/{{userId}}/avatar/{{avatarSize}}`
- headers: none (public endpoint)
- description: "Public avatar proxy. Returns image/png bytes at the requested size. Returns placeholder PNG when the user has no avatar uploaded."

Saved examples:

**200 OK (binary)**: response headers include `Content-Type: image/png`, `Cache-Control: public, max-age=86400, immutable`. Postman will show the PNG as binary — the example is just the headers + a placeholder "binary" note.

**400 Invalid size**:
```json
{
  "type": "https://slpa.example/problems/user/invalid-avatar-size",
  "title": "Invalid avatar size",
  "status": 400,
  "detail": "Avatar size must be 64, 128, or 256.",
  "instance": "/api/v1/users/1/avatar/99",
  "code": "USER_INVALID_AVATAR_SIZE",
  "requestedSize": 99
}
```

**404 User not found**:
```json
{
  "type": "https://slpa.example/problems/user/not-found",
  "title": "User not found",
  "status": 404,
  "detail": "User not found.",
  "instance": "/api/v1/users/99999/avatar/128",
  "code": "USER_NOT_FOUND"
}
```

- [ ] **Step 6.4: README sweep**

File: `README.md`

Read the current README. Then make these additions:

1. **Docker Compose section** — add MinIO to the list of services the compose stack brings up, along with its console port (9001).
2. **API endpoints section** (if one exists; otherwise add one) — document the three new routes:
   - `PUT /api/v1/users/me` — edit display name and bio
   - `POST /api/v1/users/me/avatar` — upload avatar (multipart, 2MB max, JPG/PNG/WebP)
   - `GET /api/v1/users/{id}/avatar/{size}` — public avatar proxy, sizes 64/128/256
3. **Avatar storage paragraph** — one paragraph explaining: MinIO in dev, real S3 in prod, backend proxy (not presigned URLs), placeholder PNG fallback, `profile_pic_url` column stores the proxy path not an S3 URL.
4. **Backend test count** — bump the approximate count (e.g., "~141 backend tests" → "~175 backend tests").
5. **Repository layout** — if the README lists Java packages, add `storage/`.

- [ ] **Step 6.5: FOOTGUNS F.28, F.29, F.30**

File: `docs/implementation/FOOTGUNS.md`

Read the file to find the highest current footgun number (should be F.27 after Epic 02 sub-spec 1). Append three new entries matching the existing `### F.NN — Title` → `**Why:**` → `**How to apply:**` structure.

**F.28 — `MaxUploadSizeExceededException` is thrown before the dispatcher.**

**Why:** Spring's `StandardServletMultipartResolver` parses the multipart request body before `DispatcherServlet` routes to any `@RestController`. When the request body exceeds `spring.servlet.multipart.max-file-size` or `max-request-size`, the resolver throws `MaxUploadSizeExceededException` during request parsing — which means a package-scoped `@RestControllerAdvice(basePackages = "...")` never sees the exception because the request has not reached any controller in that package yet. A slice's exception handler scoped to `user/` will never handle multipart size errors even on `POST /api/v1/users/me/avatar`.

**How to apply:** Put the `MaxUploadSizeExceededException` handler in `common/exception/GlobalExceptionHandler.java` (the unscoped advice) or in any advice without a `basePackages` filter. The `AvatarTooLargeException` defensive re-check in `AvatarService.upload` can still live in `UserExceptionHandler` because it fires from inside a controller method. Keep both handlers producing the same `ProblemDetail` shape so clients can't distinguish which layer caught the bloat.

**F.29 — Spring `@Transactional` same-class method calls bypass the AOP proxy.**

**Why:** Spring's default `@Transactional` mechanism uses JDK dynamic proxies or CGLIB subclassing to intercept method calls. A method call through a bean reference goes through the proxy and triggers the transaction interceptor. But a same-class call — `this.helper()` from inside another method on the same class — goes directly to the target object and **bypasses the proxy entirely**. This holds regardless of the target method's visibility (private, protected, or public). It is not a bug in your code; it is a fundamental limitation of proxy-based AOP. The trap: you add `@Transactional` to a private helper, call it from a non-`@Transactional` public method, and observe that JPA dirty checking never flushes the update. No error is thrown. The test passes in isolation (via direct service injection + stubbed repository) but fails in integration.

**How to apply:** To narrow a `@Transactional` boundary, you have three options: (a) extract the DB mutation to a separate `@Service` bean and inject it (proxy fires correctly through the injected reference); (b) self-inject via `@Autowired @Lazy AvatarService self` and call `self.helper()` (the `@Lazy` breaks the circular dependency); (c) use `TransactionTemplate` programmatically to open an explicit transaction around a lambda. If the narrowing isn't worth the extra plumbing, keep the annotation on the outer public method. `AvatarService.upload` in Epic 02 sub-spec 2a chose option (c-alternative) — the single `@Transactional` on `upload()` spans the S3 puts because the Phase 1 scale argument beats the optimization argument. Revisit if profiling shows DB pool starvation.

**F.30 — `@JsonIgnoreProperties(ignoreUnknown = false)` is a privilege-escalation guard that must have test coverage.**

**Why:** By default, Spring's Jackson configuration silently ignores unknown JSON fields when deserializing a request body into a DTO — `{"email": "hacker@example.com", "role": "admin"}` becomes an object with only the fields the DTO declares, and the extras are discarded. This is usually fine (forward-compatible APIs). It is **not** fine on endpoints where the DTO intentionally omits fields to prevent clients from setting them (e.g., `UpdateUserRequest` omits `email`, `role`, `verified` by design). Without `@JsonIgnoreProperties(ignoreUnknown = false)` on the DTO, a hostile client can smuggle those fields into the request body and rely on Jackson's leniency to get them past validation. Even WITH the annotation, a future refactor can silently remove it ("cleanup: delete unused annotation") and reopen the hole. The guard must be enforced by a test that would fail if the annotation were removed.

**How to apply:** Every DTO that needs field-injection protection gets `@JsonIgnoreProperties(ignoreUnknown = false)`. Every such DTO also gets a dedicated security canary test that posts a request body with an unknown field and asserts 400 with the `user/unknown-field` ProblemDetail type. Example: `UserControllerUpdateMeSliceTest.put_me_rejectsUnknownFields_returns400` in the `user/` slice. **Scoping note:** the `UnrecognizedPropertyException` handler currently lives in `UserExceptionHandler` (scoped to the `user/` package). If a future slice (e.g., `auction/`, `parcel/`) adopts the same hardening, that slice's `UnrecognizedPropertyException` will fall through to `GlobalExceptionHandler` and get a generic 400 instead of the tailored "unknown field" response. When that happens, either (a) add a sibling handler to the new slice's advice, or (b) move the `UnrecognizedPropertyException` handler up to `GlobalExceptionHandler` so every slice benefits. Option (b) is the cleaner long-term fix.

- [ ] **Step 6.6: Commit README + FOOTGUNS**

```bash
cd /c/Users/heath/Repos/Personal/slpa
git add README.md docs/implementation/FOOTGUNS.md
git status --short
```

```bash
git commit -m "docs: README sweep and FOOTGUNS F.28-F.30 for Epic 02 sub-spec 2a

README additions:
- MinIO service in the docker-compose stack
- PUT /me, POST /me/avatar, GET /users/{id}/avatar/{size} endpoints
- Avatar storage paragraph (MinIO dev / S3 prod, backend proxy,
  placeholder fallback, profile_pic_url holds the proxy path)
- Updated backend test count

FOOTGUNS additions:
- F.28: MaxUploadSizeExceededException is thrown before the dispatcher,
  so package-scoped @RestControllerAdvice never sees it — handler must
  live in GlobalExceptionHandler.
- F.29: Spring @Transactional same-class calls bypass the AOP proxy
  regardless of visibility. Three working options to narrow a boundary;
  AvatarService.upload chose to accept the broader boundary.
- F.30: @JsonIgnoreProperties(ignoreUnknown = false) is a privilege-
  escalation guard that must be protected by a security canary test.
  Scoping note: UnrecognizedPropertyException handler is currently in
  UserExceptionHandler; move to GlobalExceptionHandler when a second
  slice adopts the same hardening."
git push
```

- [ ] **Step 6.7: Final pre-PR smoke test**

```bash
cd backend && ./mvnw test -q 2>&1 | grep -E 'BUILD (SUCCESS|FAILURE)|Tests run:' | tail -5
```

Expected: `BUILD SUCCESS`, ~175+ tests.

```bash
cd /c/Users/heath/Repos/Personal/slpa/frontend && npm test -- --run 2>&1 | tail -10
```

Expected: 185 passing + 1 todo (unchanged — no frontend work in 2a).

Manual smoke via docker compose:

```bash
cd /c/Users/heath/Repos/Personal/slpa
docker compose up -d
sleep 30
docker compose ps
curl -fsS http://localhost:8080/api/v1/health && echo " - backend OK"
curl -fsS http://localhost:9000/minio/health/live && echo " - minio OK"
```

Expected: all 5 services healthy, both health endpoints OK.

- [ ] **Step 6.8: Open the PR into dev**

```bash
gh pr create --base dev --title "Epic 02 sub-spec 2a — profile API + avatar upload" --body "$(cat <<'EOF'
## Summary

Implements Epic 02 sub-spec 2a (backend profile API + avatar upload) per `docs/superpowers/specs/2026-04-14-epic-02-sub-2a-profile-api-avatar-upload.md`. Seven tasks in commit order:

1. Dependencies (`software.amazon.awssdk:s3`, `net.coobird:thumbnailator`, `org.sejda.imageio:webp-imageio`) + MinIO service in docker-compose + stale `/api/health` healthcheck fix
2. `storage/` vertical slice: `StorageConfigProperties`, `S3ClientConfig` (profile-aware), `StorageStartupValidator` (prod fail-fast / non-prod auto-create), `ObjectStorageService` interface + `S3ObjectStorageService` impl with paginated `deletePrefix`
3. `AvatarImageProcessor`: Thumbnailator pipeline with ImageIO format sniffing, 8 unit tests against golden fixtures
4a. `PUT /api/v1/users/me` filled in: hardened `UpdateUserRequest` (`@JsonIgnoreProperties(ignoreUnknown = false)` + tightened `@Size`), `UserExceptionHandler` with `UnrecognizedPropertyException` handler, security canary slice test
4b. `POST /api/v1/users/me/avatar` + `GET /api/v1/users/{id}/avatar/{size}`: `AvatarService` orchestrator, placeholder fallback for no-avatar and orphaned cases, three `UserExceptionHandler` methods + one `GlobalExceptionHandler` method for `MaxUploadSizeExceededException`, `SecurityConfig` matcher additions, 8 slice tests
5. Integration tests against real dev MinIO (register → upload → fetch round-trip, re-upload overwrite, `/me` reflects upload, `headBucket` proves startup validator ran) + `UserResponse` shape alignment adding `slAvatarName`/`slBornDate`/`slPayinfo`/`verifiedAt`
6. Postman (3 new requests in `Users/` folder with saved examples) + README sweep + FOOTGUNS F.28-F.30

Backend test suite grows from ~141 to ~175+ tests.

## Test plan

- [x] `./mvnw test` passes (all existing + ~34 new tests)
- [x] `npm test -- --run` still passes (frontend untouched)
- [x] `docker compose up` stands up 5 services healthy (postgres, redis, minio, backend, frontend)
- [x] Manual curl smoke: `/api/v1/health` → 200, `/api/v1/users/1/avatar/128` → 200 PNG (placeholder), `POST /me/avatar` with a valid PNG → 200 + `profilePicUrl` set
- [x] Postman: SLPA Dev environment + `Users/Update current user` + `Users/Upload avatar` + `Users/Get user avatar` all present with saved examples
- [x] Bucket `slpa-uploads` auto-created on first backend startup (confirmed in log output)
- [x] Security canary: `PUT /me` with `{"email": "hacker@..."}` returns 400 `user/unknown-field`

## Postman runbook

1. Select `SLPA Dev` environment in the top-right dropdown.
2. `Auth/Register` → 201 (one time; 409 on subsequent runs is fine).
3. `Auth/Login` → 200. Environment vars `accessToken` / `refreshToken` / `userId` populate automatically via the Login test script.
4. `Users/Update current user` → 200. Body already wires `displayName` and `bio`.
5. `Users/Upload avatar` → In the Postman UI, switch to the `Body` tab, click the `file` field, and select a JPG/PNG/WebP ≤2MB from local disk. Fire → 200 with `profilePicUrl` set.
6. `Users/Get user avatar` → 200 PNG bytes. Change `avatarSize` env var to `64` or `128` to fetch smaller sizes.

See `docs/superpowers/specs/2026-04-14-epic-02-sub-2a-profile-api-avatar-upload.md` for the full design and `docs/implementation/FOOTGUNS.md` F.28-F.30 for the gotchas this sub-spec surfaced.
EOF
)"
```

Expected: PR URL printed. Record it.

- [ ] **Step 6.9: Return to dev branch**

```bash
git checkout dev
```

Leave the working tree on `dev`. The user reviews and merges the PR on GitHub.

---

## Done definition

Sub-spec 2a is done when all of the following are true:

- [ ] All 7 tasks committed in order per §16 of the spec.
- [ ] `./mvnw test` passes — ~175+ tests, BUILD SUCCESS.
- [ ] `cd frontend && npm test -- --run` still passes (185 + 1 todo, unchanged — no frontend work in 2a).
- [ ] `docker compose up` stands up all 5 services healthy.
- [ ] Manual smoke: register → login → upload an avatar via Postman → fetch at all three sizes.
- [ ] Postman `SLPA` collection has the 3 new Users/ folder requests with saved examples.
- [ ] `README.md` swept with MinIO, new endpoints, avatar storage paragraph, updated test count.
- [ ] `docs/implementation/FOOTGUNS.md` has F.28, F.29, F.30.
- [ ] PR opened into `dev` (not `main`).
- [ ] No AI/tool attribution anywhere in commits or PR body.
- [ ] Local branch is `dev` after the PR opens.

---

## Appendix — spec section cross-reference

| Spec section | Plan coverage |
|---|---|
| §1 Summary | Plan preamble |
| §2 Scope | Preamble + task list |
| §3 Background | Preflight (read spec) |
| §4.1 PUT /me | Task 4a |
| §4.2 POST /me/avatar | Task 4b (Steps 4b.12) |
| §4.3 GET /{id}/avatar/{size} | Task 4b (Steps 4b.12) |
| §4.4 DELETE /me stays 501 | Task 4a (Step 4a.4) |
| §5 Package structure | File Structure section of plan |
| §6 Data model (no schema changes) | Task 5 (UserResponse alignment) |
| §7 Config props + S3 client + startup validator | Task 2 |
| §8 ObjectStorageService interface + S3ObjectStorageService | Task 2 |
| §9 AvatarImageProcessor | Task 3 |
| §10 AvatarService + transaction boundary note | Task 4b (Step 4b.5) |
| §11 Controller wiring | Task 4a (Step 4a.4) + Task 4b (Step 4b.12) |
| §11.3 SecurityConfig matchers | Task 4b (Step 4b.11) |
| §12 docker-compose changes | Task 1 (Steps 1.3-1.7) |
| §13 Error handling + exception mapping | Task 4a (Step 4a.3) + Task 4b (Steps 4b.9, 4b.10) |
| §14 Security considerations | Implicit in matcher ordering + bean validation + ImageIO sniffing |
| §15 Testing strategy | Test classes across all tasks |
| §16 Task breakdown | Tasks 1-6 |
| §17 Done definition | Done definition section above |
| §18 Deferred to sub-spec 2b | Not in this plan |
| §19 Decisions log | Not in this plan |

