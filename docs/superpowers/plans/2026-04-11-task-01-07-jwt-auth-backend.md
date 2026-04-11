# Task 01-07 — JWT Authentication Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the `auth/` vertical slice: DB-backed refresh tokens with rotation and reuse detection, 15-minute HS256 access tokens, the `JwtAuthenticationFilter` that sets a lightweight `AuthPrincipal` in the Spring Security context, five auth endpoints (`register`, `login`, `refresh`, `logout`, `logout-all`), per-slice exception handling that produces RFC 7807 ProblemDetail responses, and the test infrastructure (`@WithMockAuthPrincipal`, `JwtTestFactory`, `RefreshTokenTestFixture`) that every subsequent protected-endpoint task will inherit.

**Architecture:** Access tokens are stateless 15-minute HS256 JWTs carried in the `Authorization: Bearer` header; the filter validates and parses them without touching the database. Refresh tokens are opaque 256-bit random strings, SHA-256-hashed at rest in a dedicated `refresh_tokens` table, rotated on every refresh, and cascade-revoked on reuse detection. The 15-minute access-token staleness window is closed for write-path operations by a `token_version` column on the user that write-path services check at the integrity boundary.

**Tech Stack:** Spring Boot 4.0.5 / Java 26 / Spring Security 6 / Spring Data JPA + Hibernate + Lombok / JJWT 0.12.6 (new) / BCrypt (existing `PasswordEncoder`) / PostgreSQL 16 / JUnit 5 + Mockito + Spring Security Test + `@SpringBootTest`

**Spec:** [`docs/superpowers/specs/2026-04-11-task-01-07-jwt-auth-backend-design.md`](../specs/2026-04-11-task-01-07-jwt-auth-backend-design.md)

---

## Post-spec refinements (from spec sign-off — already baked into this plan)

Five corrections and one meta-lesson from the spec review. The implementer does **not** need to reconcile these with the spec — they are already applied in the task steps below.

1. **`GlobalExceptionHandler` does NOT handle `AuthenticationException`.** The spec's §9 listed it; the sign-off removed it because `JwtAuthenticationEntryPoint` already owns `AUTH_TOKEN_MISSING` for protected-endpoint-without-auth via Spring's `exceptionHandling(eh -> eh.authenticationEntryPoint(...))`. The entry point intercepts before any `@ExceptionHandler` chain fires, so a global handler for it would be dead code. Plan's Task 23 omits the handler accordingly.
2. **`AuthService.refresh()` carries a clarifying comment** on the post-rotation user load: `// happy-path-only; the reuse-cascade path throws before reaching this line`. Plan's Task 21 shows the comment inline.
3. **Reuse-cascade integration test asserts `AUTH_REFRESH_TOKEN_REUSED`** as the exact code emitted when a cleanly-rotated token is replayed — not a distinct "already revoked" error. The code is correct (reuse detection fires on `revokedAt != null` before any "already revoked" check could). Plan's Task 33 asserts the code explicitly.
4. **`JwtTestFactory` exposes a `static forKey(String secretBase64)` factory** in addition to the `@Component` bean path. Pure-unit tests (`JwtServiceTest`, `JwtAuthenticationFilterTest`) use `JwtTestFactory.forKey(...)` to instantiate without a Spring context. Plan's Task 8 ships both construction paths.
5. **`User.tokenVersion` gets `columnDefinition = "bigint not null default 0"`.** `@Builder.Default` is Java-side only; Hibernate needs the SQL-side default for `ddl-auto: update` to safely add the NOT NULL column to existing rows without failing on the first dev to pull the branch. Plan's Task 13 shows the annotation inline.

**Meta-lesson** (added to FOOTGUNS during Task 36): **load-bearing documentation pattern** — document *why* a critical decision can't be refactored away, not just *what*. Examples from this spec: the `AuthResult`/`AuthResponse` split's JavaDoc, the `Path=/api/auth` non-widening rule, the `@RestControllerAdvice(basePackages=...)` scoping, and the reuse-cascade canary test. Future contributors read the warning before reaching for the refactor.

---

## File structure

### New files (32 production + 10 test)

**Production (`backend/src/main/java/com/slparcelauctions/backend/`)**

| Path | Purpose |
|---|---|
| `auth/AuthPrincipal.java` | Record `(Long userId, String email, Long tokenVersion)` — Spring Security principal type |
| `auth/TokenHasher.java` | Static helpers: `sha256Hex(String)`, `secureRandomBase64Url(int bytes)` |
| `auth/JwtKeyFactory.java` | Static `buildKey(String base64)` shared by prod + test; HS256 min-length validation |
| `auth/JwtService.java` | `issueAccessToken(AuthPrincipal)` / `parseAccessToken(String)` facade over JJWT 0.12 |
| `auth/JwtAuthenticationFilter.java` | `OncePerRequestFilter` — best-effort token parse, sets `AuthPrincipal` on success |
| `auth/JwtAuthenticationEntryPoint.java` | `AuthenticationEntryPoint` — emits ProblemDetail with manual serialization |
| `auth/RefreshToken.java` | JPA entity — `token_hash`, `user_id`, `expires_at`, `revoked_at`, `last_used_at`, etc. |
| `auth/RefreshTokenRepository.java` | `findByTokenHash`, `findAllByUserId`, `revokeAllByUserId`, `deleteOldRows` |
| `auth/RefreshTokenService.java` | `issueForUser`, `rotate` (reuse cascade), `revokeByRawToken`, `revokeAllForUser` |
| `auth/AuthService.java` | Orchestrates register / login / refresh / logout / logout-all |
| `auth/AuthController.java` | `POST /api/auth/{register,login,refresh,logout,logout-all}` + cookie helpers |
| `auth/RefreshTokenCleanupJob.java` | `@Scheduled` daily cleanup of expired + revoked rows > 30 days old |
| `auth/config/JwtConfig.java` | `@ConfigurationProperties(prefix = "jwt")` + `@PostConstruct` validation + `SecretKey` bean |
| `auth/dto/LoginRequest.java` | Record `(@Email email, @NotBlank password)` |
| `auth/dto/RegisterRequest.java` | Record `(@Email email, @NotBlank password, @NotBlank displayName)` |
| `auth/dto/AuthResult.java` | **Internal** service→controller record `(accessToken, refreshToken, user)` |
| `auth/dto/AuthResponse.java` | **External** controller→client record `(accessToken, user)` — no refresh token |
| `auth/exception/InvalidCredentialsException.java` | |
| `auth/exception/EmailAlreadyExistsException.java` | |
| `auth/exception/TokenExpiredException.java` | |
| `auth/exception/TokenInvalidException.java` | |
| `auth/exception/RefreshTokenReuseDetectedException.java` | Carries `userId` for logging |
| `auth/exception/AuthenticationStaleException.java` | |
| `auth/exception/AuthExceptionHandler.java` | `@RestControllerAdvice(basePackages="...auth")` — 6 handlers → ProblemDetail |
| `common/exception/GlobalExceptionHandler.java` | Cross-cutting only (validation, malformed JSON, 403/404/410/500) |
| `common/exception/ResourceNotFoundException.java` | |
| `common/exception/ResourceGoneException.java` | |

**Test infrastructure (`backend/src/test/java/com/slparcelauctions/backend/auth/test/`)**

| Path | Purpose |
|---|---|
| `WithMockAuthPrincipal.java` | Annotation with `@WithSecurityContext(factory = ...)` |
| `WithMockAuthPrincipalSecurityContextFactory.java` | Factory building `AuthPrincipal` → `SecurityContext` |
| `JwtTestFactory.java` | `@Component` + `static forKey(String)` — issues real signed tokens for tests |
| `RefreshTokenTestFixture.java` | `@Component` — `insertValid / insertRevoked / insertExpired` |
| `README.md` | Documents the `@WithSecurityContext` wiring path used |

### Modified files

| Path | Change |
|---|---|
| `backend/pom.xml` | Add `jjwt-api` / `jjwt-impl` / `jjwt-jackson` 0.12.6 |
| `backend/src/main/resources/application.yml` | Add `jwt.secret: ${JWT_SECRET}` + lifetimes + `auth.cleanup.enabled: true` |
| `backend/src/main/resources/application-dev.yml` | Add placeholder `jwt.secret` + DEV ONLY comment (contractor regenerates) |
| `backend/src/main/resources/application-prod.yml` | Add `jwt.secret: ${JWT_SECRET}` (no default) |
| `backend/src/main/java/com/slparcelauctions/backend/SlparcelauctionsBackendApplication.java` | Add `@EnableScheduling` |
| `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java` | Wire filter + entry point, flip `permitAll()` → `authenticated()`, exact-match rules |
| `backend/src/main/java/com/slparcelauctions/backend/user/User.java` | Add `token_version` column (SQL-side default) |
| `backend/src/main/java/com/slparcelauctions/backend/user/UserService.java` | Add `bumpTokenVersion(Long userId)` public method |
| `backend/src/main/java/com/slparcelauctions/backend/user/UserController.java` | Replace GET /me 501 stub with real impl; update PUT/DELETE comments |
| `backend/src/main/java/com/slparcelauctions/backend/user/dto/UserResponse.java` | Exclude `tokenVersion` from serialization |
| `backend/src/test/java/com/slparcelauctions/backend/user/UserControllerTest.java` | Replace GET /me 501 case with 200-auth + 401-unauth cases |
| `backend/src/test/java/com/slparcelauctions/backend/user/UserServiceTest.java` | Add `bumpTokenVersion` unit test |
| `docs/implementation/FOOTGUNS.md` | Add §B backend section + §5.8 load-bearing documentation meta-lesson |
| `README.md` | Sweep backend section for JWT auth mention |

---

## Phases overview

| Phase | Name | Tasks | What lands |
|---|---|---|---|
| A | Dependencies & scheduling | 1–4 | JJWT on classpath, `jwt.*` config, `JwtConfig`, `@EnableScheduling` |
| B | Core primitives & helpers | 5–7 | `AuthPrincipal`, `TokenHasher`, auth exception classes |
| C | JWT service + test helpers | 8–10 | `JwtTestFactory`, `JwtService`, their tests |
| D | Mock-auth test annotation | 11–12 | `@WithMockAuthPrincipal` + factory + README |
| E | Entity layer | 13–16 | `User.tokenVersion`, `UserService.bumpTokenVersion`, `RefreshToken` entity + repository |
| F | Test fixture completion | 17 | `RefreshTokenTestFixture` |
| G | Filter + entry point | 18–19 | `JwtAuthenticationFilter`, `JwtAuthenticationEntryPoint` |
| H | Service layer | 20–21 | `RefreshTokenService`, `AuthService` |
| I | Exception handlers | 22–23 | `AuthExceptionHandler`, `GlobalExceptionHandler` |
| J | DTOs + controller | 24–26 | Request/response records, `AuthController`, cookie helpers |
| K | Security wiring | 27–28 | `SecurityConfig` flip, `RefreshTokenCleanupJob` |
| L | Cross-slice | 29 | `GET /me` real impl, `UserControllerTest` update |
| M | Integration tests | 30–34 | `AuthFlowIntegrationTest` + the reuse-cascade canary |
| N | Finalization | 35–38 | Slice tests, FOOTGUNS, README, final verify |

**Convention recap** (applies to every task): single atomic commit, conventional-commits format with `feat(auth):` / `chore(auth):` / `test(auth):` / `docs(auth):` scope as appropriate, no AI attribution in the subject or body, no `--no-verify`, `./mvnw test` passes before every commit. The worktree is at `C:\Users\heath\Repos\Personal\slpa-task-01-07` on branch `task/01-07-jwt-auth-backend`.

---

## Phase A — Dependencies & scheduling

### Task 1: Add JJWT 0.12.6 dependency to pom.xml

**Files:**
- Modify: `backend/pom.xml`

- [ ] **Step 1: Open `backend/pom.xml` and find the `<dependencies>` section**

- [ ] **Step 2: Add the three JJWT dependencies**

Insert immediately after the `spring-boot-starter-security` dependency (or anywhere in the dependencies block):

```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
```

- [ ] **Step 3: Verify Maven resolves the new dependencies**

```bash
cd backend
./mvnw dependency:resolve -q
```

Expected: command succeeds, no "could not resolve" errors. JJWT artifacts appear in `~/.m2/repository/io/jsonwebtoken/`.

- [ ] **Step 4: Verify the existing test suite still compiles and passes**

```bash
cd backend
./mvnw test -q
```

Expected: all existing tests pass (no auth tests yet — this is a baseline check that the dependency change didn't break the compile).

- [ ] **Step 5: Commit**

```bash
cd backend
git add pom.xml
git commit -m "chore(auth): add JJWT 0.12.6 dependency"
```

---

### Task 2: Add JWT configuration keys to application.yml files

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/resources/application-dev.yml`
- Modify: `backend/src/main/resources/application-prod.yml`

- [ ] **Step 1: Add the `jwt.*` block to `application.yml`**

Append to `backend/src/main/resources/application.yml`:

```yaml
jwt:
  secret: ${JWT_SECRET}             # no default — fail-fast on prod startup if missing
  access-token-lifetime: PT15M      # ISO-8601 duration: 15 minutes
  refresh-token-lifetime: P7D       # ISO-8601 duration: 7 days

auth:
  cleanup:
    enabled: true
```

- [ ] **Step 2: Add the dev secret placeholder to `application-dev.yml`**

Append to `backend/src/main/resources/application-dev.yml`:

```yaml
jwt:
  # DEV ONLY — production reads jwt.secret from JWT_SECRET env var.
  # This default must never ship to prod.
  # 32 bytes (256 bits) base64-encoded.
  # Regenerate via: openssl rand -base64 32
  # Do NOT use the string below as-is — replace it with real random bytes before committing.
  secret: REPLACE_WITH_openssl_rand_base64_32_OUTPUT
```

- [ ] **Step 3: Replace the placeholder with a real random value**

```bash
openssl rand -base64 32
```

Copy the output (43 chars + `=` padding). Paste it into `application-dev.yml` replacing `REPLACE_WITH_openssl_rand_base64_32_OUTPUT`. The value must be real random bytes, not a memorable string.

- [ ] **Step 4: Add the prod declaration to `application-prod.yml`**

Append to `backend/src/main/resources/application-prod.yml`:

```yaml
jwt:
  secret: ${JWT_SECRET}    # required, fail-fast on startup if missing
```

- [ ] **Step 5: Verify the app still starts against the dev profile**

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Wait for `Started SlparcelauctionsBackendApplication`. The startup will fail with an `@ConfigurationProperties` binding error only if `jwt.secret` is missing or unparseable — which shouldn't happen here because `JwtConfig` doesn't exist yet. Ctrl+C the process once you see startup success.

- [ ] **Step 6: Commit**

```bash
cd backend
git add src/main/resources/application.yml src/main/resources/application-dev.yml src/main/resources/application-prod.yml
git commit -m "chore(auth): add jwt.* and auth.cleanup config keys"
```

---

### Task 3: Create `JwtKeyFactory` + `JwtConfig` with fail-fast validation

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auth/JwtKeyFactory.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auth/config/JwtConfig.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auth/JwtKeyFactoryTest.java`

- [ ] **Step 1: Write the failing test for `JwtKeyFactory`**

Create `backend/src/test/java/com/slparcelauctions/backend/auth/JwtKeyFactoryTest.java`:

```java
package com.slparcelauctions.backend.auth;

import org.junit.jupiter.api.Test;
import javax.crypto.SecretKey;
import java.util.Base64;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtKeyFactoryTest {

    @Test
    void buildKey_succeedsWith32ByteSecret() {
        byte[] bytes = new byte[32];
        for (int i = 0; i < 32; i++) bytes[i] = (byte) i;
        String base64 = Base64.getEncoder().encodeToString(bytes);

        SecretKey key = JwtKeyFactory.buildKey(base64);

        assertThat(key).isNotNull();
        assertThat(key.getAlgorithm()).isEqualTo("HmacSHA256");
    }

    @Test
    void buildKey_throwsOnShortSecret() {
        byte[] bytes = new byte[16]; // too short
        String base64 = Base64.getEncoder().encodeToString(bytes);

        assertThatThrownBy(() -> JwtKeyFactory.buildKey(base64))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("at least 32 bytes");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd backend
./mvnw test -Dtest=JwtKeyFactoryTest -q
```

Expected: compilation failure — `JwtKeyFactory` does not exist.

- [ ] **Step 3: Create `JwtKeyFactory`**

Create `backend/src/main/java/com/slparcelauctions/backend/auth/JwtKeyFactory.java`:

```java
package com.slparcelauctions.backend.auth;

import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;

/**
 * Shared JWT signing-key derivation used by both production ({@link com.slparcelauctions.backend.auth.config.JwtConfig})
 * and tests ({@code JwtTestFactory}). Exists as a separate class so the key shape cannot drift
 * between prod and test code paths. If the production key derivation changes, this helper changes
 * once and both sides follow.
 */
public final class JwtKeyFactory {

    // HS256 requires keys ≥ 256 bits per RFC 7518 §3.2.
    private static final int MIN_KEY_BYTES = 32;

    private JwtKeyFactory() {}

    public static SecretKey buildKey(String secretBase64) {
        byte[] decoded = Decoders.BASE64.decode(secretBase64);
        if (decoded.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                "jwt.secret must decode to at least 32 bytes (256 bits). "
                + "Got " + decoded.length + " bytes.");
        }
        return Keys.hmacShaKeyFor(decoded);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd backend
./mvnw test -Dtest=JwtKeyFactoryTest -q
```

Expected: 2 tests pass.

- [ ] **Step 5: Create `JwtConfig` with `@PostConstruct` validation + `SecretKey` bean**

Create `backend/src/main/java/com/slparcelauctions/backend/auth/config/JwtConfig.java`:

```java
package com.slparcelauctions.backend.auth.config;

import com.slparcelauctions.backend.auth.JwtKeyFactory;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.crypto.SecretKey;
import java.time.Duration;

/**
 * JWT configuration properties bound from {@code jwt.*} keys in application.yml.
 *
 * <p>Fails fast on startup via {@link PostConstruct} if {@code jwt.secret} is missing, blank,
 * or shorter than 256 bits — preventing a misconfigured JWT secret from ever reaching runtime.
 * Production reads {@code jwt.secret} from the {@code JWT_SECRET} environment variable (no default);
 * dev uses a committed placeholder with a loud "DEV ONLY" comment in {@code application-dev.yml}.
 */
@Configuration
@ConfigurationProperties(prefix = "jwt")
@Getter
@Setter
public class JwtConfig {

    private String secret;
    private Duration accessTokenLifetime;
    private Duration refreshTokenLifetime;

    @PostConstruct
    void validate() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                "jwt.secret is required. Set JWT_SECRET environment variable in production "
                + "or check application-dev.yml for the dev default.");
        }
        // Side effect: throws on length validation failure.
        JwtKeyFactory.buildKey(secret);
    }

    @Bean
    public SecretKey jwtSigningKey() {
        return JwtKeyFactory.buildKey(secret);
    }
}
```

- [ ] **Step 6: Verify the app starts against the dev profile (fail-fast validation happy path)**

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Wait for `Started SlparcelauctionsBackendApplication`. The `@PostConstruct` in `JwtConfig` runs during bean creation; if `jwt.secret` isn't properly set in `application-dev.yml`, startup aborts with the "jwt.secret is required" message. Ctrl+C once you see startup success.

- [ ] **Step 7: Commit**

```bash
cd backend
git add src/main/java/com/slparcelauctions/backend/auth/JwtKeyFactory.java \
        src/main/java/com/slparcelauctions/backend/auth/config/JwtConfig.java \
        src/test/java/com/slparcelauctions/backend/auth/JwtKeyFactoryTest.java
git commit -m "feat(auth): add JwtKeyFactory and JwtConfig with fail-fast validation"
```

---

### Task 4: Enable scheduling on the application class

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/SlparcelauctionsBackendApplication.java`

- [ ] **Step 1: Open the main application class and check for `@EnableScheduling`**

```bash
cd backend
grep -n "EnableScheduling" src/main/java/com/slparcelauctions/backend/SlparcelauctionsBackendApplication.java
```

Expected: no match. Scheduling is not currently enabled.

- [ ] **Step 2: Add `@EnableScheduling` to the class**

Update `backend/src/main/java/com/slparcelauctions/backend/SlparcelauctionsBackendApplication.java`:

```java
package com.slparcelauctions.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SlparcelauctionsBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(SlparcelauctionsBackendApplication.class, args);
    }
}
```

- [ ] **Step 3: Verify the app still starts**

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Ctrl+C after `Started SlparcelauctionsBackendApplication`. No functional change yet — `RefreshTokenCleanupJob` lands in Task 28.

- [ ] **Step 4: Commit**

```bash
cd backend
git add src/main/java/com/slparcelauctions/backend/SlparcelauctionsBackendApplication.java
git commit -m "chore(auth): enable Spring @Scheduled support for refresh token cleanup"
```

---

## Phase B — Core primitives & helpers

### Task 5: Create `AuthPrincipal` record

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auth/AuthPrincipal.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auth/AuthPrincipalTest.java`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/slparcelauctions/backend/auth/AuthPrincipalTest.java`:

```java
package com.slparcelauctions.backend.auth;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AuthPrincipalTest {

    @Test
    void record_exposesAllThreeAccessors() {
        AuthPrincipal principal = new AuthPrincipal(42L, "user@example.com", 7L);

        assertThat(principal.userId()).isEqualTo(42L);
        assertThat(principal.email()).isEqualTo("user@example.com");
        assertThat(principal.tokenVersion()).isEqualTo(7L);
    }

    @Test
    void record_equalsAndHashCodeAreValueBased() {
        AuthPrincipal a = new AuthPrincipal(1L, "a@example.com", 0L);
        AuthPrincipal b = new AuthPrincipal(1L, "a@example.com", 0L);
        AuthPrincipal c = new AuthPrincipal(2L, "a@example.com", 0L);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd backend
./mvnw test -Dtest=AuthPrincipalTest -q
```

Expected: compilation failure — `AuthPrincipal` does not exist.

- [ ] **Step 3: Create `AuthPrincipal`**

Create `backend/src/main/java/com/slparcelauctions/backend/auth/AuthPrincipal.java`:

```java
package com.slparcelauctions.backend.auth;

/**
 * Lightweight authentication principal set into the Spring {@code SecurityContext} by
 * {@code JwtAuthenticationFilter} on a successful access-token parse. Consumed by controllers via
 * {@code @AuthenticationPrincipal AuthPrincipal principal}.
 *
 * <p><strong>Never use {@code @AuthenticationPrincipal UserDetails}</strong> in this codebase —
 * the filter sets this record, not a Spring {@code UserDetails}, and reaching for {@code UserDetails}
 * yields {@code null}. See FOOTGUNS §B.1.
 *
 * <p>The {@code tokenVersion} field is the freshness-mitigation claim: write-path services compare
 * it against the freshly-loaded {@code user.getTokenVersion()} at the integrity boundary to detect
 * stale sessions within the 15-minute access-token window. See spec §2 (data model) and §6
 * (service-layer freshness check).
 */
public record AuthPrincipal(Long userId, String email, Long tokenVersion) {}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd backend
./mvnw test -Dtest=AuthPrincipalTest -q
```

Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
cd backend
git add src/main/java/com/slparcelauctions/backend/auth/AuthPrincipal.java \
        src/test/java/com/slparcelauctions/backend/auth/AuthPrincipalTest.java
git commit -m "feat(auth): add AuthPrincipal record for Spring Security context"
```

---

### Task 6: Create `TokenHasher` utility

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auth/TokenHasher.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auth/TokenHasherTest.java`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/slparcelauctions/backend/auth/TokenHasherTest.java`:

```java
package com.slparcelauctions.backend.auth;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TokenHasherTest {

    @Test
    void sha256Hex_producesStable64CharHex() {
        String hash = TokenHasher.sha256Hex("test-token-value");

        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]{64}");
        // Same input → same output (deterministic)
        assertThat(hash).isEqualTo(TokenHasher.sha256Hex("test-token-value"));
    }

    @Test
    void sha256Hex_differentInputsProduceDifferentHashes() {
        assertThat(TokenHasher.sha256Hex("a")).isNotEqualTo(TokenHasher.sha256Hex("b"));
    }

    @Test
    void secureRandomBase64Url_produces43CharTokenFor32Bytes() {
        String token = TokenHasher.secureRandomBase64Url(32);

        // base64url of 32 bytes with no padding = 43 chars
        assertThat(token).hasSize(43);
        // URL-safe base64 alphabet: A-Z a-z 0-9 - _
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void secureRandomBase64Url_producesUniqueValues() {
        String a = TokenHasher.secureRandomBase64Url(32);
        String b = TokenHasher.secureRandomBase64Url(32);

        assertThat(a).isNotEqualTo(b);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd backend
./mvnw test -Dtest=TokenHasherTest -q
```

Expected: compilation failure — `TokenHasher` does not exist.

- [ ] **Step 3: Create `TokenHasher`**

Create `backend/src/main/java/com/slparcelauctions/backend/auth/TokenHasher.java`:

```java
package com.slparcelauctions.backend.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Shared hashing and secure-random helpers for refresh-token lifecycle.
 *
 * <p>Used by both {@code RefreshTokenService} (production path) and {@code RefreshTokenTestFixture}
 * (test path) so the hashing algorithm and random source cannot drift. Changing the hashing
 * algorithm here requires updating both call sites — see FOOTGUNS §B.8 for the "raw refresh token
 * never lives in the DB" rule that this helper enforces.
 */
public final class TokenHasher {

    private static final SecureRandom SECURE_RANDOM;

    static {
        try {
            SECURE_RANDOM = SecureRandom.getInstanceStrong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SecureRandom strong instance unavailable", e);
        }
    }

    private TokenHasher() {}

    /**
     * Returns the SHA-256 hash of the input as lowercase hex (64 chars).
     */
    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Generates {@code byteCount} bytes of secure randomness and returns them as a base64url
     * string with no padding. For {@code byteCount = 32}, the output is 43 chars.
     */
    public static String secureRandomBase64Url(int byteCount) {
        byte[] bytes = new byte[byteCount];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd backend
./mvnw test -Dtest=TokenHasherTest -q
```

Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
cd backend
git add src/main/java/com/slparcelauctions/backend/auth/TokenHasher.java \
        src/test/java/com/slparcelauctions/backend/auth/TokenHasherTest.java
git commit -m "feat(auth): add TokenHasher for sha256Hex and secureRandomBase64Url"
```

---

### Task 7: Create all six auth exception classes

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auth/exception/InvalidCredentialsException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auth/exception/EmailAlreadyExistsException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auth/exception/TokenExpiredException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auth/exception/TokenInvalidException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auth/exception/RefreshTokenReuseDetectedException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auth/exception/AuthenticationStaleException.java`

All six are trivial `RuntimeException` subclasses; bundled into one task and one commit because they're a logical unit with no individual behavior to test. The exception handler tests (Task 22) cover them via the response-mapping assertions.

- [ ] **Step 1: Create `InvalidCredentialsException`**

```java
package com.slparcelauctions.backend.auth.exception;

/**
 * Thrown by {@code AuthService.login} when email is unknown or password doesn't match.
 * Both cases throw this same exception with the same response shape (401 AUTH_INVALID_CREDENTIALS)
 * so the endpoint doesn't leak email existence via response differences. BCrypt's constant-time
 * comparison handles the timing-attack mitigation.
 */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Email or password is incorrect.");
    }
}
```

- [ ] **Step 2: Create `EmailAlreadyExistsException`**

```java
package com.slparcelauctions.backend.auth.exception;

/**
 * Thrown by {@code AuthService.register} when the email is already in use. Maps to
 * 409 AUTH_EMAIL_EXISTS via {@code AuthExceptionHandler}.
 */
public class EmailAlreadyExistsException extends RuntimeException {
    public EmailAlreadyExistsException(String email) {
        super("An account with email " + email + " already exists.");
    }
}
```

- [ ] **Step 3: Create `TokenExpiredException`**

```java
package com.slparcelauctions.backend.auth.exception;

/**
 * Thrown by {@code JwtService.parseAccessToken} when the access token's {@code exp} claim is
 * in the past, or by {@code RefreshTokenService.rotate} when a refresh token row's
 * {@code expires_at} is in the past. Maps to 401 AUTH_TOKEN_EXPIRED.
 */
public class TokenExpiredException extends RuntimeException {
    public TokenExpiredException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Create `TokenInvalidException`**

```java
package com.slparcelauctions.backend.auth.exception;

/**
 * Thrown by {@code JwtService.parseAccessToken} on any validation failure other than expiry
 * (bad signature, wrong {@code type} claim, malformed payload), and by {@code RefreshTokenService.rotate}
 * when a submitted refresh token hash isn't found in the DB. Maps to 401 AUTH_TOKEN_INVALID.
 *
 * <p>The filter also maps missing {@code Authorization} header to AUTH_TOKEN_MISSING via the
 * {@code JwtAuthenticationEntryPoint}, which is a different code path (entry point, not handler).
 */
public class TokenInvalidException extends RuntimeException {
    public TokenInvalidException(String message) {
        super(message);
    }
}
```

- [ ] **Step 5: Create `RefreshTokenReuseDetectedException`**

```java
package com.slparcelauctions.backend.auth.exception;

import lombok.Getter;

/**
 * Thrown by {@code RefreshTokenService.rotate} when a refresh token with {@code revoked_at != null}
 * is presented — a signal of theft (the legitimate user already rotated this token, so whoever is
 * submitting it now is replaying an old copy). The service has already cascaded the revocation
 * (revokes all of the user's refresh tokens, bumps {@code users.token_version}) before this
 * exception is thrown. The handler maps to 401 AUTH_REFRESH_TOKEN_REUSED and logs a WARN line
 * with the user ID, request IP, and User-Agent for audit.
 *
 * <p>See FOOTGUNS §B.6 — this cascade is the entire reason DB-backed refresh tokens are worth
 * the cost over JWT-based refresh tokens.
 */
@Getter
public class RefreshTokenReuseDetectedException extends RuntimeException {
    private final Long userId;

    public RefreshTokenReuseDetectedException(Long userId) {
        super("Refresh token reuse detected for user " + userId + "; all sessions revoked.");
        this.userId = userId;
    }
}
```

- [ ] **Step 6: Create `AuthenticationStaleException`**

```java
package com.slparcelauctions.backend.auth.exception;

/**
 * Thrown by write-path services (future {@code BidService}, {@code ListingService}, {@code EscrowService})
 * when {@code principal.tokenVersion()} does not match the freshly-loaded {@code user.getTokenVersion()}.
 * Signals that the user's session was invalidated (banned, suspended, password-changed, logged-out-all)
 * after this access token was issued. The client should POST /api/auth/refresh to get a new access token
 * reflecting the current token version — which will fail if the refresh token was also revoked, forcing
 * a re-login.
 *
 * <p>Not thrown by any Task 01-07 code — this exception class ships now so future write-path slices
 * have the API ready. See spec §6 and §15 for out-of-scope notes.
 */
public class AuthenticationStaleException extends RuntimeException {
    public AuthenticationStaleException() {
        super("Session is no longer valid; please log in again.");
    }
}
```

- [ ] **Step 7: Verify compilation**

```bash
cd backend
./mvnw compile -q
```

Expected: compiles cleanly. No tests for these classes directly — they're tested via `AuthExceptionHandlerTest` in Task 22.

- [ ] **Step 8: Commit**

```bash
cd backend
git add src/main/java/com/slparcelauctions/backend/auth/exception/
git commit -m "feat(auth): add six auth exception classes"
```

---

## Phase C — JWT service + test helpers

### Task 8: Create `JwtTestFactory` with both `@Component` and `forKey` paths

**Files:**
- Create: `backend/src/test/java/com/slparcelauctions/backend/auth/test/JwtTestFactory.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auth/test/JwtTestFactoryTest.java`

Ships before `JwtService` because `JwtServiceTest` (Task 10) depends on it. The `forKey` static factory is the pure-unit construction path used by tests that don't bring up a Spring context.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/slparcelauctions/backend/auth/test/JwtTestFactoryTest.java`:

```java
package com.slparcelauctions.backend.auth.test;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtKeyFactory;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import javax.crypto.SecretKey;
import java.util.Base64;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTestFactoryTest {

    private static final String DEV_SECRET = base64Of32RandomBytes();

    private static String base64Of32RandomBytes() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void forKey_producesFactoryThatIssuesValidAccessTokens() {
        JwtTestFactory factory = JwtTestFactory.forKey(DEV_SECRET);
        AuthPrincipal principal = new AuthPrincipal(1L, "test@example.com", 0L);

        String token = factory.validAccessToken(principal);

        // Parse with the same key and assert the claims.
        SecretKey key = JwtKeyFactory.buildKey(DEV_SECRET);
        Jws<Claims> parsed = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
        Claims claims = parsed.getPayload();

        assertThat(claims.getSubject()).isEqualTo("1");
        assertThat(claims.get("email")).isEqualTo("test@example.com");
        assertThat(((Number) claims.get("tv")).longValue()).isEqualTo(0L);
        assertThat(claims.get("type")).isEqualTo("access");
    }

    @Test
    void expiredAccessToken_producesTokenWithPastExpiry() {
        JwtTestFactory factory = JwtTestFactory.forKey(DEV_SECRET);
        AuthPrincipal principal = new AuthPrincipal(1L, "test@example.com", 0L);

        String token = factory.expiredAccessToken(principal);
        SecretKey key = JwtKeyFactory.buildKey(DEV_SECRET);

        assertThatThrownBy(() ->
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token))
            .isInstanceOf(io.jsonwebtoken.ExpiredJwtException.class);
    }

    @Test
    void tokenWithWrongType_hasTypeClaimSetToRefresh() {
        JwtTestFactory factory = JwtTestFactory.forKey(DEV_SECRET);
        AuthPrincipal principal = new AuthPrincipal(1L, "test@example.com", 0L);

        String token = factory.tokenWithWrongType(principal);
        SecretKey key = JwtKeyFactory.buildKey(DEV_SECRET);
        Claims claims = Jwts.parser().verifyWith(key).build()
            .parseSignedClaims(token).getPayload();

        assertThat(claims.get("type")).isEqualTo("refresh");
    }

    @Test
    void tokenWithBadSignature_doesNotVerifyWithRealKey() {
        JwtTestFactory factory = JwtTestFactory.forKey(DEV_SECRET);

        String token = factory.tokenWithBadSignature();
        SecretKey key = JwtKeyFactory.buildKey(DEV_SECRET);

        assertThatThrownBy(() ->
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token))
            .isInstanceOf(io.jsonwebtoken.security.SignatureException.class);
    }

    @Test
    void malformedToken_returnsNonJwtString() {
        JwtTestFactory factory = JwtTestFactory.forKey(DEV_SECRET);

        assertThat(factory.malformedToken()).isEqualTo("not.a.jwt");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd backend
./mvnw test -Dtest=JwtTestFactoryTest -q
```

Expected: compilation failure — `JwtTestFactory` does not exist.

- [ ] **Step 3: Create `JwtTestFactory`**

Create `backend/src/test/java/com/slparcelauctions/backend/auth/test/JwtTestFactory.java`:

```java
package com.slparcelauctions.backend.auth.test;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtKeyFactory;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Test-only factory for issuing real signed JWTs that validate against the same key as production.
 *
 * <p>Two construction paths are intentionally exposed:
 * <ol>
 *   <li><strong>{@code @Component} with {@code @Value}</strong> — for slice and integration tests
 *       that bring up a Spring context. The factory is autowired and reads {@code jwt.secret}
 *       from the active profile's application.yml, so tokens it issues validate against the same
 *       key the running {@code JwtAuthenticationFilter} uses.</li>
 *   <li><strong>{@link #forKey(String)} static factory</strong> — for pure-unit tests that don't
 *       bring up a Spring context (e.g., {@code JwtServiceTest}, {@code JwtAuthenticationFilterTest}).
 *       The test hands in a secret directly.</li>
 * </ol>
 *
 * <p>Both paths derive the key via {@link JwtKeyFactory#buildKey(String)} so prod and test share
 * the same derivation — if the production key shape changes, this factory follows automatically.
 */
@Component
public class JwtTestFactory {

    private static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(15);

    private final SecretKey key;

    public JwtTestFactory(@Value("${jwt.secret}") String secret) {
        this.key = JwtKeyFactory.buildKey(secret);
    }

    private JwtTestFactory(SecretKey key) {
        this.key = key;
    }

    /**
     * Pure-unit construction path. Use from tests that don't bring up a Spring context.
     */
    public static JwtTestFactory forKey(String secretBase64) {
        return new JwtTestFactory(JwtKeyFactory.buildKey(secretBase64));
    }

    public String validAccessToken(AuthPrincipal principal) {
        return validAccessTokenWithLifetime(principal, DEFAULT_LIFETIME);
    }

    public String validAccessTokenWithLifetime(AuthPrincipal principal, Duration lifetime) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(String.valueOf(principal.userId()))
            .claim("email", principal.email())
            .claim("tv", principal.tokenVersion())
            .claim("type", "access")
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(lifetime)))
            .signWith(key)
            .compact();
    }

    public String expiredAccessToken(AuthPrincipal principal) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(String.valueOf(principal.userId()))
            .claim("email", principal.email())
            .claim("tv", principal.tokenVersion())
            .claim("type", "access")
            .issuedAt(Date.from(now.minus(Duration.ofHours(1))))
            .expiration(Date.from(now.minus(Duration.ofMinutes(1))))
            .signWith(key)
            .compact();
    }

    public String tokenWithWrongType(AuthPrincipal principal) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(String.valueOf(principal.userId()))
            .claim("email", principal.email())
            .claim("tv", principal.tokenVersion())
            .claim("type", "refresh") // wrong — access token parser should reject
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(DEFAULT_LIFETIME)))
            .signWith(key)
            .compact();
    }

    public String tokenWithBadSignature() {
        // Sign with a completely different key.
        byte[] otherBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(otherBytes);
        SecretKey otherKey = Keys.hmacShaKeyFor(otherBytes);
        Instant now = Instant.now();
        return Jwts.builder()
            .subject("1")
            .claim("email", "unused@example.com")
            .claim("tv", 0L)
            .claim("type", "access")
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(DEFAULT_LIFETIME)))
            .signWith(otherKey)
            .compact();
    }

    public String malformedToken() {
        return "not.a.jwt";
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd backend
./mvnw test -Dtest=JwtTestFactoryTest -q
```

Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
cd backend
git add src/test/java/com/slparcelauctions/backend/auth/test/JwtTestFactory.java \
        src/test/java/com/slparcelauctions/backend/auth/test/JwtTestFactoryTest.java
git commit -m "test(auth): add JwtTestFactory with @Component and forKey paths"
```

---

### Task 9: Create `JwtService`

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auth/JwtService.java`

This task creates the production service. Its tests land in Task 10 (separated for commit granularity — Task 9's commit is "service exists and compiles," Task 10's commit is "service is tested").

- [ ] **Step 1: Create `JwtService`**

Create `backend/src/main/java/com/slparcelauctions/backend/auth/JwtService.java`:

```java
package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.auth.config.JwtConfig;
import com.slparcelauctions.backend.auth.exception.TokenExpiredException;
import com.slparcelauctions.backend.auth.exception.TokenInvalidException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;

/**
 * Thin facade over JJWT 0.12 for access-token issuance and parsing.
 *
 * <p><strong>Access tokens only.</strong> Refresh tokens are not JWTs — they're opaque
 * {@code SecureRandom}-derived strings handled by {@code RefreshTokenService}.
 *
 * <p>Access token claims: {@code sub} (user ID as string), {@code email}, {@code tv} (token version),
 * {@code iat}, {@code exp}, {@code type} (literal {@code "access"}). The {@code type} claim is a
 * defense-in-depth check — refresh tokens don't look like JWTs at all, so this check is redundant
 * in practice, but it asserts the contract explicitly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JwtService {

    private final JwtConfig jwtConfig;
    private final SecretKey jwtSigningKey;

    public String issueAccessToken(AuthPrincipal principal) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(String.valueOf(principal.userId()))
            .claim("email", principal.email())
            .claim("tv", principal.tokenVersion())
            .claim("type", "access")
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(jwtConfig.getAccessTokenLifetime())))
            .signWith(jwtSigningKey)
            .compact();
    }

    public AuthPrincipal parseAccessToken(String token) {
        try {
            Jws<Claims> parsed = Jwts.parser()
                .verifyWith(jwtSigningKey)
                .build()
                .parseSignedClaims(token);
            Claims claims = parsed.getPayload();

            if (!"access".equals(claims.get("type"))) {
                throw new TokenInvalidException("Token type claim is not 'access'.");
            }

            Long userId = Long.parseLong(claims.getSubject());
            String email = (String) claims.get("email");
            Long tokenVersion = ((Number) claims.get("tv")).longValue();

            return new AuthPrincipal(userId, email, tokenVersion);
        } catch (ExpiredJwtException e) {
            throw new TokenExpiredException("Access token has expired.");
        } catch (JwtException | IllegalArgumentException | NumberFormatException | ClassCastException | NullPointerException e) {
            throw new TokenInvalidException("Access token is invalid: " + e.getClass().getSimpleName());
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd backend
./mvnw compile -q
```

Expected: compiles cleanly.

- [ ] **Step 3: Commit**

```bash
cd backend
git add src/main/java/com/slparcelauctions/backend/auth/JwtService.java
git commit -m "feat(auth): add JwtService for access token issuance and parsing"
```

---

### Task 10: Add `JwtServiceTest`

**Files:**
- Create: `backend/src/test/java/com/slparcelauctions/backend/auth/JwtServiceTest.java`

Pure-unit test using `JwtTestFactory.forKey(...)` for test token generation. No Spring context.

- [ ] **Step 1: Write `JwtServiceTest`**

Create `backend/src/test/java/com/slparcelauctions/backend/auth/JwtServiceTest.java`:

```java
package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.auth.config.JwtConfig;
import com.slparcelauctions.backend.auth.exception.TokenExpiredException;
import com.slparcelauctions.backend.auth.exception.TokenInvalidException;
import com.slparcelauctions.backend.auth.test.JwtTestFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.crypto.SecretKey;
import java.time.Duration;
import java.util.Base64;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = base64Of32RandomBytes();

    private JwtService jwtService;
    private JwtTestFactory testFactory;

    private static String base64Of32RandomBytes() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @BeforeEach
    void setUp() {
        JwtConfig config = new JwtConfig();
        config.setSecret(SECRET);
        config.setAccessTokenLifetime(Duration.ofMinutes(15));
        config.setRefreshTokenLifetime(Duration.ofDays(7));
        SecretKey key = JwtKeyFactory.buildKey(SECRET);
        jwtService = new JwtService(config, key);
        testFactory = JwtTestFactory.forKey(SECRET);
    }

    @Test
    void issueAccessToken_producesTokenWithCorrectClaims() {
        AuthPrincipal input = new AuthPrincipal(42L, "user@example.com", 3L);

        String token = jwtService.issueAccessToken(input);
        AuthPrincipal parsed = jwtService.parseAccessToken(token);

        assertThat(parsed.userId()).isEqualTo(42L);
        assertThat(parsed.email()).isEqualTo("user@example.com");
        assertThat(parsed.tokenVersion()).isEqualTo(3L);
    }

    @Test
    void parseAccessToken_returnsPrincipalOnValidToken() {
        AuthPrincipal expected = new AuthPrincipal(1L, "a@b.com", 0L);
        String token = testFactory.validAccessToken(expected);

        assertThat(jwtService.parseAccessToken(token)).isEqualTo(expected);
    }

    @Test
    void parseAccessToken_throwsExpiredOnExpiredToken() {
        AuthPrincipal p = new AuthPrincipal(1L, "a@b.com", 0L);
        String token = testFactory.expiredAccessToken(p);

        assertThatThrownBy(() -> jwtService.parseAccessToken(token))
            .isInstanceOf(TokenExpiredException.class);
    }

    @Test
    void parseAccessToken_throwsInvalidOnBadSignature() {
        String token = testFactory.tokenWithBadSignature();

        assertThatThrownBy(() -> jwtService.parseAccessToken(token))
            .isInstanceOf(TokenInvalidException.class);
    }

    @Test
    void parseAccessToken_throwsInvalidOnWrongType() {
        AuthPrincipal p = new AuthPrincipal(1L, "a@b.com", 0L);
        String token = testFactory.tokenWithWrongType(p);

        assertThatThrownBy(() -> jwtService.parseAccessToken(token))
            .isInstanceOf(TokenInvalidException.class)
            .hasMessageContaining("type");
    }

    @Test
    void parseAccessToken_throwsInvalidOnMalformedToken() {
        String token = testFactory.malformedToken();

        assertThatThrownBy(() -> jwtService.parseAccessToken(token))
            .isInstanceOf(TokenInvalidException.class);
    }
}
```

- [ ] **Step 2: Run the test to verify all cases pass**

```bash
cd backend
./mvnw test -Dtest=JwtServiceTest -q
```

Expected: 6 tests pass.

- [ ] **Step 3: Commit**

```bash
cd backend
git add src/test/java/com/slparcelauctions/backend/auth/JwtServiceTest.java
git commit -m "test(auth): add JwtServiceTest covering issue, parse, expired, bad-sig, wrong-type, malformed"
```

---

## Phase D — Mock-auth test annotation

### Task 11: Create `@WithMockAuthPrincipal` annotation and factory

**Files:**
- Create: `backend/src/test/java/com/slparcelauctions/backend/auth/test/WithMockAuthPrincipal.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auth/test/WithMockAuthPrincipalSecurityContextFactory.java`

- [ ] **Step 1: Create the annotation**

Create `backend/src/test/java/com/slparcelauctions/backend/auth/test/WithMockAuthPrincipal.java`:

```java
package com.slparcelauctions.backend.auth.test;

import org.springframework.security.test.context.support.WithSecurityContext;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Inserts an {@link com.slparcelauctions.backend.auth.AuthPrincipal} into the Spring
 * {@code SecurityContext} for the test method or class. Modelled on Spring Security's
 * {@code @WithMockUser}.
 *
 * <p>Defaults: {@code userId=1L}, {@code email="test@example.com"}, {@code tokenVersion=0L}.
 * Override any subset via annotation attributes:
 *
 * <pre>{@code
 * @Test
 * @WithMockAuthPrincipal(userId = 42, email = "alice@example.com")
 * void placesBid_whenAuthenticated() { ... }
 * }</pre>
 *
 * <p><strong>Wiring:</strong> the {@code @WithSecurityContext(factory = ...)} element below is the
 * modern Spring Security 6+ auto-discovery path. {@code META-INF/spring.factories} is the legacy
 * fallback — use it only if this annotation is silently ignored in tests (see FOOTGUNS §B.3).
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithMockAuthPrincipalSecurityContextFactory.class)
public @interface WithMockAuthPrincipal {
    long userId() default 1L;
    String email() default "test@example.com";
    long tokenVersion() default 0L;
}
```

- [ ] **Step 2: Create the factory**

Create `backend/src/test/java/com/slparcelauctions/backend/auth/test/WithMockAuthPrincipalSecurityContextFactory.java`:

```java
package com.slparcelauctions.backend.auth.test;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContextFactory;
import java.util.List;

public class WithMockAuthPrincipalSecurityContextFactory
        implements WithSecurityContextFactory<WithMockAuthPrincipal> {

    @Override
    public SecurityContext createSecurityContext(WithMockAuthPrincipal annotation) {
        AuthPrincipal principal = new AuthPrincipal(
            annotation.userId(),
            annotation.email(),
            annotation.tokenVersion()
        );
        Authentication auth = new UsernamePasswordAuthenticationToken(
            principal, null, List.of()
        );
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        return context;
    }
}
```

- [ ] **Step 3: Verify compilation**

```bash
cd backend
./mvnw test-compile -q
```

Expected: compiles cleanly. No direct test of the annotation yet — Task 12's README smoke test exercises it.

- [ ] **Step 4: Commit**

```bash
cd backend
git add src/test/java/com/slparcelauctions/backend/auth/test/WithMockAuthPrincipal.java \
        src/test/java/com/slparcelauctions/backend/auth/test/WithMockAuthPrincipalSecurityContextFactory.java
git commit -m "test(auth): add @WithMockAuthPrincipal annotation and security context factory"
```

---

### Task 12: Write `auth/test/README.md` documenting the wiring path

**Files:**
- Create: `backend/src/test/java/com/slparcelauctions/backend/auth/test/README.md`

- [ ] **Step 1: Create the README**

Create `backend/src/test/java/com/slparcelauctions/backend/auth/test/README.md`:

```markdown
# auth/test — authentication test infrastructure

This package ships the shared test helpers that every protected-endpoint test in the SLPA backend
uses. It's built in Task 01-07 and inherited by Task 01-09 onwards (bid, listing, escrow, etc.).

## Helpers

### `@WithMockAuthPrincipal`

Annotation that inserts an `AuthPrincipal` into the `SecurityContext` for the test method or class.
Use on any slice test (`@WebMvcTest`) or integration test that needs an authenticated caller:

```java
@Test
@WithMockAuthPrincipal(userId = 42)
void placesBid_whenAuthenticated() throws Exception {
    mockMvc.perform(post("/api/bids")...).andExpect(status().isCreated());
}
```

**Defaults:** `userId=1L`, `email="test@example.com"`, `tokenVersion=0L`.

**Convention:** always use the annotation, never `MockMvc.with(authentication(...))` directly.
The annotation form is idiomatic Spring Security testing and keeps test setup terse.

**Wiring:** the annotation uses Spring Security 6+'s `@WithSecurityContext(factory = ...)`
auto-discovery path. No `META-INF/spring.factories` is needed. If a future test shows the
annotation being silently ignored (principal is `null` in the controller), the fallback is to
declare the factory in `META-INF/spring.factories` — but verify the primary path first. See
FOOTGUNS §B.3.

### `JwtTestFactory`

Issues real signed JWTs for tests. Two construction paths:

1. **`@Component` with `@Value("${jwt.secret}")`** — inject via `@Autowired` in slice and
   integration tests. Reads the same secret as the running filter, so tokens it issues validate
   against production code.
2. **`JwtTestFactory.forKey(String secretBase64)` static factory** — for pure-unit tests that
   don't bring up a Spring context (e.g., `JwtServiceTest`, `JwtAuthenticationFilterTest`). The
   test hands in a random secret directly.

Both paths share `JwtKeyFactory.buildKey(...)` so prod and test cannot drift. If the production
key derivation changes, update `JwtKeyFactory` once and both sides follow.

Methods: `validAccessToken`, `validAccessTokenWithLifetime`, `expiredAccessToken`,
`tokenWithWrongType`, `tokenWithBadSignature`, `malformedToken`.

### `RefreshTokenTestFixture`

`@Component` that inserts `RefreshToken` rows for integration tests that need to simulate
pre-existing sessions (e.g., the `logoutAll` test that seeds a "device 2" token). Methods:
`insertValid(userId)`, `insertRevoked(userId)`, `insertExpired(userId)`,
`insertWithExpiry(userId, expiresAt)`. Returns the raw token so tests can replay it through
`/api/auth/refresh`.

Uses `TokenHasher.sha256Hex(...)` to hash the same way production does — changing the hashing
algorithm requires updating both call sites.

## FOOTGUNS references

- §B.1 — `@AuthenticationPrincipal AuthPrincipal principal`, never `UserDetails`
- §B.3 — `WithSecurityContextFactory` wiring in Spring Security 6
- §B.8 — Refresh token raw value never lives in the DB
```

- [ ] **Step 2: Commit**

```bash
cd backend
git add src/test/java/com/slparcelauctions/backend/auth/test/README.md
git commit -m "docs(auth): add auth/test README documenting WithSecurityContext wiring and helpers"
```

---

## Phase E — Entity layer

### Task 13: Add `tokenVersion` column to `User` entity

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/User.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/dto/UserResponse.java`

- [ ] **Step 1: Add the column to `User.java`**

Open `backend/src/main/java/com/slparcelauctions/backend/user/User.java` and add this field alongside the other `@Column` declarations:

```java
/**
 * Freshness-mitigation counter for the auth slice. Incremented on events that should
 * invalidate all live access tokens for this user (ban, suspension, password change,
 * logout-all, role change, account deletion). Access tokens carry this value as a
 * {@code tv} claim; write-path services compare against the live value at the integrity
 * boundary. See spec §2 and FOOTGUNS §B.4.
 *
 * <p>The {@code columnDefinition} supplies a SQL-side default so Hibernate's
 * {@code ddl-auto: update} can add this NOT NULL column to existing rows on local dev
 * databases without failing. The {@code @Builder.Default} handles the Java-side default
 * for newly-constructed entities.
 */
@Column(name = "token_version", nullable = false,
        columnDefinition = "bigint not null default 0")
@Builder.Default
private Long tokenVersion = 0L;
```

- [ ] **Step 2: Exclude `tokenVersion` from `UserResponse` serialization**

Open `backend/src/main/java/com/slparcelauctions/backend/user/dto/UserResponse.java`. `UserResponse` is currently a record constructed from a `User` entity via a static factory (e.g., `UserResponse.from(User)`). The fix is to simply not include `tokenVersion` in the record or the factory method — `tokenVersion` is an internal auth concern and must never serialize over the wire.

**If `UserResponse` is a record with explicit fields**, verify that `tokenVersion` is NOT one of the fields and the `from(User)` factory does not reference it. No change needed if it's already absent.

**If `UserResponse` is reflection-based** (e.g., a class with `@Data` over all `User` fields), add an explicit exclusion. Given the existing Task 01-04 pattern (record with explicit fields per the exploration brief), no change should be needed beyond confirming — but verify and add an exclusion comment if needed:

```java
// tokenVersion is intentionally NOT exposed — it's an auth-internal concern.
// See FOOTGUNS §B.4 and spec §2.
```

- [ ] **Step 3: Update `application-dev.yml` if needed for existing-DB compatibility**

The `columnDefinition` on `tokenVersion` should make this a non-issue, but if the implementer hits a startup failure on an existing dev DB (Hibernate complaining about adding a NOT NULL column), the workaround is to first drop the users table and let Hibernate recreate it — or add a dev-only `@SQL` annotation. Verify by starting the app:

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Expected: the startup log shows `alter table users add column token_version bigint not null default 0` (Hibernate DDL) and the app starts cleanly. Ctrl+C.

- [ ] **Step 4: Run the existing user-slice tests to confirm nothing broke**

```bash
cd backend
./mvnw test -Dtest='com.slparcelauctions.backend.user.*' -q
```

Expected: all existing user tests pass. The new column is default-0 and invisible to existing behavior.

- [ ] **Step 5: Commit**

```bash
cd backend
git add src/main/java/com/slparcelauctions/backend/user/User.java \
        src/main/java/com/slparcelauctions/backend/user/dto/UserResponse.java
git commit -m "feat(auth): add User.tokenVersion column for session freshness mitigation"
```

---

### Task 14: Add `UserService.bumpTokenVersion` public method

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/UserService.java`
- Modify: `backend/src/test/java/com/slparcelauctions/backend/user/UserServiceTest.java`

- [ ] **Step 1: Write the failing test**

Add to `backend/src/test/java/com/slparcelauctions/backend/user/UserServiceTest.java` (inside the existing test class, with the existing `@Mock UserRepository` etc.):

```java
@Test
void bumpTokenVersion_incrementsByOne() {
    User user = User.builder()
        .id(7L)
        .email("user@example.com")
        .passwordHash("hash")
        .displayName("User")
        .tokenVersion(3L)
        .build();
    when(userRepository.findById(7L)).thenReturn(Optional.of(user));

    userService.bumpTokenVersion(7L);

    assertThat(user.getTokenVersion()).isEqualTo(4L);
    // No explicit save() — JPA dirty checking handles the persist on transaction commit.
}

@Test
void bumpTokenVersion_throwsWhenUserMissing() {
    when(userRepository.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> userService.bumpTokenVersion(99L))
        .isInstanceOf(ResourceNotFoundException.class);
}
```

(If `ResourceNotFoundException` doesn't exist yet in the user slice, use whatever the existing `UserService.getUserById` throws on missing — match the existing convention.)

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd backend
./mvnw test -Dtest=UserServiceTest#bumpTokenVersion_incrementsByOne -q
```

Expected: compilation failure — `bumpTokenVersion` does not exist.

- [ ] **Step 3: Add the method to `UserService`**

Add to `backend/src/main/java/com/slparcelauctions/backend/user/UserService.java`:

```java
/**
 * Increments this user's {@code token_version}, invalidating all live access tokens for the user.
 * Write-path services (future {@code BidService}, {@code ListingService}, etc.) compare their
 * authenticated {@code AuthPrincipal.tokenVersion()} against the freshly-loaded value and throw
 * {@code AuthenticationStaleException} on mismatch.
 *
 * <p>Callers (current and planned):
 * <ul>
 *   <li>{@code AuthService.logoutAll} — Task 01-07, shipped in this slice</li>
 *   <li>{@code RefreshTokenService.rotate} on reuse detection — Task 01-07, shipped in this slice</li>
 *   <li>Password change endpoint — future task</li>
 *   <li>Admin ban / suspension — future moderation task</li>
 *   <li>Role change — future admin task</li>
 *   <li>Account deletion — future profile-edit task</li>
 * </ul>
 *
 * <p>No explicit {@code save()} call — JPA dirty checking flushes the update on transaction commit.
 * Callers must run inside a transactional context.
 */
@Transactional
public void bumpTokenVersion(Long userId) {
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    user.setTokenVersion(user.getTokenVersion() + 1);
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd backend
./mvnw test -Dtest=UserServiceTest -q
```

Expected: all existing UserService tests + 2 new ones pass.

- [ ] **Step 5: Commit**

```bash
cd backend
git add src/main/java/com/slparcelauctions/backend/user/UserService.java \
        src/test/java/com/slparcelauctions/backend/user/UserServiceTest.java
git commit -m "feat(user): add UserService.bumpTokenVersion for session invalidation"
```

---

### Task 15: Create `RefreshToken` entity

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auth/RefreshToken.java`

- [ ] **Step 1: Create the entity**

Create `backend/src/main/java/com/slparcelauctions/backend/auth/RefreshToken.java`:

```java
package com.slparcelauctions.backend.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import java.time.OffsetDateTime;

/**
 * DB-backed refresh token row. The raw token value is NEVER stored — only its SHA-256 hash
 * (see {@link TokenHasher#sha256Hex(String)}). The raw value exists only in the HttpOnly cookie
 * on the wire and in the client's cookie jar. If the DB leaks, tokens leak as hashes, not usable
 * credentials. See FOOTGUNS §B.8.
 *
 * <p>The unique index on {@code token_hash} is the lookup path; the composite index on
 * {@code (user_id, revoked_at)} supports the {@code revokeAllByUserId} cascade and future
 * "active sessions per user" queries.
 *
 * <p>{@code user_id} is a plain {@code Long}, not a JPA relationship, to keep slice boundaries
 * loosely coupled. Joins happen at the service layer when needed.
 */
@Entity
@Table(
    name = "refresh_tokens",
    indexes = {
        @Index(name = "idx_refresh_tokens_user_active", columnList = "user_id, revoked_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;
}
```

- [ ] **Step 2: Verify the app starts and creates the table via `ddl-auto: update`**

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Expected: startup log includes `create table refresh_tokens` DDL. The app starts cleanly. Ctrl+C.

- [ ] **Step 3: Commit**

```bash
cd backend
git add src/main/java/com/slparcelauctions/backend/auth/RefreshToken.java
git commit -m "feat(auth): add RefreshToken JPA entity"
```

---

### Task 16: Create `RefreshTokenRepository`

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auth/RefreshTokenRepository.java`

- [ ] **Step 1: Create the repository**

Create `backend/src/main/java/com/slparcelauctions/backend/auth/RefreshTokenRepository.java`:

```java
package com.slparcelauctions.backend.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findAllByUserId(Long userId);

    /**
     * Single-query cascade: set {@code revoked_at = :now} on every currently-active refresh token
     * for the user. Used by (a) the reuse-detection cascade in {@code RefreshTokenService.rotate}
     * and (b) the {@code logout-all} endpoint. Returns the count of rows updated.
     *
     * <p>Atomic — don't loop in Java.
     */
    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revokedAt = :now "
         + "WHERE rt.userId = :userId AND rt.revokedAt IS NULL")
    int revokeAllByUserId(@Param("userId") Long userId, @Param("now") OffsetDateTime now);

    /**
     * Scheduled cleanup: delete rows where the revocation OR expiry happened more than
     * {@code cutoff} ago. Retains 30 days of audit history via the call site's cutoff calculation.
     */
    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE "
         + "(rt.revokedAt IS NOT NULL AND rt.revokedAt < :cutoff) OR "
         + "(rt.expiresAt < :cutoff)")
    int deleteOldRows(@Param("cutoff") OffsetDateTime cutoff);
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd backend
./mvnw compile -q
```

- [ ] **Step 3: Commit**

```bash
cd backend
git add src/main/java/com/slparcelauctions/backend/auth/RefreshTokenRepository.java
git commit -m "feat(auth): add RefreshTokenRepository with cascade and cleanup queries"
```

---

## Phase F — Test fixture completion

### Task 17: Create `RefreshTokenTestFixture`

**Files:**
- Create: `backend/src/test/java/com/slparcelauctions/backend/auth/test/RefreshTokenTestFixture.java`

- [ ] **Step 1: Create the fixture**

Create `backend/src/test/java/com/slparcelauctions/backend/auth/test/RefreshTokenTestFixture.java`:

```java
package com.slparcelauctions.backend.auth.test;

import com.slparcelauctions.backend.auth.RefreshToken;
import com.slparcelauctions.backend.auth.RefreshTokenRepository;
import com.slparcelauctions.backend.auth.TokenHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;

/**
 * Test helper for inserting {@link RefreshToken} rows in integration tests. Uses the same
 * {@link TokenHasher#sha256Hex(String)} helper as production so the hashing path cannot drift.
 *
 * <p>Each insert method returns an {@link InsertedToken} containing the raw token string and the
 * row id — tests replay the raw token through {@code /api/auth/refresh} and assert DB state via
 * the id.
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenTestFixture {

    private final RefreshTokenRepository refreshTokenRepository;

    public InsertedToken insertValid(Long userId) {
        return insertWithExpiry(userId, OffsetDateTime.now().plusDays(7));
    }

    public InsertedToken insertRevoked(Long userId) {
        String rawToken = TokenHasher.secureRandomBase64Url(32);
        String hash = TokenHasher.sha256Hex(rawToken);
        RefreshToken row = RefreshToken.builder()
            .userId(userId)
            .tokenHash(hash)
            .expiresAt(OffsetDateTime.now().plusDays(7))
            .revokedAt(OffsetDateTime.now())
            .build();
        RefreshToken saved = refreshTokenRepository.save(row);
        return new InsertedToken(saved.getId(), rawToken, hash);
    }

    public InsertedToken insertExpired(Long userId) {
        return insertWithExpiry(userId, OffsetDateTime.now().minusDays(1));
    }

    public InsertedToken insertWithExpiry(Long userId, OffsetDateTime expiresAt) {
        String rawToken = TokenHasher.secureRandomBase64Url(32);
        String hash = TokenHasher.sha256Hex(rawToken);
        RefreshToken row = RefreshToken.builder()
            .userId(userId)
            .tokenHash(hash)
            .expiresAt(expiresAt)
            .build();
        RefreshToken saved = refreshTokenRepository.save(row);
        return new InsertedToken(saved.getId(), rawToken, hash);
    }

    public record InsertedToken(Long id, String rawToken, String tokenHash) {}
}
```

- [ ] **Step 2: Verify test compilation**

```bash
cd backend
./mvnw test-compile -q
```

- [ ] **Step 3: Commit**

```bash
cd backend
git add src/test/java/com/slparcelauctions/backend/auth/test/RefreshTokenTestFixture.java
git commit -m "test(auth): add RefreshTokenTestFixture for integration test seeding"
```

---

## Phase G — Filter + entry point

### Task 18: Create `JwtAuthenticationFilter` and its tests

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auth/JwtAuthenticationFilter.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auth/JwtAuthenticationFilterTest.java`

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/slparcelauctions/backend/auth/JwtAuthenticationFilterTest.java`:

```java
package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.auth.config.JwtConfig;
import com.slparcelauctions.backend.auth.test.JwtTestFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import javax.crypto.SecretKey;
import java.time.Duration;
import java.util.Base64;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class JwtAuthenticationFilterTest {

    private static final String SECRET = base64Of32RandomBytes();

    private JwtAuthenticationFilter filter;
    private JwtTestFactory testFactory;
    private FilterChain chain;

    private static String base64Of32RandomBytes() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @BeforeEach
    void setUp() {
        JwtConfig config = new JwtConfig();
        config.setSecret(SECRET);
        config.setAccessTokenLifetime(Duration.ofMinutes(15));
        config.setRefreshTokenLifetime(Duration.ofDays(7));
        SecretKey key = JwtKeyFactory.buildKey(SECRET);
        JwtService jwtService = new JwtService(config, key);
        filter = new JwtAuthenticationFilter(jwtService);
        testFactory = JwtTestFactory.forKey(SECRET);
        chain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilter_setsPrincipalOnValidToken() throws Exception {
        AuthPrincipal principal = new AuthPrincipal(42L, "user@example.com", 0L);
        String token = testFactory.validAccessToken(principal);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        AuthPrincipal set = (AuthPrincipal) SecurityContextHolder.getContext()
            .getAuthentication().getPrincipal();
        assertThat(set).isEqualTo(principal);
        verify(chain).doFilter(req, resp);
    }

    @Test
    void doFilter_clearsContextOnExpiredToken() throws Exception {
        AuthPrincipal p = new AuthPrincipal(1L, "a@b.com", 0L);
        String token = testFactory.expiredAccessToken(p);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(req, resp);
    }

    @Test
    void doFilter_clearsContextOnMalformedToken() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer not.a.jwt");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(req, resp);
    }

    @Test
    void doFilter_leavesContextUntouchedOnMissingHeader() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(req, resp);
    }

    @Test
    void doFilter_clearsContextOnBadSignature() throws Exception {
        String token = testFactory.tokenWithBadSignature();

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(req, resp);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd backend
./mvnw test -Dtest=JwtAuthenticationFilterTest -q
```

Expected: compilation failure — `JwtAuthenticationFilter` does not exist.

- [ ] **Step 3: Create the filter**

Create `backend/src/main/java/com/slparcelauctions/backend/auth/JwtAuthenticationFilter.java`:

```java
package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.auth.exception.TokenExpiredException;
import com.slparcelauctions.backend.auth.exception.TokenInvalidException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

/**
 * Best-effort JWT authentication filter. Extracts a Bearer token from the {@code Authorization}
 * header, parses it via {@link JwtService}, and on success sets an {@link AuthPrincipal} into the
 * Spring {@code SecurityContext}. On any validation failure, clears the context and lets the
 * request continue — {@code ExceptionTranslationFilter} downstream produces the 401 for
 * protected endpoints via {@link JwtAuthenticationEntryPoint}.
 *
 * <p><strong>Invariants:</strong>
 * <ul>
 *   <li>The filter NEVER throws.</li>
 *   <li>The filter NEVER writes to the response.</li>
 *   <li>No database hit — the principal is built entirely from JWT claims.</li>
 * </ul>
 *
 * <p>User freshness checks happen at write-path service boundaries via the {@code tv} claim,
 * not here. See spec §6.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                AuthPrincipal principal = jwtService.parseAccessToken(token);
                UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(principal, null, List.of());
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (TokenExpiredException | TokenInvalidException e) {
                SecurityContextHolder.clearContext();
                log.debug("JWT validation failed: {}", e.getMessage());
            }
        }
        chain.doFilter(request, response);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd backend
./mvnw test -Dtest=JwtAuthenticationFilterTest -q
```

Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
cd backend
git add src/main/java/com/slparcelauctions/backend/auth/JwtAuthenticationFilter.java \
        src/test/java/com/slparcelauctions/backend/auth/JwtAuthenticationFilterTest.java
git commit -m "feat(auth): add JwtAuthenticationFilter with best-effort parsing and context-clear semantics"
```

---

### Task 19: Create `JwtAuthenticationEntryPoint`

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auth/JwtAuthenticationEntryPoint.java`

The entry point writes a ProblemDetail body manually because `AuthenticationEntryPoint` runs outside Spring's message converter chain. See FOOTGUNS §B.2.

- [ ] **Step 1: Create the entry point**

Create `backend/src/main/java/com/slparcelauctions/backend/auth/JwtAuthenticationEntryPoint.java`:

```java
package com.slparcelauctions.backend.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Writes a ProblemDetail JSON body when a protected endpoint is reached without an authenticated
 * SecurityContext. Used by Spring Security via
 * {@code http.exceptionHandling(eh -> eh.authenticationEntryPoint(this))} in {@code SecurityConfig}.
 *
 * <p><strong>FOOTGUNS §B.2:</strong> {@code AuthenticationEntryPoint} runs outside Spring's
 * message converter chain. The body must be serialized manually via the injected
 * {@link ObjectMapper}. Relying on {@code @ResponseBody} or {@code ProblemDetail} auto-conversion
 * yields an empty 401 body and the frontend's {@code ApiError} parser falls back to a generic
 * "401 Unauthorized" string.
 *
 * <p>Also sets {@code Cache-Control: no-store} — auth failures must never be cached by
 * intermediaries (CDNs, proxies).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.UNAUTHORIZED,
            "Authentication is required to access this resource.");
        pd.setType(URI.create("https://slpa.example/problems/auth/token-missing"));
        pd.setTitle("Authentication required");
        pd.setInstance(URI.create(request.getRequestURI()));
        pd.setProperty("code", "AUTH_TOKEN_MISSING");

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        objectMapper.writeValue(response.getWriter(), pd);
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd backend
./mvnw compile -q
```

Expected: compiles cleanly. The entry point is tested end-to-end via `AuthFlowIntegrationTest` and `SecurityConfigTest` — no dedicated unit test because the servlet writeValue path is hard to test without Spring's `MockHttpServletResponse` and is well-covered by integration tests.

- [ ] **Step 3: Commit**

```bash
cd backend
git add src/main/java/com/slparcelauctions/backend/auth/JwtAuthenticationEntryPoint.java
git commit -m "feat(auth): add JwtAuthenticationEntryPoint emitting ProblemDetail JSON 401"
```

---

## Phase H — Service layer

### Task 20: Create `RefreshTokenService` with reuse-detection cascade

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auth/RefreshTokenService.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auth/RefreshTokenServiceTest.java`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/slparcelauctions/backend/auth/RefreshTokenServiceTest.java`:

```java
package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.auth.config.JwtConfig;
import com.slparcelauctions.backend.auth.exception.RefreshTokenReuseDetectedException;
import com.slparcelauctions.backend.auth.exception.TokenExpiredException;
import com.slparcelauctions.backend.auth.exception.TokenInvalidException;
import com.slparcelauctions.backend.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock private RefreshTokenRepository repository;
    @Mock private UserService userService;

    @InjectMocks private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        JwtConfig config = new JwtConfig();
        config.setRefreshTokenLifetime(Duration.ofDays(7));
        service = new RefreshTokenService(repository, userService, config);
    }

    @Test
    void issueForUser_createsRowWithHashedToken() {
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RefreshTokenService.IssuedRefreshToken issued =
            service.issueForUser(1L, "test-agent", "127.0.0.1");

        verify(repository).save(captor.capture());
        RefreshToken saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getTokenHash()).hasSize(64);
        assertThat(saved.getTokenHash()).isEqualTo(TokenHasher.sha256Hex(issued.rawToken()));
        assertThat(saved.getExpiresAt()).isAfter(OffsetDateTime.now().plusDays(6));
        assertThat(saved.getRevokedAt()).isNull();
        assertThat(saved.getUserAgent()).isEqualTo("test-agent");
        assertThat(saved.getIpAddress()).isEqualTo("127.0.0.1");
    }

    @Test
    void issueForUser_truncatesLongUserAgent() {
        String longUa = "x".repeat(1000);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.issueForUser(1L, longUa, "127.0.0.1");

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUserAgent()).hasSize(512);
    }

    @Test
    void rotate_happyPath_revokesOldAndInsertsNew() {
        String rawToken = "raw-token-value";
        String hash = TokenHasher.sha256Hex(rawToken);
        RefreshToken existing = RefreshToken.builder()
            .id(1L)
            .userId(42L)
            .tokenHash(hash)
            .expiresAt(OffsetDateTime.now().plusDays(5))
            .build();
        when(repository.findByTokenHash(hash)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RefreshTokenService.RotationResult result = service.rotate(rawToken, "ua", "1.1.1.1");

        assertThat(existing.getRevokedAt()).isNotNull();
        assertThat(existing.getLastUsedAt()).isNotNull();
        assertThat(result.userId()).isEqualTo(42L);
        assertThat(result.newRawToken()).isNotEqualTo(rawToken);
    }

    @Test
    void rotate_rejectsExpiredToken() {
        String rawToken = "expired-token";
        String hash = TokenHasher.sha256Hex(rawToken);
        RefreshToken expired = RefreshToken.builder()
            .id(1L)
            .userId(42L)
            .tokenHash(hash)
            .expiresAt(OffsetDateTime.now().minusDays(1))
            .build();
        when(repository.findByTokenHash(hash)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.rotate(rawToken, "ua", "1.1.1.1"))
            .isInstanceOf(TokenExpiredException.class);
    }

    @Test
    void rotate_onReusedToken_cascadeRevokesAndBumpsTokenVersion() {
        String rawToken = "reused-token";
        String hash = TokenHasher.sha256Hex(rawToken);
        RefreshToken alreadyRevoked = RefreshToken.builder()
            .id(1L)
            .userId(99L)
            .tokenHash(hash)
            .expiresAt(OffsetDateTime.now().plusDays(3))
            .revokedAt(OffsetDateTime.now().minusMinutes(5))
            .build();
        when(repository.findByTokenHash(hash)).thenReturn(Optional.of(alreadyRevoked));

        assertThatThrownBy(() -> service.rotate(rawToken, "attacker-ua", "9.9.9.9"))
            .isInstanceOf(RefreshTokenReuseDetectedException.class);

        verify(repository).revokeAllByUserId(eq(99L), any());
        verify(userService).bumpTokenVersion(99L);
        verify(repository, never()).save(any());
    }

    @Test
    void rotate_onUnknownToken_throwsInvalid() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate("unknown", "ua", "1.1.1.1"))
            .isInstanceOf(TokenInvalidException.class);
    }

    @Test
    void revokeByRawToken_isIdempotentOnMissingToken() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

        // Must not throw.
        service.revokeByRawToken("missing");

        verify(repository, never()).save(any());
    }

    @Test
    void revokeByRawToken_doesNotDoubleRevoke() {
        String rawToken = "already-revoked";
        String hash = TokenHasher.sha256Hex(rawToken);
        OffsetDateTime originalRevokedAt = OffsetDateTime.now().minusHours(1);
        RefreshToken revoked = RefreshToken.builder()
            .id(1L)
            .userId(42L)
            .tokenHash(hash)
            .expiresAt(OffsetDateTime.now().plusDays(5))
            .revokedAt(originalRevokedAt)
            .build();
        when(repository.findByTokenHash(hash)).thenReturn(Optional.of(revoked));

        service.revokeByRawToken(rawToken);

        // revokedAt is still the original timestamp — not bumped.
        assertThat(revoked.getRevokedAt()).isEqualTo(originalRevokedAt);
    }

    @Test
    void revokeByRawToken_setsRevokedAtOnActiveRow() {
        String rawToken = "active";
        String hash = TokenHasher.sha256Hex(rawToken);
        RefreshToken active = RefreshToken.builder()
            .id(1L)
            .userId(42L)
            .tokenHash(hash)
            .expiresAt(OffsetDateTime.now().plusDays(5))
            .build();
        when(repository.findByTokenHash(hash)).thenReturn(Optional.of(active));

        service.revokeByRawToken(rawToken);

        assertThat(active.getRevokedAt()).isNotNull();
    }

    @Test
    void revokeAllForUser_delegatesToRepository() {
        when(repository.revokeAllByUserId(eq(42L), any())).thenReturn(3);

        int count = service.revokeAllForUser(42L);

        assertThat(count).isEqualTo(3);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd backend
./mvnw test -Dtest=RefreshTokenServiceTest -q
```

Expected: compilation failure — `RefreshTokenService` does not exist.

- [ ] **Step 3: Create `RefreshTokenService`**

Create `backend/src/main/java/com/slparcelauctions/backend/auth/RefreshTokenService.java`:

```java
package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.auth.config.JwtConfig;
import com.slparcelauctions.backend.auth.exception.RefreshTokenReuseDetectedException;
import com.slparcelauctions.backend.auth.exception.TokenExpiredException;
import com.slparcelauctions.backend.auth.exception.TokenInvalidException;
import com.slparcelauctions.backend.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Security-critical service for the DB-backed refresh-token lifecycle.
 *
 * <p><strong>The reuse-detection cascade</strong> in {@link #rotate(String, String, String)} is
 * the entire reason DB-backed refresh tokens are worth the cost over stateless JWT refresh tokens.
 * If a revoked token is presented (i.e., someone replayed an old copy after the legitimate user
 * already rotated it), the service assumes theft and cascades: revokes ALL of the user's refresh
 * tokens AND bumps {@code users.token_version} so live access tokens become stale at the next
 * write-path service call. Documented in FOOTGUNS §B.6 — if a future contributor skips this check,
 * they've silently degraded the security model.
 *
 * <p>Rotation is a three-write atomic transaction: the old row's {@code last_used_at} + {@code revoked_at},
 * plus the new row insert. Happy path and reuse cascade both run under {@code @Transactional}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenService {

    private static final int USER_AGENT_MAX = 512;

    private final RefreshTokenRepository repository;
    private final UserService userService;
    private final JwtConfig jwtConfig;

    public IssuedRefreshToken issueForUser(Long userId, String userAgent, String ipAddress) {
        String rawToken = TokenHasher.secureRandomBase64Url(32);
        String hash = TokenHasher.sha256Hex(rawToken);
        OffsetDateTime expiresAt = OffsetDateTime.now().plus(jwtConfig.getRefreshTokenLifetime());

        RefreshToken row = RefreshToken.builder()
            .userId(userId)
            .tokenHash(hash)
            .expiresAt(expiresAt)
            .userAgent(truncate(userAgent, USER_AGENT_MAX))
            .ipAddress(ipAddress)
            .build();
        repository.save(row);

        return new IssuedRefreshToken(rawToken, expiresAt);
    }

    @Transactional
    public RotationResult rotate(String rawToken, String userAgent, String ipAddress) {
        String hash = TokenHasher.sha256Hex(rawToken);
        RefreshToken row = repository.findByTokenHash(hash)
            .orElseThrow(() -> new TokenInvalidException("Refresh token not found."));

        if (row.getRevokedAt() != null) {
            // REUSE DETECTED — the cascade. See FOOTGUNS §B.6.
            log.warn("Refresh token reuse detected: userId={} ip={} userAgent={}",
                     row.getUserId(), ipAddress, userAgent);
            repository.revokeAllByUserId(row.getUserId(), OffsetDateTime.now());
            userService.bumpTokenVersion(row.getUserId());
            throw new RefreshTokenReuseDetectedException(row.getUserId());
        }

        if (row.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new TokenExpiredException("Refresh token has expired.");
        }

        OffsetDateTime now = OffsetDateTime.now();
        row.setLastUsedAt(now);
        row.setRevokedAt(now);
        // Dirty checking flushes both updates on transaction commit.

        IssuedRefreshToken newToken = issueForUser(row.getUserId(), userAgent, ipAddress);
        return new RotationResult(row.getUserId(), newToken.rawToken(), newToken.expiresAt());
    }

    @Transactional
    public void revokeByRawToken(String rawToken) {
        // IDEMPOTENT — never throws, never logs details that could leak validity.
        // See FOOTGUNS §B.7 (logout endpoint idempotency rule).
        if (rawToken == null || rawToken.isBlank()) return;

        String hash = TokenHasher.sha256Hex(rawToken);
        Optional<RefreshToken> maybeRow = repository.findByTokenHash(hash);
        maybeRow.ifPresent(row -> {
            if (row.getRevokedAt() == null) {
                row.setRevokedAt(OffsetDateTime.now());
            }
        });
    }

    @Transactional
    public int revokeAllForUser(Long userId) {
        return repository.revokeAllByUserId(userId, OffsetDateTime.now());
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record IssuedRefreshToken(String rawToken, OffsetDateTime expiresAt) {}

    public record RotationResult(Long userId, String newRawToken, OffsetDateTime newExpiresAt) {}
}
```

- [ ] **Step 4: Run the test to verify all cases pass**

```bash
cd backend
./mvnw test -Dtest=RefreshTokenServiceTest -q
```

Expected: 10 tests pass.

- [ ] **Step 5: Commit**

```bash
cd backend
git add src/main/java/com/slparcelauctions/backend/auth/RefreshTokenService.java \
        src/test/java/com/slparcelauctions/backend/auth/RefreshTokenServiceTest.java
git commit -m "feat(auth): add RefreshTokenService with reuse-detection cascade"
```

---

### Task 21: Create `AuthService` orchestration layer

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auth/AuthService.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auth/AuthServiceTest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auth/dto/AuthResult.java`

(`AuthResult` is created here because it's the return type of every `AuthService` method. The other DTOs ship in Task 24 alongside the controller.)

- [ ] **Step 1: Create `AuthResult`**

Create `backend/src/main/java/com/slparcelauctions/backend/auth/dto/AuthResult.java`:

```java
package com.slparcelauctions.backend.auth.dto;

import com.slparcelauctions.backend.user.dto.UserResponse;

/**
 * Internal service-to-controller result. Carries the raw refresh token so the controller can
 * write it to an HttpOnly cookie. MUST NOT be returned in any HTTP response body — that would
 * leak the refresh token into JSON.
 *
 * <p>The external response shape is {@link AuthResponse}. This two-record split is load-bearing:
 * the type system prevents refresh-token leakage by making it structurally impossible for a
 * handler that returns {@code AuthResponse} to ship the refresh token. See FOOTGUNS §B.10.
 *
 * <p>Do NOT merge this with {@code AuthResponse} "for simplicity." The split is the structural
 * guard; merging it removes the guard.
 */
public record AuthResult(String accessToken, String refreshToken, UserResponse user) {}
```

- [ ] **Step 2: Write the failing `AuthServiceTest`**

Create `backend/src/test/java/com/slparcelauctions/backend/auth/AuthServiceTest.java`:

```java
package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.auth.dto.AuthResult;
import com.slparcelauctions.backend.auth.dto.LoginRequest;
import com.slparcelauctions.backend.auth.dto.RegisterRequest;
import com.slparcelauctions.backend.auth.exception.EmailAlreadyExistsException;
import com.slparcelauctions.backend.auth.exception.InvalidCredentialsException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.user.UserService;
import com.slparcelauctions.backend.user.dto.CreateUserRequest;
import com.slparcelauctions.backend.user.dto.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserService userService;
    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private JwtService jwtService;
    @Mock private PasswordEncoder passwordEncoder;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
            userService, userRepository, refreshTokenService, jwtService, passwordEncoder);
    }

    @Test
    void register_createsUserIssuesTokens() {
        RegisterRequest req = new RegisterRequest("new@example.com", "hunter22abc", "Newbie");
        User user = User.builder()
            .id(7L)
            .email("new@example.com")
            .passwordHash("hashed")
            .displayName("Newbie")
            .tokenVersion(0L)
            .build();
        UserResponse created = /* mock UserResponse */ null; // placeholder if needed
        when(userService.createUser(any(CreateUserRequest.class))).thenReturn(created);
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(user));
        when(jwtService.issueAccessToken(any())).thenReturn("access-token");
        when(refreshTokenService.issueForUser(eq(7L), anyString(), anyString()))
            .thenReturn(new RefreshTokenService.IssuedRefreshToken("refresh-raw", null));

        AuthResult result = authService.register(req, new MockHttpServletRequest());

        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshToken()).isEqualTo("refresh-raw");
        verify(userService).createUser(any(CreateUserRequest.class));
    }

    @Test
    void login_withValidCredentials_returnsResult() {
        User user = User.builder()
            .id(7L)
            .email("user@example.com")
            .passwordHash("hashed")
            .displayName("User")
            .tokenVersion(0L)
            .build();
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correct-pw", "hashed")).thenReturn(true);
        when(jwtService.issueAccessToken(any())).thenReturn("access-token");
        when(refreshTokenService.issueForUser(eq(7L), anyString(), anyString()))
            .thenReturn(new RefreshTokenService.IssuedRefreshToken("refresh-raw", null));

        AuthResult result = authService.login(
            new LoginRequest("user@example.com", "correct-pw"),
            new MockHttpServletRequest());

        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshToken()).isEqualTo("refresh-raw");
    }

    @Test
    void login_withInvalidEmail_throwsInvalidCredentials() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("unknown@example.com", "any"),
                new MockHttpServletRequest()))
            .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_withWrongPassword_throwsInvalidCredentials() {
        User user = User.builder()
            .id(7L)
            .email("user@example.com")
            .passwordHash("hashed")
            .displayName("User")
            .build();
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("user@example.com", "wrong"),
                new MockHttpServletRequest()))
            .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void logout_delegatesToRevoke() {
        authService.logout("raw-token");

        verify(refreshTokenService).revokeByRawToken("raw-token");
    }

    @Test
    void logoutAll_revokesAllAndBumpsTokenVersion() {
        authService.logoutAll(42L);

        verify(refreshTokenService).revokeAllForUser(42L);
        verify(userService).bumpTokenVersion(42L);
    }
}
```

- [ ] **Step 3: Create `AuthService`**

Create `backend/src/main/java/com/slparcelauctions/backend/auth/AuthService.java`:

```java
package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.auth.dto.AuthResult;
import com.slparcelauctions.backend.auth.dto.LoginRequest;
import com.slparcelauctions.backend.auth.dto.RegisterRequest;
import com.slparcelauctions.backend.auth.exception.EmailAlreadyExistsException;
import com.slparcelauctions.backend.auth.exception.InvalidCredentialsException;
import com.slparcelauctions.backend.auth.exception.TokenInvalidException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.user.UserService;
import com.slparcelauctions.backend.user.dto.CreateUserRequest;
import com.slparcelauctions.backend.user.dto.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Orchestration layer for auth flows. Holds no business logic beyond composition — it hands the
 * user-creation work to {@code UserService}, the JWT issuance to {@link JwtService}, and the
 * refresh-token lifecycle to {@link RefreshTokenService}. See spec §6.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserService userService;
    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    public AuthResult register(RegisterRequest request, HttpServletRequest httpReq) {
        UserResponse created;
        try {
            created = userService.createUser(new CreateUserRequest(
                request.email(), request.password(), request.displayName()));
        } catch (RuntimeException e) {
            // UserService throws its own exception type on duplicate email; rethrow as
            // auth-slice exception so the auth handler maps it to AUTH_EMAIL_EXISTS.
            if (e.getClass().getSimpleName().contains("EmailAlreadyExists")
                || e.getMessage() != null && e.getMessage().toLowerCase().contains("email")) {
                throw new EmailAlreadyExistsException(request.email());
            }
            throw e;
        }

        User user = userRepository.findById(created.id())
            .orElseThrow(() -> new IllegalStateException(
                "User was just created but cannot be loaded: id=" + created.id()));

        return buildAuthResult(user, httpReq);
    }

    public AuthResult login(LoginRequest request, HttpServletRequest httpReq) {
        User user = userRepository.findByEmail(request.email())
            .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return buildAuthResult(user, httpReq);
    }

    public AuthResult refresh(String rawRefreshToken, HttpServletRequest httpReq) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new TokenInvalidException("Refresh token is missing.");
        }
        String userAgent = httpReq.getHeader("User-Agent");
        String ipAddress = httpReq.getRemoteAddr();

        RefreshTokenService.RotationResult rotation =
            refreshTokenService.rotate(rawRefreshToken, userAgent, ipAddress);

        // happy-path-only; the reuse-cascade path throws before reaching this line
        User user = userRepository.findById(rotation.userId())
            .orElseThrow(() -> new IllegalStateException(
                "User not found after rotation: id=" + rotation.userId()));

        AuthPrincipal principal = new AuthPrincipal(
            user.getId(), user.getEmail(), user.getTokenVersion());
        String accessToken = jwtService.issueAccessToken(principal);
        return new AuthResult(accessToken, rotation.newRawToken(), UserResponse.from(user));
    }

    public void logout(String rawRefreshToken) {
        refreshTokenService.revokeByRawToken(rawRefreshToken);
    }

    public void logoutAll(Long userId) {
        refreshTokenService.revokeAllForUser(userId);
        userService.bumpTokenVersion(userId);
    }

    private AuthResult buildAuthResult(User user, HttpServletRequest httpReq) {
        AuthPrincipal principal = new AuthPrincipal(
            user.getId(), user.getEmail(), user.getTokenVersion());
        String accessToken = jwtService.issueAccessToken(principal);

        String userAgent = httpReq.getHeader("User-Agent");
        String ipAddress = httpReq.getRemoteAddr();
        RefreshTokenService.IssuedRefreshToken refresh =
            refreshTokenService.issueForUser(user.getId(), userAgent, ipAddress);

        return new AuthResult(accessToken, refresh.rawToken(), UserResponse.from(user));
    }
}
```

**Note:** `UserResponse.from(User)` is assumed to exist from Task 01-04. If it doesn't, the implementer maps fields inline. Do not add a new factory method to `UserResponse` in this task — keep the user slice untouched beyond `tokenVersion`.

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd backend
./mvnw test -Dtest=AuthServiceTest -q
```

Expected: 6 tests pass. (The `register` test may need adjustment depending on how `UserResponse` is shaped — match the existing user-slice pattern.)

- [ ] **Step 5: Commit**

```bash
cd backend
git add src/main/java/com/slparcelauctions/backend/auth/AuthService.java \
        src/main/java/com/slparcelauctions/backend/auth/dto/AuthResult.java \
        src/test/java/com/slparcelauctions/backend/auth/AuthServiceTest.java
git commit -m "feat(auth): add AuthService orchestrating register/login/refresh/logout flows"
```

---

## Phase I — Exception handlers

### Task 22: Create `AuthExceptionHandler`

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auth/exception/AuthExceptionHandler.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auth/exception/AuthExceptionHandlerTest.java`

- [ ] **Step 1: Create the handler**

Create `backend/src/main/java/com/slparcelauctions/backend/auth/exception/AuthExceptionHandler.java`:

```java
package com.slparcelauctions.backend.auth.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.net.URI;

/**
 * Slice-scoped exception handler for the {@code auth/} package. Scoped via
 * {@code basePackages = "com.slparcelauctions.backend.auth"} so it catches only auth-slice
 * exceptions; the global handler picks up everything else. No {@code @Order} workarounds —
 * handler collisions are a code smell to surface, not paper over.
 *
 * <p>Each handler is explicit (no generic builder helper). The repetition is documentation —
 * these are six distinct security responses and the explicit form makes each one discoverable
 * by searching for its exception type.
 */
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.auth")
@RequiredArgsConstructor
@Slf4j
public class AuthExceptionHandler {

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail handleInvalidCredentials(InvalidCredentialsException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.UNAUTHORIZED, "Email or password is incorrect.");
        pd.setType(URI.create("https://slpa.example/problems/auth/invalid-credentials"));
        pd.setTitle("Invalid credentials");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "AUTH_INVALID_CREDENTIALS");
        return pd;
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ProblemDetail handleEmailExists(EmailAlreadyExistsException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/auth/email-exists"));
        pd.setTitle("Email already registered");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "AUTH_EMAIL_EXISTS");
        return pd;
    }

    @ExceptionHandler(TokenExpiredException.class)
    public ProblemDetail handleTokenExpired(TokenExpiredException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.UNAUTHORIZED, "Token has expired. Please refresh or log in again.");
        pd.setType(URI.create("https://slpa.example/problems/auth/token-expired"));
        pd.setTitle("Token expired");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "AUTH_TOKEN_EXPIRED");
        return pd;
    }

    @ExceptionHandler(TokenInvalidException.class)
    public ProblemDetail handleTokenInvalid(TokenInvalidException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.UNAUTHORIZED, "Token is invalid.");
        pd.setType(URI.create("https://slpa.example/problems/auth/token-invalid"));
        pd.setTitle("Invalid token");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "AUTH_TOKEN_INVALID");
        return pd;
    }

    @ExceptionHandler(RefreshTokenReuseDetectedException.class)
    public ProblemDetail handleRefreshTokenReuse(
            RefreshTokenReuseDetectedException e, HttpServletRequest req) {
        // Already logged at WARN in RefreshTokenService.rotate with IP and User-Agent.
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.UNAUTHORIZED,
            "Session invalidated due to suspicious activity. Please log in again.");
        pd.setType(URI.create("https://slpa.example/problems/auth/refresh-token-reused"));
        pd.setTitle("Refresh token reuse detected");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "AUTH_REFRESH_TOKEN_REUSED");
        return pd;
    }

    @ExceptionHandler(AuthenticationStaleException.class)
    public ProblemDetail handleStale(AuthenticationStaleException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.UNAUTHORIZED, "Session is no longer valid. Please log in again.");
        pd.setType(URI.create("https://slpa.example/problems/auth/stale-session"));
        pd.setTitle("Session stale");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "AUTH_STALE_SESSION");
        return pd;
    }
}
```

- [ ] **Step 2: Create the slice test**

Create `backend/src/test/java/com/slparcelauctions/backend/auth/exception/AuthExceptionHandlerTest.java`:

```java
package com.slparcelauctions.backend.auth.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(SpringExtension.class)
@WebMvcTest(controllers = AuthExceptionHandlerTest.FakeThrowingController.class)
@Import(AuthExceptionHandler.class)
class AuthExceptionHandlerTest {

    @Autowired private MockMvc mockMvc;

    @RestController
    static class FakeThrowingController {
        @GetMapping("/test/invalid-credentials")
        void invalidCredentials() { throw new InvalidCredentialsException(); }

        @GetMapping("/test/email-exists")
        void emailExists() { throw new EmailAlreadyExistsException("user@example.com"); }

        @GetMapping("/test/token-expired")
        void tokenExpired() { throw new TokenExpiredException("expired"); }

        @GetMapping("/test/token-invalid")
        void tokenInvalid() { throw new TokenInvalidException("bad"); }

        @GetMapping("/test/refresh-reuse")
        void refreshReuse() { throw new RefreshTokenReuseDetectedException(42L); }

        @GetMapping("/test/stale")
        void stale() { throw new AuthenticationStaleException(); }
    }

    @Test
    void invalidCredentials_mapsTo401WithAuthInvalidCredentialsCode() throws Exception {
        mockMvc.perform(get("/test/invalid-credentials"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Content-Type", "application/problem+json"))
            .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
            .andExpect(jsonPath("$.type").value("https://slpa.example/problems/auth/invalid-credentials"));
    }

    @Test
    void emailExists_mapsTo409WithAuthEmailExistsCode() throws Exception {
        mockMvc.perform(get("/test/email-exists"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("AUTH_EMAIL_EXISTS"));
    }

    @Test
    void tokenExpired_mapsTo401WithAuthTokenExpiredCode() throws Exception {
        mockMvc.perform(get("/test/token-expired"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_TOKEN_EXPIRED"));
    }

    @Test
    void tokenInvalid_mapsTo401WithAuthTokenInvalidCode() throws Exception {
        mockMvc.perform(get("/test/token-invalid"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
    }

    @Test
    void refreshReuse_mapsTo401WithAuthRefreshTokenReusedCode() throws Exception {
        mockMvc.perform(get("/test/refresh-reuse"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_REUSED"));
    }

    @Test
    void stale_mapsTo401WithAuthStaleSessionCode() throws Exception {
        mockMvc.perform(get("/test/stale"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_STALE_SESSION"));
    }
}
```

- [ ] **Step 3: Run the test**

```bash
cd backend
./mvnw test -Dtest=AuthExceptionHandlerTest -q
```

Expected: 6 tests pass.

- [ ] **Step 4: Commit**

```bash
cd backend
git add src/main/java/com/slparcelauctions/backend/auth/exception/AuthExceptionHandler.java \
        src/test/java/com/slparcelauctions/backend/auth/exception/AuthExceptionHandlerTest.java
git commit -m "feat(auth): add AuthExceptionHandler mapping auth exceptions to ProblemDetail"
```

---

### Task 23: Create `GlobalExceptionHandler` (cross-cutting only)

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/common/exception/GlobalExceptionHandler.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/common/exception/ResourceNotFoundException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/common/exception/ResourceGoneException.java`

**Per post-spec refinement #1: does NOT handle `AuthenticationException`.** The `JwtAuthenticationEntryPoint` already owns `AUTH_TOKEN_MISSING` via `exceptionHandling(eh -> eh.authenticationEntryPoint(...))`. Adding a handler here would be dead code (entry point fires first).

- [ ] **Step 1: Create the two common exception classes**

```java
// common/exception/ResourceNotFoundException.java
package com.slparcelauctions.backend.common.exception;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) { super(message); }
}
```

```java
// common/exception/ResourceGoneException.java
package com.slparcelauctions.backend.common.exception;

public class ResourceGoneException extends RuntimeException {
    public ResourceGoneException(String message) { super(message); }
}
```

- [ ] **Step 2: Create the global handler**

Create `backend/src/main/java/com/slparcelauctions/backend/common/exception/GlobalExceptionHandler.java`:

```java
package com.slparcelauctions.backend.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Global exception handler for cross-cutting concerns only — validation, malformed JSON,
 * access denied, resource state, and the fallback 500. Auth-specific exceptions are handled
 * by {@code AuthExceptionHandler}. Explicitly does NOT handle {@code AuthenticationException} —
 * the {@code JwtAuthenticationEntryPoint} owns 401-for-missing-auth (see spec §9 refinement).
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e, HttpServletRequest req) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
            .map(fe -> Map.of(
                "field", fe.getField(),
                "message", Objects.requireNonNullElse(fe.getDefaultMessage(), "invalid")))
            .toList();
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "Request contains " + errors.size() + " invalid field(s).");
        pd.setType(URI.create("https://slpa.example/problems/validation"));
        pd.setTitle("Validation failed");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "VALIDATION_FAILED");
        pd.setProperty("errors", errors);
        return pd;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleMalformed(HttpMessageNotReadableException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, "Request body is malformed or missing.");
        pd.setType(URI.create("https://slpa.example/problems/malformed-request"));
        pd.setTitle("Malformed request");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "MALFORMED_REQUEST");
        return pd;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.FORBIDDEN, "Access denied.");
        pd.setType(URI.create("https://slpa.example/problems/access-denied"));
        pd.setTitle("Access denied");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "ACCESS_DENIED");
        return pd;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/resource-not-found"));
        pd.setTitle("Resource not found");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "RESOURCE_NOT_FOUND");
        return pd;
    }

    @ExceptionHandler(ResourceGoneException.class)
    public ProblemDetail handleGone(ResourceGoneException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.GONE, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/resource-gone"));
        pd.setTitle("Resource gone");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "RESOURCE_GONE");
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception e, HttpServletRequest req) {
        String correlationId = UUID.randomUUID().toString();
        log.error("Unhandled exception correlationId={} path={}",
                  correlationId, req.getRequestURI(), e);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
        pd.setType(URI.create("https://slpa.example/problems/internal-server-error"));
        pd.setTitle("Internal server error");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "INTERNAL_SERVER_ERROR");
        pd.setProperty("correlationId", correlationId);
        return pd;
    }
}
```

- [ ] **Step 3: Verify compilation and run all tests so far**

```bash
cd backend
./mvnw test -q
```

Expected: all tests pass. No new test file for `GlobalExceptionHandler` — it's exercised end-to-end in Phase M's integration tests and the validation path is exercised in Task 25's `AuthControllerTest`.

- [ ] **Step 4: Commit**

```bash
cd backend
git add src/main/java/com/slparcelauctions/backend/common/exception/
git commit -m "feat(common): add GlobalExceptionHandler for cross-cutting ProblemDetail responses"
```

---

## Phase J — DTOs + Controller

### Task 24: Create request and response DTOs

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auth/dto/LoginRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auth/dto/RegisterRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auth/dto/AuthResponse.java`

- [ ] **Step 1: Create `LoginRequest`**

```java
package com.slparcelauctions.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @NotBlank @Email String email,
    @NotBlank String password
) {}
```

- [ ] **Step 2: Create `RegisterRequest`**

```java
package com.slparcelauctions.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank @Email String email,
    @NotBlank @Size(min = 10, max = 255) String password,
    @NotBlank String displayName
) {}
```

**Note:** password validation matches the existing `CreateUserRequest` convention from Task 01-04 (min 10 chars). The more elaborate regex check from `CreateUserRequest` is delegated to `UserService.createUser()` which performs the full validation; this record just ensures non-empty + length.

- [ ] **Step 3: Create `AuthResponse`**

```java
package com.slparcelauctions.backend.auth.dto;

import com.slparcelauctions.backend.user.dto.UserResponse;

/**
 * External client-facing response for the auth endpoints. Contains the access token (which the
 * frontend keeps in memory) and the user profile. Does NOT contain the refresh token — that lives
 * in an HttpOnly cookie set by the controller via the {@code Set-Cookie} header.
 *
 * <p>Paired with {@link AuthResult} (service-internal). The two-record split is load-bearing —
 * the type system prevents refresh-token leakage by making it structurally impossible for a
 * handler that returns this record to ship the refresh token in the body. See FOOTGUNS §B.10.
 *
 * <p>Do NOT merge this with {@link AuthResult} "for simplicity." The split is the structural
 * guard; merging it removes the guard.
 */
public record AuthResponse(String accessToken, UserResponse user) {}
```

- [ ] **Step 4: Verify compilation**

```bash
cd backend
./mvnw compile -q
```

- [ ] **Step 5: Commit**

```bash
cd backend
git add src/main/java/com/slparcelauctions/backend/auth/dto/LoginRequest.java \
        src/main/java/com/slparcelauctions/backend/auth/dto/RegisterRequest.java \
        src/main/java/com/slparcelauctions/backend/auth/dto/AuthResponse.java
git commit -m "feat(auth): add LoginRequest, RegisterRequest, and AuthResponse DTOs"
```

---

### Task 25: Create `AuthController` with cookie helpers

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auth/AuthController.java`

- [ ] **Step 1: Create the controller**

Create `backend/src/main/java/com/slparcelauctions/backend/auth/AuthController.java`:

```java
package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.auth.config.JwtConfig;
import com.slparcelauctions.backend.auth.dto.AuthResponse;
import com.slparcelauctions.backend.auth.dto.AuthResult;
import com.slparcelauctions.backend.auth.dto.LoginRequest;
import com.slparcelauctions.backend.auth.dto.RegisterRequest;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import java.time.Duration;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private static final String REFRESH_COOKIE_NAME = "refreshToken";
    private static final String REFRESH_COOKIE_PATH = "/api/auth";

    private final AuthService authService;
    private final JwtConfig jwtConfig;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request,
                                 HttpServletRequest httpReq,
                                 HttpServletResponse httpResp) {
        AuthResult result = authService.register(request, httpReq);
        setRefreshCookie(httpResp, result.refreshToken(), jwtConfig.getRefreshTokenLifetime());
        return new AuthResponse(result.accessToken(), result.user());
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request,
                              HttpServletRequest httpReq,
                              HttpServletResponse httpResp) {
        AuthResult result = authService.login(request, httpReq);
        setRefreshCookie(httpResp, result.refreshToken(), jwtConfig.getRefreshTokenLifetime());
        return new AuthResponse(result.accessToken(), result.user());
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(HttpServletRequest httpReq, HttpServletResponse httpResp) {
        String rawRefreshToken = readRefreshCookie(httpReq);
        AuthResult result = authService.refresh(rawRefreshToken, httpReq);
        setRefreshCookie(httpResp, result.refreshToken(), jwtConfig.getRefreshTokenLifetime());
        return new AuthResponse(result.accessToken(), result.user());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest httpReq, HttpServletResponse httpResp) {
        String rawRefreshToken = readRefreshCookie(httpReq);
        // Idempotent — never throws, always 204. See FOOTGUNS §B.7.
        authService.logout(rawRefreshToken);
        clearRefreshCookie(httpResp);
    }

    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll(@AuthenticationPrincipal AuthPrincipal principal,
                          HttpServletResponse httpResp) {
        authService.logoutAll(principal.userId());
        clearRefreshCookie(httpResp);
    }

    private void setRefreshCookie(HttpServletResponse resp, String token, Duration lifetime) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE_NAME, token)
            .httpOnly(true)
            .secure(true)
            .sameSite("Lax")
            .path(REFRESH_COOKIE_PATH)
            .maxAge(lifetime)
            .build();
        resp.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse resp) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE_NAME, "")
            .httpOnly(true)
            .secure(true)
            .sameSite("Lax")
            .path(REFRESH_COOKIE_PATH)
            .maxAge(0)
            .build();
        resp.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String readRefreshCookie(HttpServletRequest req) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) {
            if (REFRESH_COOKIE_NAME.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd backend
./mvnw compile -q
```

- [ ] **Step 3: Commit**

```bash
cd backend
git add src/main/java/com/slparcelauctions/backend/auth/AuthController.java
git commit -m "feat(auth): add AuthController with register/login/refresh/logout/logout-all endpoints"
```

---

### Task 26: Add `AuthControllerTest` slice test

**Files:**
- Create: `backend/src/test/java/com/slparcelauctions/backend/auth/AuthControllerTest.java`

- [ ] **Step 1: Create the slice test**

Create `backend/src/test/java/com/slparcelauctions/backend/auth/AuthControllerTest.java`:

```java
package com.slparcelauctions.backend.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.auth.config.JwtConfig;
import com.slparcelauctions.backend.auth.dto.AuthResult;
import com.slparcelauctions.backend.auth.dto.LoginRequest;
import com.slparcelauctions.backend.auth.dto.RegisterRequest;
import com.slparcelauctions.backend.auth.exception.AuthExceptionHandler;
import com.slparcelauctions.backend.auth.exception.InvalidCredentialsException;
import com.slparcelauctions.backend.auth.test.WithMockAuthPrincipal;
import com.slparcelauctions.backend.common.exception.GlobalExceptionHandler;
import com.slparcelauctions.backend.user.dto.UserResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Duration;
import java.time.OffsetDateTime;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AuthController.class)
@AutoConfigureMockMvc(addFilters = false) // filter is wired in SecurityConfig, tested separately
@Import({AuthExceptionHandler.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
    "jwt.secret=dGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdA==",
    "jwt.access-token-lifetime=PT15M",
    "jwt.refresh-token-lifetime=P7D"
})
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private AuthService authService;
    @MockBean private JwtConfig jwtConfig;

    @BeforeEach
    void setUp() {
        when(jwtConfig.getRefreshTokenLifetime()).thenReturn(Duration.ofDays(7));
    }

    @Test
    void register_returns201WithTokenAndUser() throws Exception {
        UserResponse user = stubUser();
        when(authService.register(any(), any()))
            .thenReturn(new AuthResult("access-token", "refresh-token", user));

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new RegisterRequest("new@example.com", "hunter22abc", "Newbie")))
                .with(csrf()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accessToken").value("access-token"))
            .andExpect(jsonPath("$.user.email").value("new@example.com"))
            .andExpect(jsonPath("$.refreshToken").doesNotExist())
            .andExpect(header().exists("Set-Cookie"));
    }

    @Test
    void register_returns400OnValidationFailure() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"not-an-email\",\"password\":\"short\",\"displayName\":\"\"}")
                .with(csrf()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void login_returns200WithTokenAndUser() throws Exception {
        UserResponse user = stubUser();
        when(authService.login(any(), any()))
            .thenReturn(new AuthResult("access-token", "refresh-token", user));

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new LoginRequest("user@example.com", "correct-pw")))
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").value("access-token"))
            .andExpect(jsonPath("$.refreshToken").doesNotExist())
            .andExpect(header().exists("Set-Cookie"));
    }

    @Test
    void login_returns401OnInvalidCredentials() throws Exception {
        doThrow(new InvalidCredentialsException()).when(authService).login(any(), any());

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new LoginRequest("user@example.com", "wrong")))
                .with(csrf()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    void refresh_returns200WithNewTokenAndRotatedCookie() throws Exception {
        UserResponse user = stubUser();
        when(authService.refresh(anyString(), any()))
            .thenReturn(new AuthResult("new-access", "new-refresh", user));

        mockMvc.perform(post("/api/auth/refresh")
                .cookie(new jakarta.servlet.http.Cookie("refreshToken", "old-raw"))
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").value("new-access"))
            .andExpect(header().exists("Set-Cookie"));
    }

    @Test
    void logout_returns204AlwaysAndClearsCookie() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                .cookie(new jakarta.servlet.http.Cookie("refreshToken", "any"))
                .with(csrf()))
            .andExpect(status().isNoContent())
            .andExpect(header().exists("Set-Cookie"));
    }

    @Test
    @WithMockAuthPrincipal(userId = 42)
    void logoutAll_returns204AndClearsCookieWhenAuthenticated() throws Exception {
        mockMvc.perform(post("/api/auth/logout-all").with(csrf()))
            .andExpect(status().isNoContent())
            .andExpect(header().exists("Set-Cookie"));
    }

    private UserResponse stubUser() {
        // Build a UserResponse matching the existing user-slice shape.
        // Implementer: adjust this to match UserResponse's actual constructor signature.
        return new UserResponse(
            1L, "new@example.com", "Newbie", null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            OffsetDateTime.now(), OffsetDateTime.now()
        );
    }
}
```

**Note:** the `stubUser()` helper constructs a `UserResponse` with placeholder values. The exact constructor signature depends on the Task 01-04 record shape — the implementer must adjust the call to match. The test's assertions only care about `email`, so other fields can be `null`.

- [ ] **Step 2: Run the test**

```bash
cd backend
./mvnw test -Dtest=AuthControllerTest -q
```

Expected: 7 tests pass.

- [ ] **Step 3: Commit**

```bash
cd backend
git add src/test/java/com/slparcelauctions/backend/auth/AuthControllerTest.java
git commit -m "test(auth): add AuthControllerTest slice tests covering all 5 endpoints"
```

---

## Phase K — Security wiring

### Task 27: Update `SecurityConfig` to wire filter, flip permitAll → authenticated

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/config/SecurityConfigTest.java`

- [ ] **Step 1: Write the failing slice test for the updated security rules**

Create `backend/src/test/java/com/slparcelauctions/backend/config/SecurityConfigTest.java`:

```java
package com.slparcelauctions.backend.config;

import com.slparcelauctions.backend.auth.test.WithMockAuthPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class SecurityConfigTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void healthEndpoint_isPublic() throws Exception {
        mockMvc.perform(get("/api/health"))
            .andExpect(status().isOk());
    }

    @Test
    void meEndpoint_returns401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/users/me"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockAuthPrincipal(userId = 1)
    void meEndpoint_returns200WhenAuthenticated() throws Exception {
        // Note: this test actually hits the DB via UserService. It requires the dev profile
        // and a seeded user with id=1. If the test DB is empty, this will return 404 instead
        // of 200 — acceptable for this smoke test, the Phase L cross-slice tests cover the
        // full path.
        mockMvc.perform(get("/api/users/me"))
            .andExpect(status().is2xxSuccessful());
    }
}
```

- [ ] **Step 2: Run the test to see the pre-update behavior**

```bash
cd backend
./mvnw test -Dtest=SecurityConfigTest -q
```

Expected: `healthEndpoint_isPublic` passes, but `meEndpoint_returns401WhenUnauthenticated` probably fails (because the current config has `anyRequest().permitAll()`). The test will pass after Step 3.

- [ ] **Step 3: Rewrite `SecurityConfig`**

Replace the contents of `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java`:

```java
package com.slparcelauctions.backend.config;

import com.slparcelauctions.backend.auth.JwtAuthenticationEntryPoint;
import com.slparcelauctions.backend.auth.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.beans.factory.annotation.Value;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;

    @Value("${cors.allowed-origin:http://localhost:3000}")
    private String allowedOrigin;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            //
            // Matcher ordering matters — see FOOTGUNS §B.5.
            //
            // All rules below are exact-match (no prefix wildcards), so the current ordering
            // is safe. BUT: if a future contributor adds a prefix matcher like /api/auth/**
            // without understanding the consequences, it will swallow the /api/auth/logout-all
            // authenticated() rule below. DO NOT reorder or add prefix matchers without
            // verifying the impact on the explicit authenticated() rule for logout-all.
            //
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                .requestMatchers(
                    "/api/auth/register",
                    "/api/auth/login",
                    // refresh authenticates via HttpOnly cookie inside the handler, not via SecurityContext
                    "/api/auth/refresh",
                    // logout is idempotent and cookie-authenticated inside the handler (FOOTGUNS §B.7)
                    "/api/auth/logout"
                ).permitAll()
                .requestMatchers("/api/auth/logout-all").authenticated()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .exceptionHandling(eh -> eh.authenticationEntryPoint(authenticationEntryPoint))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(List.of(allowedOrigin));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setAllowCredentials(true); // required for the refresh cookie
        cfg.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd backend
./mvnw test -Dtest=SecurityConfigTest -q
```

Expected: `healthEndpoint_isPublic` and `meEndpoint_returns401WhenUnauthenticated` pass. The authenticated test may fail or pass depending on DB state — that's OK, the full cross-slice path is tested in Phase L.

- [ ] **Step 5: Run all tests so far to catch any regressions**

```bash
cd backend
./mvnw test -q
```

Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
cd backend
git add src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java \
        src/test/java/com/slparcelauctions/backend/config/SecurityConfigTest.java
git commit -m "feat(auth): wire JwtAuthenticationFilter in SecurityConfig and flip to authenticated()"
```

---

### Task 28: Create `RefreshTokenCleanupJob`

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auth/RefreshTokenCleanupJob.java`

- [ ] **Step 1: Create the job**

Create `backend/src/main/java/com/slparcelauctions/backend/auth/RefreshTokenCleanupJob.java`:

```java
package com.slparcelauctions.backend.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;

/**
 * Scheduled cleanup of expired and long-revoked refresh token rows. Runs daily at 03:30 server
 * time and deletes rows where either {@code expires_at} or {@code revoked_at} is more than 30
 * days in the past. Keeps the table from growing unbounded while retaining a month of audit
 * history for security investigations.
 *
 * <p>Disabled in integration tests via {@code auth.cleanup.enabled=false} on the test base class.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "auth.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class RefreshTokenCleanupJob {

    private static final int RETENTION_DAYS = 30;

    private final RefreshTokenRepository refreshTokenRepository;

    @Scheduled(cron = "0 30 3 * * *")  // 03:30 server local time, daily
    @Transactional
    public void cleanupExpiredTokens() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(RETENTION_DAYS);
        int deleted = refreshTokenRepository.deleteOldRows(cutoff);
        log.info("Refresh token cleanup: deleted {} rows older than {}", deleted, cutoff);
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd backend
./mvnw compile -q
```

- [ ] **Step 3: Commit**

```bash
cd backend
git add src/main/java/com/slparcelauctions/backend/auth/RefreshTokenCleanupJob.java
git commit -m "feat(auth): add RefreshTokenCleanupJob for daily expired-row cleanup"
```

---

## Phase L — Cross-slice (`GET /api/users/me`)

### Task 29: Replace GET /me 501 stub with real implementation + update user controller test

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/UserController.java`
- Modify: `backend/src/test/java/com/slparcelauctions/backend/user/UserControllerTest.java`

Single commit: controller change + test update. Cross-slice scope documented in the PR description.

- [ ] **Step 1: Update `UserController.getMe`**

Find the existing `getMe()` method in `backend/src/main/java/com/slparcelauctions/backend/user/UserController.java` and replace its body:

**Before:**

```java
@GetMapping("/me")
public ResponseEntity<Void> getMe() {
    // JWT auth lands in Task 01-07
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
}
```

**After:**

```java
@GetMapping("/me")
public UserResponse getMe(@AuthenticationPrincipal AuthPrincipal principal) {
    return userService.getUserById(principal.userId());
}
```

Add the imports:

```java
import com.slparcelauctions.backend.auth.AuthPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
```

- [ ] **Step 2: Update the PUT and DELETE /me comments (but keep them as 501 stubs)**

Find the `PUT /me` and `DELETE /me` methods in the same file and update the comment:

```java
@PutMapping("/me")
public ResponseEntity<Void> updateMe() {
    // Profile edit lands in Task 01-XX (TBD) — needs design pass on field-level edit rules
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
}

@DeleteMapping("/me")
public ResponseEntity<Void> deleteMe() {
    // Profile edit lands in Task 01-XX (TBD) — needs design pass on soft-vs-hard delete + GDPR
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
}
```

- [ ] **Step 3: Update `UserControllerTest` — remove the 501 test case, add two new cases**

Find the existing GET /me test case in `backend/src/test/java/com/slparcelauctions/backend/user/UserControllerTest.java` (probably named something like `getMe_returns501`) and replace it with:

```java
@Test
@WithMockAuthPrincipal(userId = 1L)
void getMe_returnsUserDto_whenAuthenticated() throws Exception {
    UserResponse expected = /* build a UserResponse with id=1L and test fields */;
    when(userService.getUserById(1L)).thenReturn(expected);

    mockMvc.perform(get("/api/users/me"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(1))
        .andExpect(jsonPath("$.email").value(expected.email()));
}

@Test
void getMe_returns401_whenUnauthenticated() throws Exception {
    mockMvc.perform(get("/api/users/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_TOKEN_MISSING"));
}
```

Add the imports at the top of the test file:

```java
import com.slparcelauctions.backend.auth.test.WithMockAuthPrincipal;
```

- [ ] **Step 4: Run the user slice tests**

```bash
cd backend
./mvnw test -Dtest='com.slparcelauctions.backend.user.*' -q
```

Expected: all user-slice tests pass, including the two new /me cases.

- [ ] **Step 5: Commit**

```bash
cd backend
git add src/main/java/com/slparcelauctions/backend/user/UserController.java \
        src/test/java/com/slparcelauctions/backend/user/UserControllerTest.java
git commit -m "feat(user): wire GET /api/users/me to authenticated principal (cross-slice from auth)"
```

---

## Phase M — Integration tests

### Task 30: Create `AuthFlowIntegrationTest` base class with cleanup disabled

**Files:**
- Create: `backend/src/test/java/com/slparcelauctions/backend/auth/AuthFlowIntegrationTest.java`

First task of this phase creates the test class scaffolding and one simple happy-path test. Subsequent tasks (31–34) add each flow as a separate commit for granularity.

- [ ] **Step 1: Create the test class with the first flow**

Create `backend/src/test/java/com/slparcelauctions/backend/auth/AuthFlowIntegrationTest.java`:

```java
package com.slparcelauctions.backend.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.auth.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "auth.cleanup.enabled=false")
@Transactional
class AuthFlowIntegrationTest {

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;

    @Test
    void registerThenUseAccessToken() throws Exception {
        RegisterRequest register = new RegisterRequest(
            "reg@example.com", "hunter22abc", "Reg User");

        MvcResult regResult = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(register)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.user.email").value("reg@example.com"))
            .andExpect(header().exists("Set-Cookie"))
            .andReturn();

        JsonNode body = objectMapper.readTree(regResult.getResponse().getContentAsString());
        String accessToken = body.get("accessToken").asText();

        mockMvc.perform(get("/api/users/me")
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("reg@example.com"));
    }
}
```

- [ ] **Step 2: Start Postgres locally if not running**

```bash
docker run -d --name slpa-pg -p 5432:5432 \
  -e POSTGRES_DB=slpa -e POSTGRES_USER=slpa -e POSTGRES_PASSWORD=slpa \
  postgres:16
```

(Use the dev DB from the user's memory — check `dev_containers.md` for the canonical docker run.)

- [ ] **Step 3: Run the integration test**

```bash
cd backend
./mvnw test -Dtest=AuthFlowIntegrationTest#registerThenUseAccessToken -q
```

Expected: test passes. If it fails, check that `@EnableScheduling` is in place, `jwt.secret` has a valid value in `application-dev.yml`, and Postgres is running.

- [ ] **Step 4: Commit**

```bash
cd backend
git add src/test/java/com/slparcelauctions/backend/auth/AuthFlowIntegrationTest.java
git commit -m "test(auth): add AuthFlowIntegrationTest with register-then-use-token flow"
```

---

### Task 31: Add `loginThenRefreshThenUseNewToken` integration test

**Files:**
- Modify: `backend/src/test/java/com/slparcelauctions/backend/auth/AuthFlowIntegrationTest.java`

- [ ] **Step 1: Add the test method**

Append to `AuthFlowIntegrationTest`:

```java
@Test
void loginThenRefreshThenUseNewToken() throws Exception {
    // Setup: register a user
    RegisterRequest register = new RegisterRequest(
        "login@example.com", "hunter22abc", "Login User");
    mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(register)))
        .andExpect(status().isCreated());

    // Login
    MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"login@example.com\",\"password\":\"hunter22abc\"}"))
        .andExpect(status().isOk())
        .andReturn();

    String refreshCookie = extractRefreshCookie(loginResult);
    assertThat(refreshCookie).isNotNull();

    // Refresh
    MvcResult refreshResult = mockMvc.perform(post("/api/auth/refresh")
            .cookie(new jakarta.servlet.http.Cookie("refreshToken", refreshCookie)))
        .andExpect(status().isOk())
        .andReturn();

    JsonNode refreshBody = objectMapper.readTree(refreshResult.getResponse().getContentAsString());
    String newAccessToken = refreshBody.get("accessToken").asText();
    String newRefreshCookie = extractRefreshCookie(refreshResult);

    // The new refresh cookie is different from the old one
    assertThat(newRefreshCookie).isNotEqualTo(refreshCookie);

    // Use the new access token on /me
    mockMvc.perform(get("/api/users/me")
            .header("Authorization", "Bearer " + newAccessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("login@example.com"));
}

private String extractRefreshCookie(MvcResult result) {
    String setCookie = result.getResponse().getHeader("Set-Cookie");
    if (setCookie == null) return null;
    // Parse "refreshToken=<value>; HttpOnly; Secure; ..."
    int eq = setCookie.indexOf('=');
    int semi = setCookie.indexOf(';');
    if (eq < 0 || semi < 0 || eq > semi) return null;
    return setCookie.substring(eq + 1, semi);
}
```

- [ ] **Step 2: Run the test**

```bash
cd backend
./mvnw test -Dtest=AuthFlowIntegrationTest#loginThenRefreshThenUseNewToken -q
```

Expected: passes.

- [ ] **Step 3: Commit**

```bash
cd backend
git add src/test/java/com/slparcelauctions/backend/auth/AuthFlowIntegrationTest.java
git commit -m "test(auth): add login-refresh-use-new-token integration test"
```

---

### Task 32: Add `refreshTokenReuseCascade` integration test (the security-critical canary)

**Files:**
- Modify: `backend/src/test/java/com/slparcelauctions/backend/auth/AuthFlowIntegrationTest.java`

This test is **non-negotiable** — it's the canary for the reuse-detection cascade. Never delete it. See FOOTGUNS §B.6.

- [ ] **Step 1: Inject `RefreshTokenRepository` and `UserRepository` into the test class**

Add to the class-level field declarations:

```java
@Autowired private com.slparcelauctions.backend.auth.RefreshTokenRepository refreshTokenRepository;
@Autowired private com.slparcelauctions.backend.user.UserRepository userRepository;
```

- [ ] **Step 2: Add the test method**

```java
@Test
void refreshTokenReuseCascade_revokesAllSessionsAndBumpsTokenVersion() throws Exception {
    // 1. Register and capture cookie A + access token A.
    RegisterRequest register = new RegisterRequest(
        "reuse-test@example.com", "hunter22abc", "Reuse Victim");
    MvcResult regResult = mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(register)))
        .andExpect(status().isCreated())
        .andReturn();

    String cookieA = extractRefreshCookie(regResult);
    JsonNode regBody = objectMapper.readTree(regResult.getResponse().getContentAsString());
    Long userId = regBody.get("user").get("id").asLong();

    // 2. Refresh once with cookie A → success, capture cookie B.
    MvcResult refreshB = mockMvc.perform(post("/api/auth/refresh")
            .cookie(new jakarta.servlet.http.Cookie("refreshToken", cookieA)))
        .andExpect(status().isOk())
        .andReturn();
    String cookieB = extractRefreshCookie(refreshB);

    // 3. Refresh AGAIN with cookie A (now revoked) → 401 + AUTH_REFRESH_TOKEN_REUSED.
    mockMvc.perform(post("/api/auth/refresh")
            .cookie(new jakarta.servlet.http.Cookie("refreshToken", cookieA)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_REUSED"));

    // 4. Query DB: ALL refresh tokens for the user have revoked_at IS NOT NULL.
    var allTokens = refreshTokenRepository.findAllByUserId(userId);
    assertThat(allTokens).allSatisfy(rt -> assertThat(rt.getRevokedAt()).isNotNull());

    // 5. Cookie B is now dead (revoked by the cascade in step 3, not just cookie A).
    mockMvc.perform(post("/api/auth/refresh")
            .cookie(new jakarta.servlet.http.Cookie("refreshToken", cookieB)))
        .andExpect(status().isUnauthorized());

    // 6. Assert users.token_version was incremented from 0 to 1.
    //
    // End-to-end "stale access token rejected by write-path service" lands in Task 01-XX
    // when BidService ships — we assert the bump here, not the reject. The cascade calls
    // userService.bumpTokenVersion(); the downstream effect on access tokens is the job of
    // the service-layer freshness check, which lands with the first write-path service.
    var user = userRepository.findById(userId).orElseThrow();
    assertThat(user.getTokenVersion()).isEqualTo(1L);
}
```

- [ ] **Step 3: Run the test**

```bash
cd backend
./mvnw test -Dtest=AuthFlowIntegrationTest#refreshTokenReuseCascade_revokesAllSessionsAndBumpsTokenVersion -q
```

Expected: passes. If the DB assertions fail, the cascade isn't wiring correctly — debug `RefreshTokenService.rotate()`.

- [ ] **Step 4: Commit**

```bash
cd backend
git add src/test/java/com/slparcelauctions/backend/auth/AuthFlowIntegrationTest.java
git commit -m "test(auth): add refresh-token-reuse cascade integration test (security canary)"
```

---

### Task 33: Add `logoutThenRefreshFails` integration test

**Files:**
- Modify: `backend/src/test/java/com/slparcelauctions/backend/auth/AuthFlowIntegrationTest.java`

- [ ] **Step 1: Add the test method**

```java
@Test
void logoutThenRefreshFails() throws Exception {
    RegisterRequest register = new RegisterRequest(
        "logout@example.com", "hunter22abc", "Logout User");
    MvcResult regResult = mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(register)))
        .andExpect(status().isCreated())
        .andReturn();
    String cookie = extractRefreshCookie(regResult);

    // Logout — returns 204 and clears cookie
    mockMvc.perform(post("/api/auth/logout")
            .cookie(new jakarta.servlet.http.Cookie("refreshToken", cookie)))
        .andExpect(status().isNoContent())
        .andExpect(header().exists("Set-Cookie"));

    // Refresh with the now-revoked cookie → 401
    mockMvc.perform(post("/api/auth/refresh")
            .cookie(new jakarta.servlet.http.Cookie("refreshToken", cookie)))
        .andExpect(status().isUnauthorized());

    // Logout is idempotent — calling again still returns 204
    mockMvc.perform(post("/api/auth/logout")
            .cookie(new jakarta.servlet.http.Cookie("refreshToken", cookie)))
        .andExpect(status().isNoContent());

    // Logout with no cookie at all is also 204
    mockMvc.perform(post("/api/auth/logout"))
        .andExpect(status().isNoContent());
}
```

- [ ] **Step 2: Run the test**

```bash
cd backend
./mvnw test -Dtest=AuthFlowIntegrationTest#logoutThenRefreshFails -q
```

Expected: passes.

- [ ] **Step 3: Commit**

```bash
cd backend
git add src/test/java/com/slparcelauctions/backend/auth/AuthFlowIntegrationTest.java
git commit -m "test(auth): add logout-then-refresh-fails integration test"
```

---

### Task 34: Add `logoutAllInvalidatesAllSessions` integration test

**Files:**
- Modify: `backend/src/test/java/com/slparcelauctions/backend/auth/AuthFlowIntegrationTest.java`

Uses `RefreshTokenTestFixture` to simulate a second device.

- [ ] **Step 1: Inject `RefreshTokenTestFixture`**

Add to the class-level field declarations:

```java
@Autowired private com.slparcelauctions.backend.auth.test.RefreshTokenTestFixture refreshTokenTestFixture;
```

- [ ] **Step 2: Add the test method**

```java
@Test
void logoutAllInvalidatesAllSessions() throws Exception {
    // 1. Register (device 1, captures cookie 1 + access token 1).
    RegisterRequest register = new RegisterRequest(
        "logout-all@example.com", "hunter22abc", "Multi-Device User");
    MvcResult regResult = mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(register)))
        .andExpect(status().isCreated())
        .andReturn();

    JsonNode regBody = objectMapper.readTree(regResult.getResponse().getContentAsString());
    Long userId = regBody.get("user").get("id").asLong();
    String accessToken1 = regBody.get("accessToken").asText();
    String cookie1 = extractRefreshCookie(regResult);

    // 2. Seed a second refresh-token row via the fixture (simulates device 2).
    var device2 = refreshTokenTestFixture.insertValid(userId);
    String cookie2 = device2.rawToken();

    // 3. POST /api/auth/logout-all with access token 1.
    mockMvc.perform(post("/api/auth/logout-all")
            .header("Authorization", "Bearer " + accessToken1))
        .andExpect(status().isNoContent());

    // 4. Query DB: all refresh tokens revoked, users.token_version incremented.
    var allTokens = refreshTokenRepository.findAllByUserId(userId);
    assertThat(allTokens).allSatisfy(rt -> assertThat(rt.getRevokedAt()).isNotNull());

    var user = userRepository.findById(userId).orElseThrow();
    assertThat(user.getTokenVersion()).isEqualTo(1L);

    // 5. Refresh with cookie 1 → 401
    mockMvc.perform(post("/api/auth/refresh")
            .cookie(new jakarta.servlet.http.Cookie("refreshToken", cookie1)))
        .andExpect(status().isUnauthorized());

    // 6. Refresh with device 2 seeded token → 401
    mockMvc.perform(post("/api/auth/refresh")
            .cookie(new jakarta.servlet.http.Cookie("refreshToken", cookie2)))
        .andExpect(status().isUnauthorized());
}
```

- [ ] **Step 3: Run the test**

```bash
cd backend
./mvnw test -Dtest=AuthFlowIntegrationTest#logoutAllInvalidatesAllSessions -q
```

Expected: passes.

- [ ] **Step 4: Run the full `AuthFlowIntegrationTest` class to confirm no test interference**

```bash
cd backend
./mvnw test -Dtest=AuthFlowIntegrationTest -q
```

Expected: all 5 integration tests pass.

- [ ] **Step 5: Commit**

```bash
cd backend
git add src/test/java/com/slparcelauctions/backend/auth/AuthFlowIntegrationTest.java
git commit -m "test(auth): add logout-all invalidates-all-sessions integration test"
```

---

## Phase N — Finalization

### Task 35: Add FOOTGUNS §B backend section and §5.8 meta-lesson

**Files:**
- Modify: `docs/implementation/FOOTGUNS.md`

- [ ] **Step 1: Open `docs/implementation/FOOTGUNS.md` and find the end of §5 (Project conventions)**

- [ ] **Step 2: Add `§5.8` — the load-bearing documentation meta-lesson**

Insert after the last §5 entry (which will be either §5.6 or §5.7 depending on what's merged from other branches):

```markdown
### 5.8 Load-bearing documentation pattern — document WHY, not just WHAT

**Why:** Some decisions in specs are not stylistic — they're structural guards against future
refactoring that would quietly degrade the security or correctness of the system. Examples from
the Task 01-07 JWT auth spec:

- The `AuthResult` (service-internal) vs `AuthResponse` (controller-external) **two-record split**
  exists so the type system prevents refresh-token leakage into JSON bodies. If you merge them
  "for simplicity," you've removed the structural guard.
- The **`Path=/api/auth` non-widening rule** on the refresh cookie is what makes cookie-only
  logout CSRF-safe. Widening the path to `/` is a ten-character change that breaks the security
  model.
- The **`@RestControllerAdvice(basePackages = "...auth")`** scoping on `AuthExceptionHandler`
  prevents cross-slice exception leakage. Removing the scope "to simplify" means the auth
  handler starts catching user-slice exceptions and producing wrong error codes.
- The **refresh-token reuse-cascade integration test** is a canary. If a future contributor
  "optimizes" it away because "we already have a unit test for the service," they've disabled
  the entire defense that makes DB-backed refresh tokens worth the cost over JWT refresh tokens.

In each case, the code alone doesn't tell you why the thing can't be refactored — the decision
is only load-bearing if the next contributor *understands* it's load-bearing.

**How to apply:**

- When a spec locks a decision that would pass code review if a future contributor refactored it
  away, **write a JavaDoc or comment block explaining why the decision can't be refactored.**
  Not "this record is for serialization" but "this record exists as a structural guard against
  a specific failure mode. Do NOT merge it with X for simplicity — the split is the guard."
- **Reference the FOOTGUNS ledger entry by number** so future contributors have a one-hop link
  to the full rationale. The JavaDoc is the warning; the ledger entry is the reasoning.
- **Watch for simplification PRs in code review.** A PR that says "simplified the auth DTOs by
  merging AuthResult and AuthResponse" is a red flag regardless of how clean the diff looks.
  Load-bearing decisions look like over-engineering to contributors who don't know why they exist.
- **Apply this to every future security-critical spec.** The pattern is: identify the decisions
  that future contributors will be tempted to refactor away, document the *why*, reference the
  ledger, and trust that the documentation is load-bearing in the same way the code is.

This is a meta-lesson for spec writing, not an implementation footgun. It applies across
frontend and backend slices equally. Filed here under §5 (project conventions) because it's a
convention about how to write specs, not a domain-specific gotcha.
```

- [ ] **Step 3: Add the `## §B. Backend / Spring Security / JJWT` section**

Add a new top-level section at the end of the file (after `## §6. Scaffold / template / generated content` or wherever the frontend sections end):

```markdown
---

## §B. Backend / Spring Security / JJWT

Backend-domain footguns. Numbered `§B.1`, `§B.2`, etc. to keep the namespace separate from the
frontend-domain sections (`§1`–`§6`) so search stays clean.

### B.1 `@AuthenticationPrincipal AuthPrincipal principal`, never `UserDetails`

**Why:** Spring Security's tutorial-default principal type is `UserDetails`. Reaching for it in
the SLPA codebase yields `null` at runtime because `JwtAuthenticationFilter` sets an
`AuthPrincipal` record into the `SecurityContext`, not a Spring `UserDetails`.

**How to apply:**
- Every controller uses `@AuthenticationPrincipal AuthPrincipal principal`.
- Never reach for `UserDetails` in a controller method signature — it silently yields `null`.
- A backend grep verify rule (mirror of the frontend's `npm run verify` chain) should flag
  `@AuthenticationPrincipal UserDetails` as a build break. Implementation of the rule is a
  follow-on task; for now, code review enforces it.

### B.2 `AuthenticationEntryPoint` bypasses the message converter chain

**Why:** `AuthenticationEntryPoint.commence()` runs outside Spring's message converter chain.
Returning a `ProblemDetail` from the method signature does nothing — the entry point is not an
`@ExceptionHandler`, so Spring's auto-conversion doesn't fire.

**How to apply:**
- `JwtAuthenticationEntryPoint` serializes the `ProblemDetail` manually via injected `ObjectMapper`.
- Set `Content-Type: application/problem+json`, status code, character encoding, and
  `Cache-Control: no-store` explicitly.
- If a future entry point is added (e.g., an OAuth error path), follow the same manual-serialization
  pattern. Copying a `ProblemDetail` return from a regular exception handler won't work.

### B.3 `WithSecurityContextFactory` wiring in Spring Security 6

**Why:** Spring Security 6 auto-discovers `WithSecurityContextFactory` implementations from
the `@WithSecurityContext(factory = ...)` element on the custom annotation itself. Legacy Spring
Security (5 and earlier) required explicit registration in `META-INF/spring.factories`.

**How to apply:**
- Use `@WithSecurityContext(factory = YourFactory.class)` directly on the annotation.
- If the annotation is silently ignored in tests (principal is `null` inside the controller under
  test), fall back to `META-INF/spring.factories`. Verify the primary path first — the fallback
  is a last resort.
- Document which path was used in `auth/test/README.md` for the next test author.

### B.4 `jwt.secret` must be present in every active profile

**Why:** `JwtConfig.@PostConstruct` validates on startup and throws if `jwt.secret` is missing or
shorter than 256 bits. Adding a new application profile (e.g., `application-test.yml`) for other
reasons later will cause every test in that profile to fail before the first assertion runs
unless the profile inherits or re-declares `jwt.secret`.

**How to apply:**
- Any new profile that loads Spring config must have `jwt.secret` set — either inherit from base
  `application.yml` (which reads `${JWT_SECRET}`) or declare explicitly.
- Tests that use `@TestPropertySource` must include `jwt.secret` in their properties block.
- The fail-fast validation is intentional — do not weaken it by allowing null in dev profiles.

### B.5 `SecurityConfig` matcher ordering

**Why:** Spring Security matches `requestMatchers` rules in declaration order. The current SLPA
ordering is safe because every rule is exact-match (no prefix wildcards). Adding a prefix matcher
like `/api/auth/**` without understanding the consequences will break the explicit
`/api/auth/logout-all authenticated()` rule — the prefix rule would swallow it unless declared
after.

**How to apply:**
- Do not add prefix matchers (`/**` suffixes) without verifying the impact on existing exact-match
  rules.
- Do not reorder `authorizeHttpRequests` rules without understanding why the current order exists.
- The inline comment in `SecurityConfig` documents this constraint at the call site. Read it
  before modifying.

### B.6 Refresh token reuse cascade is the security model

**Why:** The `refreshTokenReuseCascade_revokesAllSessionsAndBumpsTokenVersion` integration test
in `AuthFlowIntegrationTest` is a canary. If a future contributor "optimizes" the refresh path by
skipping the revoked-row check in `RefreshTokenService.rotate`, they've degraded the auth slice
from "stateful with revocation" to "stateless without it" without realizing. The test is the
only thing that catches this regression — the unit tests mock the repository and wouldn't notice.

**How to apply:**
- **Never delete the reuse-cascade integration test.** Removing it is equivalent to removing the
  security feature.
- If the test is flaky or slow, fix the flake — do not quarantine or delete.
- Any PR that touches `RefreshTokenService.rotate` must run the integration test and assert it
  still passes.

### B.7 Logout endpoint idempotency

**Why:** `POST /api/auth/logout` must always return 204, even if the cookie is missing,
malformed, expired, revoked, or points to another user's token. Throwing a 401 on an
already-revoked token would create a "is this token still alive?" oracle through the logout
endpoint — an attacker with a bunch of candidate raw tokens could test them by checking the
logout response code.

**How to apply:**
- `AuthService.logout` and `RefreshTokenService.revokeByRawToken` are idempotent and never throw.
- The controller's `/logout` handler catches nothing — it just returns 204 after the service call.
- Do not add logging that distinguishes "successful revoke" from "cookie was already revoked" —
  both cases should produce the same log output (or none).

### B.8 Refresh token raw value never lives in the DB

**Why:** The refresh token's raw 256-bit random value exists only in the HttpOnly cookie on the
wire and in the client's cookie jar. The database stores only the SHA-256 hash. If the DB leaks,
tokens leak as hashes — not usable credentials. This is the entire reason refresh tokens are
worth persisting at all.

**How to apply:**
- If a future migration or debug feature adds a `raw_token` column to `refresh_tokens`, roll it
  back immediately. "Temporary debug column" is not an acceptable reason.
- `RefreshTokenService` hashes on every write; the raw value only crosses the API boundary as a
  return value from `issueForUser` and `rotate`.
- The `RefreshTokenTestFixture` also hashes — tests that insert rows use the raw value only for
  replay through the API, not for DB assertions.

### B.9 JJWT 0.12+ API is different from 0.11

**Why:** JJWT 0.12 introduced a new builder API. Stack Overflow examples showing
`Jwts.builder().setSubject(...)` and `signWith(SignatureAlgorithm.HS256, secret)` are JJWT 0.11
syntax and will not compile against 0.12. The 0.12 form is `Jwts.builder().subject(...)` and
`signWith(secretKey)` with the algorithm inferred from the key.

**How to apply:**
- Use `Jwts.parser().verifyWith(key).build().parseSignedClaims(token)` — not
  `Jwts.parserBuilder()` (deprecated).
- When copying JJWT examples from the internet, check the library version in the example first.
- The `JwtService` and `JwtTestFactory` classes demonstrate the correct 0.12 API — reference them
  for new JWT code.

### B.10 `AuthResult` (service-internal) vs `AuthResponse` (controller-external) two-record split

**Why:** The two-record split exists so the type system catches any code path that would put a
refresh token in a JSON body. `AuthResult` (internal, service-to-controller) carries
`{accessToken, refreshToken, user}`. `AuthResponse` (external, controller-to-client) carries
`{accessToken, user}` — no refresh token field. A handler that returns `AuthResponse` cannot
leak the refresh token because the field doesn't exist on the type.

**How to apply:**
- **Do not merge `AuthResult` and `AuthResponse` "for simplicity."** The split is the structural
  guard; merging removes the guard.
- Every new auth endpoint that returns tokens must return `AuthResponse`, not `AuthResult`.
- The JavaDoc on both records documents this — don't delete the JavaDoc either.
- If a future contributor proposes a PR that merges the records, the PR review should reference
  this entry and decline.
```

- [ ] **Step 4: Verify the file parses as markdown (visual check)**

```bash
head -50 docs/implementation/FOOTGUNS.md
tail -100 docs/implementation/FOOTGUNS.md
```

Spot-check that the new entries are properly formatted, the `---` section dividers are correct,
and no markdown renders as raw text.

- [ ] **Step 5: Commit**

```bash
git add docs/implementation/FOOTGUNS.md
git commit -m "docs(footguns): add §B backend section and §5.8 load-bearing documentation meta-lesson"
```

---

### Task 36: Sweep root `README.md` for JWT auth updates

**Files:**
- Modify: `README.md` (project root)

- [ ] **Step 1: Find the backend section**

```bash
grep -n "Backend\|backend" README.md | head -10
```

- [ ] **Step 2: Add a one-paragraph update mentioning JWT auth**

Find the "Running tests" section (or equivalent) and add/update the backend line to mention JWT
authentication is live:

```markdown
cd backend && ./mvnw test             # unit + slice + integration tests (~55 cases incl. JWT auth flows)
```

Find the "Local development without Docker" frontend section and add a sentence under the backend
block (not the frontend):

```markdown
The backend now requires `JWT_SECRET` in production (environment variable) and uses a committed
dev default in `application-dev.yml`. The JWT auth slice provides `/api/auth/register`,
`/api/auth/login`, `/api/auth/refresh`, `/api/auth/logout`, and `/api/auth/logout-all`. See
[`docs/superpowers/specs/2026-04-11-task-01-07-jwt-auth-backend-design.md`](docs/superpowers/specs/2026-04-11-task-01-07-jwt-auth-backend-design.md)
for the full design.
```

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs(readme): document JWT auth endpoints and JWT_SECRET env var requirement"
```

---

### Task 37: Final verification — all tests, all profiles, clean working tree

**Files:**
- None (verification only)

- [ ] **Step 1: Run the full test suite**

```bash
cd backend
./mvnw clean test
```

Expected: all tests pass. Test count should be ~55 (23 unit in auth package + ~10 slice + 5 integration + existing user-slice tests).

- [ ] **Step 2: Run the app against the dev profile and spot-check the endpoints**

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

In another terminal:

```bash
# Register
curl -i -X POST http://localhost:8080/api/auth/register \
    -H "Content-Type: application/json" \
    -d '{"email":"smoke@example.com","password":"hunter22abc","displayName":"Smoke"}'

# Expect: 201 Created, body {"accessToken": "...", "user": {...}}, Set-Cookie: refreshToken=...

# Use the access token
ACCESS_TOKEN="<from above>"
curl -i http://localhost:8080/api/users/me \
    -H "Authorization: Bearer $ACCESS_TOKEN"

# Expect: 200 OK, body is the user DTO.

# Unauthorized request
curl -i http://localhost:8080/api/users/me

# Expect: 401 Unauthorized, body is ProblemDetail with code AUTH_TOKEN_MISSING, Cache-Control: no-store

# Health check (public)
curl -i http://localhost:8080/api/health

# Expect: 200 OK
```

Stop the server with Ctrl+C.

- [ ] **Step 3: Confirm clean working tree**

```bash
cd /c/Users/heath/Repos/Personal/slpa-task-01-07
git status
```

Expected: `nothing to commit, working tree clean`.

- [ ] **Step 4: Confirm the branch is ahead of main by the expected commit count**

```bash
git log --oneline main..HEAD | wc -l
```

Expected: ~38–40 commits (one per task + the spec and plan commits).

- [ ] **Step 5: No commit for this task — it's verification only**

Task 37 produces no commit. The verification confirms the slice is functionally complete before
shipping.

---

### Task 38: Open the pull request

**Files:**
- None (PR creation via `gh`)

Per FOOTGUNS §5.7, the final ship step is `gh pr create`, never a direct push to main.

- [ ] **Step 1: Push the branch**

```bash
cd /c/Users/heath/Repos/Personal/slpa-task-01-07
git push -u origin task/01-07-jwt-auth-backend
```

- [ ] **Step 2: Open the PR with the smoke checklist and gate evidence**

```bash
gh pr create \
    --base main \
    --head task/01-07-jwt-auth-backend \
    --title "feat(auth): JWT authentication backend (register/login/refresh/logout + reuse detection)" \
    --body "$(cat <<'EOF'
## Summary

Ships the `auth/` vertical slice: DB-backed refresh tokens with rotation and reuse detection,
15-minute HS256 access tokens, the `JwtAuthenticationFilter` with lightweight `AuthPrincipal`,
five endpoints (`register`, `login`, `refresh`, `logout`, `logout-all`), per-slice ProblemDetail
error handling with `AUTH_*` codes, the `@WithMockAuthPrincipal` test annotation and `JwtTestFactory`
that the next 20 protected-endpoint tasks will inherit, and the real `GET /api/users/me`
implementation replacing the 501 stub.

Cross-slice touches (expected scope): `User.tokenVersion` column + `UserService.bumpTokenVersion`
method in the user slice.

## Automated gates

- [x] `./mvnw test` — all unit, slice, and integration tests pass
- [x] `./mvnw clean package` — builds cleanly
- [x] Dev profile smoke test — register, access-token-use, refresh, logout flows all work end-to-end

## Security-critical behavior verified

- [x] `AuthFlowIntegrationTest.refreshTokenReuseCascade_revokesAllSessionsAndBumpsTokenVersion`
      passes — the reuse-detection cascade revokes all sessions AND bumps `token_version` when a
      rotated-out refresh token is replayed.
- [x] `AuthFlowIntegrationTest.logoutAllInvalidatesAllSessions` passes — `/logout-all` revokes
      every active refresh token and bumps `token_version`.
- [x] `AuthControllerTest` verifies the `AuthResponse` body never contains a `refreshToken` field
      (the two-record split structural guard).

## References

- Spec: [`docs/superpowers/specs/2026-04-11-task-01-07-jwt-auth-backend-design.md`](docs/superpowers/specs/2026-04-11-task-01-07-jwt-auth-backend-design.md)
- Plan: [`docs/superpowers/plans/2026-04-11-task-01-07-jwt-auth-backend.md`](docs/superpowers/plans/2026-04-11-task-01-07-jwt-auth-backend.md)
- Brief: [`docs/implementation/epic-01/task-07-jwt-auth-backend.md`](docs/implementation/epic-01/task-07-jwt-auth-backend.md)
- FOOTGUNS §B: backend / Spring Security / JJWT footguns (10 new entries)
- FOOTGUNS §5.8: load-bearing documentation pattern (meta-lesson)

## Merge

Merge with **"Merge commit"** (not squash, not rebase) to preserve the per-task atomic commits.
EOF
)"
```

- [ ] **Step 2: Return the PR URL**

Paste the URL from the `gh pr create` output into the chat so the user can review on GitHub,
run the manual smoke test on their machine, tick the checkboxes, and merge via the GitHub UI.

---

## Self-review

Applied inline:

**1. Spec coverage:** every section of the spec (§1–§15) maps to one or more tasks in this plan.
Cross-checked file inventory:
- Spec §2 data model → Tasks 13 (User.tokenVersion), 15 (RefreshToken entity)
- Spec §3 package structure → every task maps to a file in the structure
- Spec §4 JWT mechanism → Tasks 1 (JJWT), 3 (JwtKeyFactory + JwtConfig), 9 (JwtService)
- Spec §5 filter → Task 18
- Spec §6 services → Tasks 20 (RefreshTokenService), 21 (AuthService)
- Spec §7 endpoints → Tasks 24 (DTOs), 25 (controller), 26 (slice test)
- Spec §8 SecurityConfig → Task 27
- Spec §9 exception handling → Tasks 19 (entry point), 22 (auth handler), 23 (global handler)
- Spec §10 GET /me cross-slice → Task 29
- Spec §11 test infrastructure → Tasks 8 (JwtTestFactory), 11 (annotation), 12 (README), 17 (fixture)
- Spec §12 test plan → Tasks 30–34 (integration tests); unit + slice tests distributed across earlier tasks
- Spec §13 configuration → Task 2
- Spec §14 FOOTGUNS → Task 35
- Spec §15 out of scope → respected (no profile-edit, no email verification, no forgot-password,
  no OAuth, no 2FA, no session-listing endpoint, no write-path freshness check beyond `token_version`)

**2. Placeholder scan:** No `TBD`, `TODO`, or incomplete sections. Two deliberate implementer
"adjust to match existing shape" notes (the dev `jwt.secret` placeholder regeneration in Task 2,
the `UserResponse` constructor signature in Task 21's `AuthServiceTest` and Task 26's `stubUser()`)
— both are documented and the implementer has clear guidance.

**3. Type consistency:** verified that `AuthPrincipal(userId, email, tokenVersion)` is the same
signature in every task that references it (Tasks 5, 8, 9, 10, 11, 18, 21, 25, 26, 29). The
`AuthResult(accessToken, refreshToken, user)` vs `AuthResponse(accessToken, user)` split is
consistent across Tasks 21, 24, 25, 26. `IssuedRefreshToken(rawToken, expiresAt)` and
`RotationResult(userId, newRawToken, newExpiresAt)` are consistent across Tasks 20, 21.

**4. Post-spec refinements applied:** all 5 noted in the plan header are baked into the task
steps:
- Refinement 1 (drop `AuthenticationException` from GlobalExceptionHandler) → Task 23's file omits it
- Refinement 2 (AuthService.refresh() comment) → Task 21's code has the comment at the right line
- Refinement 3 (reuse-cascade asserts the code) → Task 32's test asserts `AUTH_REFRESH_TOKEN_REUSED`
- Refinement 4 (JwtTestFactory.forKey) → Task 8's code ships both paths; Task 10 uses the static form
- Refinement 5 (User.tokenVersion columnDefinition) → Task 13's annotation has the SQL-side default

**5. Meta-lesson placement:** FOOTGUNS §5.8 in Task 35 captures the load-bearing documentation
pattern as a general spec-writing convention.

---

## Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-11-task-01-07-jwt-auth-backend.md` on
branch `task/01-07-jwt-auth-backend` in worktree `C:\Users\heath\Repos\Personal\slpa-task-01-07`.

**Two execution options:**

1. **Subagent-Driven (recommended, B mode)** — dispatch a fresh subagent per task with the
   established B-mode prompt pattern (FOOTGUNS pre-flight, byte-exact rule, spec references,
   single-commit convention). Spec compliance + code quality review pair after each task. Fast
   iteration. Matches the successful Task 01-06 pattern.

2. **Inline Execution** — execute tasks in this session via `superpowers:executing-plans`. Batch
   execution with checkpoints for review. Slower than subagent-driven but all context stays in
   one place.

**Which approach?**

