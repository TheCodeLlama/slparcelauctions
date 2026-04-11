# Task 01-07 — JWT Authentication Backend Design

**Date:** 2026-04-11
**Branch:** `task/01-07-jwt-auth-backend`
**Brief:** [`docs/implementation/epic-01/task-07-jwt-auth-backend.md`](../../implementation/epic-01/task-07-jwt-auth-backend.md)
**Conventions:** [`docs/implementation/CONVENTIONS.md`](../../implementation/CONVENTIONS.md)

## Goal

Ship the `auth/` vertical slice: DB-backed refresh tokens with rotation and reuse detection, 15-minute HS256 access tokens, the `JwtAuthenticationFilter` that sets a lightweight `AuthPrincipal` in the Spring Security context, five auth endpoints (`register`, `login`, `refresh`, `logout`, `logout-all`), per-slice exception handling that produces RFC 7807 ProblemDetail responses with a machine-readable `code` extension, and the test infrastructure (`@WithMockAuthPrincipal`, `JwtTestFactory`, `RefreshTokenTestFixture`) that every subsequent protected-endpoint task will inherit. Cross-slice touch: add `User.tokenVersion` and replace the `GET /api/users/me` 501 stub with a real authenticated implementation.

## Architecture in three sentences

Access tokens are stateless 15-minute HS256 JWTs carried in the `Authorization: Bearer` header; the filter validates and parses them without touching the database. Refresh tokens are opaque 256-bit random strings, SHA-256-hashed at rest in a dedicated `refresh_tokens` table, rotated on every refresh, and cascaded-revoked on reuse detection (the security-critical behavior that makes the entire DB-backed choice worth the cost). The 15-minute staleness window on access tokens is closed for write-path operations by a `token_version` column on the user that write-path services check at the integrity boundary — no per-request DB lookup, no framework magic, explicit at the point it matters.

## Tech stack

- **Spring Boot 4.0.5 / Java 26** (existing)
- **Spring Security 6.x** (existing, stateless session, `SecurityFilterChain` bean style)
- **Spring Data JPA / Hibernate** with Lombok for boilerplate (existing convention)
- **JJWT 0.12.6** (new dependency — `jjwt-api` compile, `jjwt-impl` + `jjwt-jackson` runtime)
- **BCrypt** via existing `PasswordEncoder` bean in `config/PasswordConfig.java`
- **PostgreSQL** (existing, dev profile, `ddl-auto: update` for entity-driven schema changes per CONVENTIONS.md post-pivot vertical-slice flow — no manual Flyway migration in this task)
- **Vitest-equivalent test pyramid** per CONVENTIONS.md: unit (Mockito) + slice (`@WebMvcTest`) + integration (`@SpringBootTest` + `@AutoConfigureMockMvc` + `@ActiveProfiles("dev")`)

---

## §1. Architecture overview

Build the `auth/` vertical slice on top of the existing `user/` slice. The slice owns: JWT issuance and validation, refresh-token lifecycle, the auth filter, the auth controller, the auth-specific exception handler, the `JwtAuthenticationEntryPoint`, and the test infrastructure that the next 20 tasks will inherit.

The slice deliberately reaches into the `user/` slice in two places, both documented as expected scope:

1. **Adds a `token_version` column to `User`** (and the freshness-mitigation behavior that depends on it).
2. **Replaces the `GET /api/users/me` 501 stub** in `UserController` with a real implementation that consumes `@AuthenticationPrincipal AuthPrincipal principal`.

The auth path is **stateless on the access token** — the filter decodes a JWT and sets a principal, no DB hit. The auth path is **stateful on the refresh token** — DB-backed, hashed, rotated, and the reuse-detection cascade is what makes statefulness pay off. Access tokens live 15 minutes in memory on the client. Refresh tokens live 7 days sliding in an HttpOnly Secure SameSite=Lax cookie scoped to `/api/auth`.

**Staleness window closure**: the 15-minute window where a valid access token exists for a user who has since been banned / suspended / password-changed / logged-out-all is closed by a `token_version` claim (`tv`) embedded in the access token at issue time. Write-path services (future `BidService`, `ListingService`, `EscrowService`, etc.) check `principal.tokenVersion()` against the freshly-loaded `user.getTokenVersion()` at the top of any method that commits the user to an obligation. Read endpoints trust the 15-minute window. The check is service-level, not filter-level — no framework magic, and it lives at the actual integrity boundary.

---

## §2. Data model changes

### New entity: `RefreshToken`

Lives at `backend/src/main/java/com/slparcelauctions/backend/auth/RefreshToken.java`.

```java
@Entity
@Table(name = "refresh_tokens", indexes = {
    @Index(name = "idx_refresh_tokens_user_active",
           columnList = "user_id, revoked_at")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RefreshToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
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

Design notes:

- **`tokenHash` is SHA-256 hex of the raw token, never the raw token itself.** The raw value only exists in the HTTP cookie on the wire and in the client's cookie jar. If the DB leaks, tokens leak as hashes — not usable credentials. Unique index on `tokenHash` is the lookup path.
- **`userId` is a plain `Long`, not a JPA `@ManyToOne` to `User`.** Slice boundaries stay clean. Joins happen at the service layer when needed. This is a convention choice to keep slices loosely coupled.
- **`ipAddress` is `varchar(45)`**, not Postgres `inet`. Hibernate's `inet` round-trip handling has type-coercion quirks in some configurations, and we don't need Postgres-native CIDR queries for audit logging. `varchar(45)` holds the maximum IPv6 textual form. If analytics ever wants CIDR queries, migrate the column then.
- **`userAgent` is capped at 512 chars** via column length AND service-level truncation before insert. User-Agent strings can be pathologically long from misconfigured clients or attack probes; the audit table doesn't need to grow on garbage.
- **Composite index on `(user_id, revoked_at)`** supports the `revokeAllByUserId` cascade and future "active sessions per user" queries.

### Modified entity: `User.tokenVersion`

One column added to `user/User.java`:

```java
@Column(name = "token_version", nullable = false)
@Builder.Default
private Long tokenVersion = 0L;
```

JPA `ddl-auto: update` in dev handles the schema change automatically. Flyway entity-driven migrations handle it in prod when we get there. No manual SQL migration file — this matches the post-pivot vertical-slice flow in CONVENTIONS.md.

### Modified DTO: `UserResponse`

The `UserResponse` record (or class) in `user/dto/UserResponse.java` must **exclude** `tokenVersion` from serialization. The field is internal to the auth layer and never goes over the wire. If `UserResponse` is built from `User` via a static factory, the factory simply doesn't read `tokenVersion`. If it uses `@JsonIgnore` or field-level mapping, exclude the field explicitly. The spec covers the intent; the implementer picks the mechanism consistent with the existing `UserResponse` shape.

---

## §3. Package structure

```
backend/src/main/java/com/slparcelauctions/backend/
├── auth/
│   ├── AuthController.java
│   ├── AuthService.java
│   ├── JwtService.java
│   ├── JwtKeyFactory.java               // shared key derivation (test + prod)
│   ├── JwtAuthenticationFilter.java
│   ├── JwtAuthenticationEntryPoint.java
│   ├── AuthPrincipal.java               // record(userId, email, tokenVersion)
│   ├── RefreshToken.java                // entity
│   ├── RefreshTokenRepository.java
│   ├── RefreshTokenService.java
│   ├── RefreshTokenCleanupJob.java      // @Scheduled daily cleanup
│   ├── config/
│   │   └── JwtConfig.java               // @ConfigurationProperties + Bean wiring + @PostConstruct validation
│   ├── dto/
│   │   ├── LoginRequest.java            // record(@Email email, @NotBlank password)
│   │   ├── RegisterRequest.java         // record(@Email email, password, displayName)
│   │   ├── AuthResult.java              // INTERNAL service → controller (accessToken, refreshToken, user)
│   │   └── AuthResponse.java            // EXTERNAL controller → client (accessToken, user) — no refreshToken field
│   └── exception/
│       ├── AuthExceptionHandler.java    // @RestControllerAdvice scoped to auth package
│       ├── InvalidCredentialsException.java
│       ├── EmailAlreadyExistsException.java
│       ├── TokenExpiredException.java
│       ├── TokenInvalidException.java
│       ├── RefreshTokenReuseDetectedException.java
│       └── AuthenticationStaleException.java
├── common/
│   └── exception/
│       ├── GlobalExceptionHandler.java  // cross-cutting only
│       ├── ResourceNotFoundException.java
│       └── ResourceGoneException.java
├── config/
│   ├── SecurityConfig.java              // updated: filter wired + authenticated() flip
│   └── PasswordConfig.java              // unchanged
└── user/
    ├── User.java                        // +tokenVersion column
    ├── UserController.java              // GET /me 501 stub → real implementation
    └── ...                              // otherwise unchanged
```

Test infrastructure (mirrors the main tree under `src/test/java`):

```
backend/src/test/java/com/slparcelauctions/backend/auth/test/
├── WithMockAuthPrincipal.java
├── WithMockAuthPrincipalSecurityContextFactory.java
├── JwtTestFactory.java
├── RefreshTokenTestFixture.java
└── README.md                            // documents WithSecurityContext wiring path used
```

### Cross-slice scope documentation

The auth slice modifies files in the `user/` slice. This is **expected scope**, not drift, and must be documented in the PR description:

1. `user/User.java` — adds `tokenVersion` column
2. `user/dto/UserResponse.java` — excludes `tokenVersion` from serialization
3. `user/UserController.java` — GET `/me` stub becomes real implementation
4. `user/UserService.java` — adds `bumpTokenVersion(Long userId)` public method
5. `user/UserControllerTest.java` (or equivalent test file) — replaces GET /me 501 case with 200-authenticated + 401-unauthenticated cases

No other `user/` slice files are touched.

---

## §4. JWT mechanism (JJWT 0.12.x)

### Dependency additions to `backend/pom.xml`

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

The 0.12.x line is the modern JJWT API using `Jwts.builder()` and `Jwts.parser()` builders. JJWT 0.11 and earlier are deprecated and have a different surface — Stack Overflow examples showing `signWith(SignatureAlgorithm.HS256, secret)` are 0.11 syntax and will not compile against 0.12. The 0.12 form is `signWith(secretKey)` with the algorithm inferred from the key type.

### `JwtKeyFactory.java` — shared key derivation

```java
public final class JwtKeyFactory {
    private JwtKeyFactory() {}

    // HS256 requires keys ≥ 256 bits per RFC 7518 §3.2.
    private static final int MIN_KEY_BYTES = 32;

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

Used by **both** `JwtConfig` (production) and `JwtTestFactory` (tests) so the key shape can't drift between production and test paths. If the production key derivation changes, this helper changes once and both sides follow.

### `JwtConfig.java`

```java
@Configuration
@ConfigurationProperties(prefix = "jwt")
@Getter @Setter
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

`@PostConstruct` runs after Spring binds properties; failing here aborts startup with a clear error. The contractor doesn't have to remember to call `validate()` — Spring does it.

### `JwtService.java`

`@Service @RequiredArgsConstructor @Slf4j`. Thin facade over JJWT. Depends on `JwtConfig` and the `SecretKey` bean.

Public methods:

```java
public String issueAccessToken(AuthPrincipal principal);
public AuthPrincipal parseAccessToken(String token);  // throws TokenExpiredException / TokenInvalidException
```

`issueAccessToken` builds the JWT via `Jwts.builder()`:

```java
return Jwts.builder()
    .subject(String.valueOf(principal.userId()))
    .claim("email", principal.email())
    .claim("tv", principal.tokenVersion())
    .claim("type", "access")
    .issuedAt(Date.from(now))
    .expiration(Date.from(now.plus(jwtConfig.getAccessTokenLifetime())))
    .signWith(signingKey)
    .compact();
```

`parseAccessToken` validates via `Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token)`, extracts claims, asserts `type=access`, and returns the principal. Maps JJWT's `ExpiredJwtException` → `TokenExpiredException` and everything else → `TokenInvalidException`.

### Access token claims (canonical shape)

```json
{
  "sub": "42",
  "email": "user@example.com",
  "tv": 0,
  "iat": 1712345678,
  "exp": 1712346578,
  "type": "access"
}
```

- `sub` — user ID as string (per JWT spec), parsed back to `Long` in the filter
- `email` — user email at issue time; used for logging, display in dev tools, never as an authoritative identifier
- `tv` — token version; closed-loop against `users.token_version` at write-path service boundaries
- `iat` — issued-at, standard JWT claim
- `exp` — expiration, 15 minutes from issue
- `type` — literal string `"access"`; the filter rejects any token where `type != "access"` to prevent a refresh-token-shaped value being accepted as an access token (defense in depth — refresh tokens are opaque strings, not JWTs, so this check is redundant in practice, but the claim asserts the contract explicitly)

### Refresh tokens are NOT JWTs

Refresh tokens are 256-bit random byte arrays from `SecureRandom.getInstanceStrong()`, base64url-encoded (43 chars on the wire), SHA-256 hashed (64 chars hex) for DB storage. They do not carry claims. They are opaque identifiers looked up by hash.

Why not JWTs? Two reasons:

1. **Revocation requires DB state anyway.** Once you need DB state for revocation, the token format doesn't matter — you're looking up a row by some key. An opaque identifier is a smaller surface than a signed claim structure.
2. **Reuse-detection is clean.** A revoked JWT still carries valid claims; the only way to detect reuse is a DB lookup. An opaque token IS the DB lookup by design. No format conversion, no claim parsing, just "hash, look up, check flags."

---

## §5. `JwtAuthenticationFilter`

`extends OncePerRequestFilter`. Registered in `SecurityConfig` via `http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)`.

```java
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
```

Behavior invariants:

- **The filter NEVER throws.** Validation failures clear the context and let the request continue. `ExceptionTranslationFilter` downstream produces the 401 for protected endpoints via the `JwtAuthenticationEntryPoint`.
- **The filter NEVER writes to the response.** All response writing for auth failures happens in the entry point or the exception handler.
- **No DB hit** in the filter. The principal is built entirely from JWT claims. User freshness checks happen at write-path service boundaries, not here.

This is the "filter is best-effort, entry point is the source of truth for 401s" idiom from Spring Security's own documentation.

### `AuthPrincipal.java`

```java
public record AuthPrincipal(Long userId, String email, Long tokenVersion) {}
```

Lives in `auth/`. A record because it's immutable, has free `hashCode`/`equals`, and is trivially mockable in tests.

**Consumption convention**: controllers take `@AuthenticationPrincipal AuthPrincipal principal`. Never `@AuthenticationPrincipal UserDetails principal` — that yields `null` because the filter sets an `AuthPrincipal`, not a Spring `UserDetails`. This is a FOOTGUNS-worthy rule (see §14.B1).

---

## §6. Auth services

### `AuthService.java` — orchestration layer

`@Service @RequiredArgsConstructor @Slf4j`. Depends on `UserService`, `UserRepository`, `RefreshTokenService`, `JwtService`, `PasswordEncoder`.

Public methods:

```java
public AuthResult register(RegisterRequest request, HttpServletRequest httpReq);
public AuthResult login(LoginRequest request, HttpServletRequest httpReq);
public AuthResult refresh(String rawRefreshToken, HttpServletRequest httpReq);
public void logout(String rawRefreshToken);
public void logoutAll(Long userId);
```

The `HttpServletRequest` parameters capture `User-Agent` and remote IP for the refresh-token row. The controller does cookie reading/writing; `AuthService` doesn't know cookies exist.

**`register(RegisterRequest, HttpServletRequest)`**:

1. Delegate user creation to `userService.createUser(...)` — this already does BCrypt hashing and the duplicate-email check. Catch the user slice's duplicate-email exception and rethrow as `EmailAlreadyExistsException` so it surfaces with `code: AUTH_EMAIL_EXISTS` via the auth handler.
2. Load the entity via `userRepository.findById(createdId).orElseThrow(...)`. **One extra DB read** — register is not a hot path, and this avoids refactoring the user slice's public API.
3. Build `AuthPrincipal(user.getId(), user.getEmail(), user.getTokenVersion())`.
4. Issue access token via `jwtService.issueAccessToken(principal)`.
5. Issue refresh token via `refreshTokenService.issueForUser(user.getId(), userAgent, ipAddress)`.
6. Return `new AuthResult(accessToken, rawRefreshToken, UserResponse.from(user))`.

**`login(LoginRequest, HttpServletRequest)`**:

1. `userRepository.findByEmail(request.email())` — if absent, throw `InvalidCredentialsException` (401 + `AUTH_INVALID_CREDENTIALS`).
2. `passwordEncoder.matches(request.password(), user.getPasswordHash())` — if false, throw the same exception with the same response shape. BCrypt's constant-time comparison is the timing-attack mitigation — do not add a manual delay.
3. Same principal/access/refresh/result building as register.

**`refresh(String rawRefreshToken, HttpServletRequest)`**:

1. If `rawRefreshToken` is null → throw `TokenInvalidException` → `AUTH_TOKEN_MISSING` 401.
2. Call `refreshTokenService.rotate(rawRefreshToken, userAgent, ipAddress)` — this is the entire rotation + reuse-detection transaction.
3. Load the user (`userRepository.findById(rotationResult.userId()).orElseThrow(...)`) — we need current `tokenVersion` for the new access token's `tv` claim.
4. Issue a new access token with the user's current `tokenVersion`.
5. Return `new AuthResult(newAccessToken, rotationResult.newRawToken(), UserResponse.from(user))`.

**`logout(String rawRefreshToken)`** — **idempotent**:

1. If `rawRefreshToken` is null → return silently. No exception.
2. Call `refreshTokenService.revokeByRawToken(rawRefreshToken)` — internally hashes, looks up, sets `revoked_at` if found and not already revoked. Returns void. Never throws on missing / malformed / already-revoked.
3. Return void. The controller clears the cookie regardless.

**`logoutAll(Long userId)`**:

1. `refreshTokenService.revokeAllForUser(userId)` — single UPDATE statement setting `revoked_at = now()` on every non-revoked row for the user.
2. `userService.bumpTokenVersion(userId)` — increments `users.token_version` so all live access tokens become stale at the next write-path service call.
3. Return void. Controller clears the cookie on the calling device.

### `RefreshTokenService.java` — security-critical layer

`@Service @RequiredArgsConstructor @Slf4j`. Depends on `RefreshTokenRepository`, `JwtConfig` (for lifetime), `UserService` (for the reuse-detection bump).

Public methods:

```java
public IssuedRefreshToken issueForUser(Long userId, String userAgent, String ipAddress);
public RotationResult rotate(String rawToken, String userAgent, String ipAddress);
public void revokeByRawToken(String rawToken);
public int revokeAllForUser(Long userId);
```

Return records:

```java
public record IssuedRefreshToken(String rawToken, OffsetDateTime expiresAt) {}
public record RotationResult(Long userId, String newRawToken, OffsetDateTime newExpiresAt) {}
```

**`issueForUser`**:

1. Generate 32 bytes via `SecureRandom.getInstanceStrong()`.
2. Base64url encode (43 chars, no padding).
3. SHA-256 hash into hex (64 chars).
4. Truncate `userAgent` to 512 chars.
5. Insert a `RefreshToken` row with `expiresAt = now + refreshTokenLifetime`, `lastUsedAt = null`.
6. Return `IssuedRefreshToken(rawToken, expiresAt)`. The raw token is the only place the unhashed value exists after this point (until the cookie reaches the browser).

**`rotate` — the security-critical path**, wrapped in `@Transactional` (default REQUIRED):

```
hash = sha256Hex(rawToken)
row = repository.findByTokenHash(hash)
    .orElseThrow(() -> new TokenInvalidException(AUTH_TOKEN_INVALID))

if (row.revokedAt != null) {
    // REUSE DETECTED — cascade
    log.warn("Refresh token reuse detected for user={}, ip={}, userAgent={}",
             row.userId, ipAddress, userAgent)
    repository.revokeAllByUserId(row.userId, now)
    userService.bumpTokenVersion(row.userId)  // invalidate live access tokens
    throw new RefreshTokenReuseDetectedException(row.userId)  // → AUTH_REFRESH_TOKEN_REUSED 401
}

if (row.expiresAt.isBefore(now)) {
    throw new TokenExpiredException(AUTH_TOKEN_EXPIRED)
}

// Happy path: rotate.
row.lastUsedAt = now
row.revokedAt = now
// Dirty checking flushes both updates on transaction commit.

IssuedRefreshToken newToken = issueForUser(row.userId, userAgent, ipAddress)

return new RotationResult(row.userId, newToken.rawToken(), newToken.expiresAt())
```

**Three writes in one transaction**: the old row's `lastUsedAt` + `revokedAt`, plus the new row insert. Atomic — if any step fails, everything rolls back and the cookie still holds the valid original token.

**Reuse-detection log line**: WARN level with `userId`, remote IP, and User-Agent inline. This is a security event. MDC is overkill for a single event type; an inline format string is fine.

**`revokeByRawToken`** — idempotent, called by logout:

```
hash = sha256Hex(rawToken)
repository.findByTokenHash(hash).ifPresent(row -> {
    if (row.revokedAt == null) {
        row.revokedAt = now
        // dirty checking saves
    }
})
// Never throws. Never logs anything that could leak validity.
```

**`revokeAllForUser`** — single UPDATE for the cascade and the logout-all endpoint:

```java
@Modifying
@Query("UPDATE RefreshToken rt SET rt.revokedAt = :now " +
       "WHERE rt.userId = :userId AND rt.revokedAt IS NULL")
int revokeAllByUserId(@Param("userId") Long userId, @Param("now") OffsetDateTime now);
```

The single-query form is correct and atomic. Do not loop in Java.

### `RefreshTokenCleanupJob.java` — scheduled cleanup

```java
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "auth.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class RefreshTokenCleanupJob {

    private final RefreshTokenRepository refreshTokenRepository;

    @Scheduled(cron = "0 30 3 * * *")  // 03:30 server local time, daily
    @Transactional
    public void cleanupExpiredTokens() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(30);
        int deleted = refreshTokenRepository.deleteOldRows(cutoff);
        log.info("Refresh token cleanup: deleted {} rows older than {}", deleted, cutoff);
    }
}
```

Repository method:

```java
@Modifying
@Query("DELETE FROM RefreshToken rt WHERE "
     + "(rt.revokedAt IS NOT NULL AND rt.revokedAt < :cutoff) OR "
     + "(rt.expiresAt < :cutoff)")
int deleteOldRows(@Param("cutoff") OffsetDateTime cutoff);
```

30 days of audit history is plenty. The `@ConditionalOnProperty` guard with `matchIfMissing = true` means the job runs in dev and prod by default. Integration tests disable it via `@TestPropertySource(properties = "auth.cleanup.enabled=false")` on the base test class.

`@EnableScheduling` must be added to the main application class if it isn't already (confirmed absent during exploration).

---

## §7. Endpoints

### `AuthController.java`

`@RestController @RequestMapping("/api/auth") @RequiredArgsConstructor @Slf4j`. Depends on `AuthService` and `JwtConfig` (for cookie max-age).

```java
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
    String rawRefreshToken = readRefreshCookie(httpReq);  // may be null
    authService.logout(rawRefreshToken);                  // idempotent, never throws
    clearRefreshCookie(httpResp);
}

@PostMapping("/logout-all")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void logoutAll(@AuthenticationPrincipal AuthPrincipal principal,
                      HttpServletResponse httpResp) {
    authService.logoutAll(principal.userId());
    clearRefreshCookie(httpResp);
}
```

### `AuthResult` vs `AuthResponse` — two-record split

**`AuthResult`** (internal, service → controller) carries all three fields including the raw refresh token. Lives in `auth/dto/AuthResult.java`.

```java
/**
 * Internal service-to-controller result. Carries the raw refresh token so the controller
 * can write it to an HttpOnly cookie. MUST NOT be returned in any HTTP response body —
 * that would leak the refresh token into JSON. The external response shape is AuthResponse.
 *
 * This two-record split is load-bearing: the type system prevents refresh-token leakage
 * by making it structurally impossible for a handler that returns AuthResponse to ship
 * the refresh token. See §14.B10.
 */
public record AuthResult(String accessToken, String refreshToken, UserResponse user) {}
```

**`AuthResponse`** (external, controller → client) carries only the access token and user. Lives in `auth/dto/AuthResponse.java`.

```java
/**
 * External client-facing response shape. Does NOT contain the refresh token — that lives
 * in an HttpOnly cookie set by the controller via Set-Cookie header. This record is the
 * only auth-related shape that goes in an HTTP response body.
 *
 * Paired with AuthResult (service-internal). See §14.B10 for the rationale on the two-record split.
 */
public record AuthResponse(String accessToken, UserResponse user) {}
```

The JavaDoc on both records is load-bearing — it's the documentation a future contributor reads before "simplifying" by merging them into one record.

### Cookie helpers

Use Spring's `ResponseCookie` (not `jakarta.servlet.http.Cookie`, which doesn't expose `SameSite`):

```java
private void setRefreshCookie(HttpServletResponse resp, String token, Duration lifetime) {
    ResponseCookie cookie = ResponseCookie.from("refreshToken", token)
        .httpOnly(true)
        .secure(true)
        .sameSite("Lax")
        .path("/api/auth")
        .maxAge(lifetime)
        .build();
    resp.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
}

private void clearRefreshCookie(HttpServletResponse resp) {
    ResponseCookie cookie = ResponseCookie.from("refreshToken", "")
        .httpOnly(true)
        .secure(true)
        .sameSite("Lax")
        .path("/api/auth")
        .maxAge(0)
        .build();
    resp.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
}

private String readRefreshCookie(HttpServletRequest req) {
    if (req.getCookies() == null) return null;
    for (Cookie c : req.getCookies()) {
        if ("refreshToken".equals(c.getName())) return c.getValue();
    }
    return null;
}
```

**`Path=/api/auth` is deliberate and must not be widened.** The scoped path is what makes cookie-only logout CSRF-safe — there is no non-auth endpoint that the cookie ships to, so no cross-site form can weaponize it. Don't put the cookie on `/`. Don't put the cookie on `/api/`. Only `/api/auth`.

### Endpoint contract summary

| Method | Path | Auth mode | Request body | Success response | Error codes |
|---|---|---|---|---|---|
| POST | `/api/auth/register` | none | `{email, password, displayName}` | 201 + `{accessToken, user}` + refresh cookie | 400 `VALIDATION_FAILED`, 409 `AUTH_EMAIL_EXISTS` |
| POST | `/api/auth/login` | none | `{email, password}` | 200 + `{accessToken, user}` + refresh cookie | 400 `VALIDATION_FAILED`, 401 `AUTH_INVALID_CREDENTIALS` |
| POST | `/api/auth/refresh` | HttpOnly cookie | _empty_ | 200 + `{accessToken, user}` + new refresh cookie | 401 `AUTH_TOKEN_MISSING` / `AUTH_TOKEN_INVALID` / `AUTH_TOKEN_EXPIRED` / `AUTH_REFRESH_TOKEN_REUSED` |
| POST | `/api/auth/logout` | HttpOnly cookie (best-effort) | _empty_ | 204 + cleared cookie | _none — always 204_ |
| POST | `/api/auth/logout-all` | `Authorization: Bearer` | _empty_ | 204 + cleared cookie | 401 `AUTH_TOKEN_MISSING` / `AUTH_TOKEN_EXPIRED` |
| GET | `/api/users/me` (existing slice, re-wired) | `Authorization: Bearer` | _N/A_ | 200 + `UserResponse` | 401 `AUTH_TOKEN_MISSING` / `AUTH_TOKEN_EXPIRED` |

---

## §8. SecurityConfig changes

`SecurityConfig.java` is rewritten. Same bean structure, new `authorizeHttpRequests` rules, filter wire, and entry point.

```java
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Matcher ordering matters — see §14.B5. Rules below are exact-match, so current
            // order is safe, but adding a prefix matcher (e.g., /api/auth/**) without
            // understanding the consequences will break the explicit /logout-all authenticated() rule.
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                .requestMatchers(
                    "/api/auth/register",
                    "/api/auth/login",
                    // refresh authenticates via HttpOnly cookie inside the handler, not via SecurityContext
                    "/api/auth/refresh",
                    // logout is idempotent and cookie-authenticated inside the handler (§14.B7)
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
        // Existing implementation from Task 01-01, unchanged except for explicit
        // setAllowCredentials(true) verification (required for the refresh cookie).
        // ...
    }
}
```

Key points:

- **Each auth endpoint is listed by name**, not `/api/auth/**`. A future auth endpoint is not accidentally permit-all'd.
- **`/api/**` catch-all is `authenticated()`** — any future endpoint is protected by default. Public endpoints require explicit `permitAll()`.
- **`anyRequest().denyAll()`** — anything outside `/api/**` returns 403, not 401. Defense in depth.
- **CORS must have `setAllowCredentials(true)`** on the existing `corsConfigurationSource()`. Verify during implementation; add if missing.

---

## §9. Exception handling

### `AuthExceptionHandler.java` — slice handler

```java
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.auth")
@RequiredArgsConstructor
@Slf4j
public class AuthExceptionHandler {

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail handleInvalidCredentials(
            InvalidCredentialsException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.UNAUTHORIZED,
            "Email or password is incorrect.");
        pd.setType(URI.create("https://slpa.example/problems/auth/invalid-credentials"));
        pd.setTitle("Invalid credentials");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "AUTH_INVALID_CREDENTIALS");
        return pd;
    }

    // ... five more handlers following the same pattern, one per auth exception ...
}
```

**`@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.auth")`** scopes the handler to the auth package. The global handler still catches cross-cutting exceptions; this one catches only auth-specific ones. No `@Order` workarounds — handler collisions are a code smell to surface, not paper over.

Handled exceptions and their mappings:

| Exception | Status | `code` | `type` URI suffix |
|---|---|---|---|
| `InvalidCredentialsException` | 401 | `AUTH_INVALID_CREDENTIALS` | `auth/invalid-credentials` |
| `EmailAlreadyExistsException` | 409 | `AUTH_EMAIL_EXISTS` | `auth/email-exists` |
| `TokenExpiredException` | 401 | `AUTH_TOKEN_EXPIRED` | `auth/token-expired` |
| `TokenInvalidException` | 401 | `AUTH_TOKEN_INVALID` | `auth/token-invalid` |
| `RefreshTokenReuseDetectedException` | 401 | `AUTH_REFRESH_TOKEN_REUSED` | `auth/refresh-token-reused` |
| `AuthenticationStaleException` | 401 | `AUTH_STALE_SESSION` | `auth/stale-session` |

All six handlers are explicit. The "DRY it up into a generic builder" instinct is wrong — these are six distinct security responses and the repetition is documentation.

**`RefreshTokenReuseDetectedException` handler** additionally logs at WARN with `userId`, request IP, and User-Agent — see §6 for the log line format.

### `GlobalExceptionHandler.java` — cross-cutting

`@RestControllerAdvice` (no `basePackages` — global scope, but the auth handler claims auth exceptions first). Handles:

- `MethodArgumentNotValidException` → 400 `VALIDATION_FAILED` with `errors[]` extension built from `getBindingResult().getFieldErrors()`
- `HttpMessageNotReadableException` → 400 `MALFORMED_REQUEST`
- `AccessDeniedException` (Spring Security) → 403 `ACCESS_DENIED`
- `AuthenticationException` (Spring Security, when a protected endpoint is reached without auth) → 401 `AUTH_TOKEN_MISSING`
- `ResourceNotFoundException` → 404 `RESOURCE_NOT_FOUND`
- `ResourceGoneException` → 410 `RESOURCE_GONE`
- `Exception.class` fallback → 500 `INTERNAL_SERVER_ERROR`

The auth handler has zero overlap with the global handler. Any future attempt to add an auth-specific case to `GlobalExceptionHandler` is a code smell — surface it and move the handler to `AuthExceptionHandler`.

**Validation errors:**

```java
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
```

**500 fallback with correlation ID:**

```java
@ExceptionHandler(Exception.class)
public ProblemDetail handleGeneric(Exception e, HttpServletRequest req) {
    String correlationId = UUID.randomUUID().toString();
    log.error("Unhandled exception correlationId={} path={}",
              correlationId, req.getRequestURI(), e);
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "An unexpected error occurred.");
    pd.setType(URI.create("https://slpa.example/problems/internal-server-error"));
    pd.setTitle("Internal server error");
    pd.setInstance(URI.create(req.getRequestURI()));
    pd.setProperty("code", "INTERNAL_SERVER_ERROR");
    pd.setProperty("correlationId", correlationId);
    return pd;
}
```

Never leak stack traces, class names, or message contents in the response body. The correlation ID is the bridge: user reports "I got a 500" → support asks for the correlation ID → support greps logs. Four lines, huge operational payoff.

### `JwtAuthenticationEntryPoint.java`

`@Component @RequiredArgsConstructor @Slf4j`. Implements `AuthenticationEntryPoint`. Depends on `ObjectMapper`.

```java
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
```

**The manual serialization is mandatory.** `AuthenticationEntryPoint` runs outside Spring's message converter chain — relying on `@ResponseBody` or `ProblemDetail` auto-conversion yields an empty body. Set content type, status, charset, and `Cache-Control: no-store` explicitly, then serialize via the injected `ObjectMapper`.

**`Cache-Control: no-store`** prevents intermediaries (CDNs, proxies) from caching auth failures. One line, prevents a class of footguns.

---

## §10. `GET /api/users/me` cross-slice change

The existing `UserController.getMe()` 501 stub becomes a real implementation. Same commit as the auth slice changes.

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

The existing `userService.getUserById(Long)` already returns `UserResponse`. No service-layer changes.

**`PUT /api/users/me`** and **`DELETE /api/users/me`** stay 501. Update the comment on each from "JWT auth lands in Task 01-07" to "Profile edit lands in Task 01-XX (TBD) — needs design pass on field-level edit rules and soft-vs-hard delete." Same commit as the GET change.

### User controller test updates

`UserControllerTest` (the existing slice test) replaces the GET /me 501 case with two new cases:

1. `getMe_returnsUserDto_whenAuthenticated()` — uses `@WithMockAuthPrincipal(userId = 1L)`, mocks `userService.getUserById(1L)` to return a `UserResponse`, asserts 200 + JSON body fields.
2. `getMe_returns401_whenUnauthenticated()` — no auth annotation, asserts 401 + ProblemDetail body with `code: "AUTH_TOKEN_MISSING"`.

Both cases live in the same test class as the existing user controller tests, in the same commit as the controller change. The cross-slice touch is contained to one test file plus the controller file.

---

## §11. Test infrastructure

All test infrastructure lives at `backend/src/test/java/com/slparcelauctions/backend/auth/test/`. Built first, before any auth test is written, because every subsequent test depends on it.

### `WithMockAuthPrincipal.java`

```java
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithMockAuthPrincipalSecurityContextFactory.class)
public @interface WithMockAuthPrincipal {
    long userId() default 1L;
    String email() default "test@example.com";
    long tokenVersion() default 0L;
}
```

The `@WithSecurityContext(factory = ...)` element on the annotation is the modern Spring Security 6+ wiring path — **no `META-INF/spring.factories` is needed**. Verify this works at implementation time (it should, per current Spring Security docs). If the annotation is silently ignored in tests, that's the wiring failure mode: fall back to declaring the factory in `META-INF/spring.factories` and document which path was used in `auth/test/README.md`.

### `WithMockAuthPrincipalSecurityContextFactory.java`

```java
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

### `JwtTestFactory.java`

`@Component @RequiredArgsConstructor`. Reads `jwt.secret` via `@Value("${jwt.secret}")`, builds the same `SecretKey` as production via `JwtKeyFactory.buildKey(secret)`. Provides:

```java
public String validAccessToken(AuthPrincipal principal);
public String validAccessTokenWithLifetime(AuthPrincipal principal, Duration lifetime);
public String expiredAccessToken(AuthPrincipal principal);   // exp = 1 minute ago
public String tokenWithWrongType(AuthPrincipal principal);   // type=refresh
public String tokenWithBadSignature();                       // signed with a different key
public String malformedToken();                              // returns "not.a.jwt"
```

Must be a Spring `@Component` to receive `@Value` injection. Tests that don't bring up a Spring context can construct it directly via a test-only static helper if needed, but most tests will `@Autowired` it.

### `RefreshTokenTestFixture.java`

`@Component @RequiredArgsConstructor`. Depends on `RefreshTokenRepository`. Uses the same `sha256Hex` helper as production — factor the hashing into a static utility in `auth/` (e.g., `TokenHasher.sha256Hex(String)`) so prod and test cannot drift. Methods:

```java
public InsertedToken insertValid(Long userId);               // expires 7 days from now
public InsertedToken insertRevoked(Long userId);             // revoked_at = now()
public InsertedToken insertExpired(Long userId);             // expires_at = 1 day ago
public InsertedToken insertWithExpiry(Long userId, OffsetDateTime expiresAt);

public record InsertedToken(Long id, String rawToken, String tokenHash) {}
```

Returns the **raw token** so tests can replay it through `/api/auth/refresh`, plus the `id` for direct DB queries. Tests must assert state by querying the repository, not by inspecting the in-memory object.

### `auth/test/README.md`

One short file documenting:

- Wiring path used for `WithMockAuthPrincipal` (Spring Security 6 annotation form vs `META-INF/spring.factories` fallback)
- Convention: every protected slice test uses `@WithMockAuthPrincipal`, never the manual `MockMvc.with(authentication(...))` form
- `JwtTestFactory` and `JwtKeyFactory` share key derivation; if production key shape changes, update both
- `RefreshTokenTestFixture` uses the same `TokenHasher.sha256Hex` helper as production; changing the hashing algorithm requires updating both call sites

---

## §12. Test plan

Distribution targets (roughly): **~30 unit tests, ~10 slice tests, ~5 integration tests**.

### Unit tests (~30, pure JUnit + Mockito)

**`JwtServiceTest`** (~6 cases):
- `issueAccessToken_producesTokenWithCorrectClaims`
- `parseAccessToken_returnsprincipal_onValidToken`
- `parseAccessToken_throwsExpired_onExpiredToken`
- `parseAccessToken_throwsInvalid_onBadSignature`
- `parseAccessToken_throwsInvalid_onWrongType`
- `parseAccessToken_throwsInvalid_onMalformedToken`

**`JwtKeyFactoryTest`** (~2 cases):
- `buildKey_succeedsWith32ByteSecret`
- `buildKey_throwsOnShortSecret`

**`JwtAuthenticationFilterTest`** (~5 cases, filter invoked directly, no Spring context):
- `doFilter_setsPrincipal_onValidToken`
- `doFilter_clearsContext_onExpiredToken`
- `doFilter_clearsContext_onMalformedToken`
- `doFilter_leavesContextUntouched_onMissingHeader`
- `doFilter_clearsContext_onBadSignature`

**`RefreshTokenServiceTest`** (~10 cases):
- `issueForUser_createsRowWithHashedToken`
- `issueForUser_truncatesLongUserAgent`
- `rotate_happyPath_revokesOldAndInsertsNew`
- `rotate_setsLastUsedAtOnRevokedRow`
- `rotate_rejectsExpiredToken`
- `rotate_onReusedToken_cascadeRevokesAndBumpsTokenVersion`
- `rotate_onUnknownToken_throwsInvalid`
- `revokeByRawToken_isIdempotentOnMissingToken`
- `revokeByRawToken_doesNotDoubleRevoke`
- `revokeAllForUser_returnsRevokedCount`

**`AuthServiceTest`** (~6 cases):
- `register_createsUserAndIssuesTokens`
- `register_rethrowsDuplicateEmailAsAuthException`
- `login_withValidCredentials_returnsResult`
- `login_withInvalidEmail_throwsInvalidCredentials`
- `login_withWrongPassword_throwsInvalidCredentials`
- `logoutAll_revokesAllAndBumpsTokenVersion`

**`UserService.bumpTokenVersionTest`** (~1 case, lives in user slice's existing test class):
- `bumpTokenVersion_incrementsByOne`

### Slice tests (~10, `@WebMvcTest` + `@Import`)

**`AuthControllerTest`** (~7 cases, mocks `AuthService`):
- `register_returns201WithTokenAndUser`
- `register_returns400OnValidationFailure`
- `login_returns200WithTokenAndUser`
- `login_returns401OnInvalidCredentials`
- `refresh_returns200WithNewTokenAndCookie`
- `logout_returns204AlwaysAndClearsCookie`
- `logoutAll_returns204AndClearsCookie_whenAuthenticated`

**`AuthExceptionHandlerTest`** (~6 cases, uses a small in-test `@RestController` fixture that throws each exception):
- `invalidCredentials_mapsToExpectedProblemDetail`
- `emailAlreadyExists_mapsToExpectedProblemDetail`
- `tokenExpired_mapsToExpectedProblemDetail`
- `tokenInvalid_mapsToExpectedProblemDetail`
- `refreshTokenReuseDetected_mapsToExpectedProblemDetail_andLogsWarn`
- `authenticationStale_mapsToExpectedProblemDetail`

**`UserControllerTest` updates** (~2 new cases, replacing the existing 501 case):
- `getMe_returnsUserDto_whenAuthenticated`
- `getMe_returns401_whenUnauthenticated`

**`SecurityConfigTest`** (~2 cases):
- `healthEndpoint_isPublic`
- `meEndpoint_requiresAuth`

### Integration tests (~5, `@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("dev") @Transactional`)

Base class has `@TestPropertySource(properties = "auth.cleanup.enabled=false")` so the cleanup job doesn't race with test data. All tests round-trip through the real HTTP path (real filter chain, real JJWT, real Postgres).

**`AuthFlowIntegrationTest.registerThenUseAccessToken`**:
1. POST `/api/auth/register` → assert 201 + body shape + `Set-Cookie: refreshToken=...`
2. Extract access token from response body
3. GET `/api/users/me` with `Authorization: Bearer <token>`
4. Assert 200 + matching user

**`AuthFlowIntegrationTest.loginThenRefreshThenUseNewToken`**:
1. Register a user (setup)
2. POST `/api/auth/login` → assert 200 + body + cookie
3. POST `/api/auth/refresh` with the cookie
4. Assert new access token in body + new refresh cookie (rotated)
5. Use the new access token on `/api/users/me` → assert 200

**`AuthFlowIntegrationTest.refreshTokenReuseCascade`** — the security-critical one, non-negotiable:

```java
@Test
void refreshTokenReuseCascade_revokesAllSessionsAndBumpsTokenVersion() throws Exception {
    // 1. Register and capture cookie A + access token A
    AuthResponse reg = register("reuse-test@example.com", "hunter22abc");
    String cookieA = extractRefreshCookie(/* response */);
    Long userId = reg.user().id();

    // 2. Refresh once with cookie A → success, capture cookie B + access token B
    MockHttpServletResponse refreshB = mockMvc.perform(post("/api/auth/refresh")
            .cookie(new Cookie("refreshToken", cookieA)))
        .andExpect(status().isOk())
        .andReturn().getResponse();
    String cookieB = extractRefreshCookie(refreshB);

    // 3. Refresh AGAIN with cookie A (now revoked) → 401 + AUTH_REFRESH_TOKEN_REUSED
    mockMvc.perform(post("/api/auth/refresh")
            .cookie(new Cookie("refreshToken", cookieA)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_REUSED"));

    // 4. Query DB: ALL refresh tokens for userId have revoked_at IS NOT NULL
    List<RefreshToken> all = refreshTokenRepository.findAllByUserId(userId);
    assertThat(all).allSatisfy(rt -> assertThat(rt.getRevokedAt()).isNotNull());

    // 5. Cookie B is now also dead (revoked by cascade in step 3)
    mockMvc.perform(post("/api/auth/refresh")
            .cookie(new Cookie("refreshToken", cookieB)))
        .andExpect(status().isUnauthorized());

    // 6. Assert users.token_version was incremented from 0 to 1.
    //
    // End-to-end "stale access token rejected by write-path service" lands in Task 01-XX
    // when BidService ships — we assert the bump here, not the reject. The cascade calls
    // userService.bumpTokenVersion(); the downstream effect on access tokens is the job
    // of the service-layer freshness check, which lands with the first write-path service.
    User user = userRepository.findById(userId).orElseThrow();
    assertThat(user.getTokenVersion()).isEqualTo(1L);
}
```

**`AuthFlowIntegrationTest.logoutThenRefreshFails`**:
1. Register
2. POST `/api/auth/logout` with the cookie
3. Assert 204 + cookie cleared
4. POST `/api/auth/refresh` with the now-revoked cookie
5. Assert 401

**`AuthFlowIntegrationTest.logoutAllInvalidatesAllSessions`**:
1. Register (device 1, captures cookie 1 + access token 1)
2. Seed a second refresh-token row via `RefreshTokenTestFixture.insertValid(userId)` (simulates device 2)
3. POST `/api/auth/logout-all` with access token 1
4. Assert 204
5. Query DB: all refresh tokens revoked, `users.token_version` incremented from 0 to 1
6. POST `/api/auth/refresh` with cookie 1 → 401
7. POST `/api/auth/refresh` with device 2 seeded token → 401

---

## §13. Configuration

### `application.yml` (shared base)

```yaml
jwt:
  secret: ${JWT_SECRET}             # no default — fail-fast on prod startup if missing
  access-token-lifetime: PT15M      # ISO-8601 duration: 15 minutes
  refresh-token-lifetime: P7D       # ISO-8601 duration: 7 days

auth:
  cleanup:
    enabled: true
```

### `application-dev.yml` additions

```yaml
jwt:
  # DEV ONLY — production reads jwt.secret from JWT_SECRET env var.
  # This default must never ship to prod.
  # 32 bytes (256 bits) base64-encoded.
  # Contractor: regenerate this value via `openssl rand -base64 32` before committing.
  # Do not use the placeholder below as-is — it's illustrative, not random.
  secret: REPLACE_WITH_openssl_rand_base64_32_OUTPUT

auth:
  cleanup:
    enabled: true
```

**The placeholder above is deliberate** — the contractor must regenerate the dev secret before committing. A memorable phrase base64-encoded is a footgun (future contributors copy-paste memorable placeholders thinking they're fine); a real random value sets the right tone. The `DEV ONLY` comment is load-bearing: it is the only thing preventing a contributor from thinking "oh, this value works in dev, I'll use it in prod."

### `application-prod.yml` addition

```yaml
jwt:
  secret: ${JWT_SECRET}    # required, fail-fast on startup if missing
```

### `@EnableScheduling` on the main application class

If `@EnableScheduling` isn't already on `SlparcelauctionsBackendApplication` (or whatever the `@SpringBootApplication` class is called), add it. The `RefreshTokenCleanupJob` needs it to be picked up.

---

## §14. FOOTGUNS additions

Add a new top-level section to `docs/implementation/FOOTGUNS.md`: `## §B. Backend / Spring Security / JJWT`.

Rationale for separate section numbering: the frontend ledger's `§1–§6` are frontend-domain-shaped (shell quirks, jsdom gaps, CSS conventions). Backend entries are Java/Spring/JJWT-shaped. Mixing them under one number space muddies search. `§B` keeps the two domains discoverable separately. When the backend ledger grows larger, sub-sections can mirror the frontend pattern (`§B.1`, `§B.2`, etc.) or use a domain-flat list — both work.

### §B entries to add in this task

**§B.1 — `@AuthenticationPrincipal AuthPrincipal principal`, never `UserDetails`.**
The Spring Security tutorial default principal type is `UserDetails`. Reaching for it in this codebase yields `null` because `JwtAuthenticationFilter` sets an `AuthPrincipal` record, not a `UserDetails`. Convention: every controller takes `@AuthenticationPrincipal AuthPrincipal principal`. A backend grep verify rule (mirror of the frontend's `npm run verify` chain) should flag `@AuthenticationPrincipal UserDetails` as a build break — implementation of the rule is a follow-on task; for now, code review enforces it.

**§B.2 — `AuthenticationEntryPoint` bypasses the message converter chain.**
`AuthenticationEntryPoint.commence()` runs outside Spring's `@ResponseBody` / `ProblemDetail` auto-conversion. Wiring it expecting Spring to format the JSON yields an empty 401 body. The entry point must set `Content-Type: application/problem+json` and serialize the `ProblemDetail` via injected `ObjectMapper` directly. Also set `Cache-Control: no-store` to prevent CDN/proxy caching of auth failures.

**§B.3 — `WithSecurityContextFactory` wiring in Spring Security 6.**
Use `@WithSecurityContext(factory = ...)` directly on the custom annotation — this is the modern auto-discovery path. `META-INF/spring.factories` is the legacy fallback. If `@WithMockAuthPrincipal` appears to do nothing in tests, that's the wiring failure mode: verify the annotation has `@WithSecurityContext(factory = WithMockAuthPrincipalSecurityContextFactory.class)` and that the factory class is on the classpath.

**§B.4 — `jwt.secret` must be present in every active profile.**
`JwtConfig.@PostConstruct` validates on startup and throws if `jwt.secret` is missing or shorter than 256 bits. Adding an `application-test.yml` later for unrelated reasons must inherit or re-declare `jwt.secret` or every test fails before the first assertion runs. Documented in `auth/test/README.md` and enforced by the fail-fast validation.

**§B.5 — `SecurityConfig` matcher ordering.**
Spring Security matches `requestMatchers` rules in declaration order. The current ordering is safe because every rule is exact-match (no prefix wildcards). **Adding a prefix matcher** (e.g., `/api/auth/**`) **without understanding the consequences will break** the explicit `/api/auth/logout-all authenticated()` rule — the prefix rule would swallow it unless declared after. Reordering or adding prefix matchers requires understanding this constraint. Comment in `SecurityConfig` documents it inline.

**§B.6 — Refresh token reuse cascade is the entire reason DB-backed tokens are worth the cost.**
If a future contributor "optimizes" the refresh path by skipping the revoked-row check, they've degraded the auth slice from "stateful with revocation" to "stateless without it" without realizing. The `AuthFlowIntegrationTest.refreshTokenReuseCascade` integration test is the canary — **it must never be deleted.** Removing it is equivalent to removing the security feature.

**§B.7 — Logout endpoint idempotency.**
`POST /api/auth/logout` must **always** return 204, even if the cookie is missing / malformed / expired / revoked / points to another user's token. Throwing a 401 on an already-revoked token would create a "is this token still alive?" oracle through the logout endpoint, which defeats the hashing-at-rest design (an attacker who has a bunch of candidate raw tokens can test them via logout response codes). Never differentiate the response based on token state.

**§B.8 — Refresh token raw value never lives in the DB.**
Only the SHA-256 hash is stored. If a future migration or debug feature adds a `raw_token` column, roll it back immediately. The entire security model depends on the DB leaking hashes, not credentials, if it leaks. "Temporary debug column" is not an acceptable reason to break this.

**§B.9 — JJWT 0.12+ API is different from 0.11.**
Stack Overflow and older Spring Boot guides show JJWT 0.11 syntax: `Jwts.builder().setSubject(...)` and `signWith(SignatureAlgorithm.HS256, secret)`. These will not compile against 0.12. The 0.12 form is `Jwts.builder().subject(...)` and `signWith(secretKey)` with the algorithm inferred from the key. Use `Jwts.parser().verifyWith(key).build().parseSignedClaims(token)` for parsing, not the deprecated `Jwts.parserBuilder()`. When copying JJWT examples from the internet, check the library version in the example first.

**§B.10 — `AuthResult` (service-internal) vs `AuthResponse` (controller-external) two-record split is load-bearing.**
The split exists so the type system catches any code path that would put a refresh token in a JSON body. Do not merge them "for simplicity." A future contributor who adds a `refreshToken` field to the external `AuthResponse` has removed the structural guard that keeps refresh tokens out of response bodies. The JavaDoc on both records documents this — don't delete the JavaDoc either.

---

## §15. Out of scope

Things this task explicitly does NOT do, listed because the implementer subagent will be tempted by some of them.

- **`PUT /api/users/me`** and **`DELETE /api/users/me`** — stay 501. Comment updates to "Profile edit lands in Task 01-XX (TBD)." Field-level edit rules and soft-vs-hard delete need their own design pass.
- **Email verification** — `users.email_verified` exists from Task 01-04 but no verification flow. Future task.
- **Password change endpoint** — future task. `UserService.bumpTokenVersion()` is shipped now in anticipation; `logout-all` is its only Task 01-07 caller.
- **Forgot password / password reset** — future task.
- **Account lockout after N failed login attempts** — future task. Current behavior is "every login attempt is independent." Brute-force protection needs its own design pass (rate limiting, captcha, lockout policy).
- **OAuth / SSO providers** — never in scope for SLPA. Self-issued JWTs only.
- **Two-factor authentication** — future task, well after MVP.
- **Session-listing UI / "active sessions" endpoint** — the data model supports it (`last_used_at`, `user_agent`, `ip_address` are captured) but no endpoint surfaces it. Future task.
- **End-to-end verification that a write-path service rejects a stale access token after the reuse cascade** — this requires a write-path service that runs the freshness check. No such service exists in this task. The cascade test asserts `users.token_version` was incremented; the full loop closes with the first write-path task.
- **`logout-all` cookie clearing on other devices** — only the calling device's cookie is cleared server-side. Other devices' browsers still hold their cookies until they're sent on the next refresh attempt, which fails (revoked). This is normal HTTP semantics, not a gap.
- **Scheduled job interference with tests** — solved by `auth.cleanup.enabled=false` in test properties. Documented, not a feature.
- **Backend `npm run verify` equivalent** — the §B.1 FOOTGUNS entry calls for a grep rule flagging `@AuthenticationPrincipal UserDetails`. Implementing the rule is a follow-on task; for now, code review enforces it.
- **Frontend integration** — Task 01-08 wires the frontend's `useAuth()` hook to these endpoints. Key notes for the 01-08 brief:
  - Bootstrap flow is `POST /api/auth/refresh` on app mount; success sets `{ status: "authenticated", user }`, 401 sets `{ status: "unauthenticated" }`, in-flight is `loading`.
  - The "log out all sessions" UI must do refresh-then-logout-all transparently because `logout-all` requires a valid access token; if the user's access token is already expired, the click otherwise fails.
  - All auth endpoints take `credentials: "include"` — already wired in `lib/api.ts` from Task 01-06.

---

## Self-review checklist

Applied inline before committing:

- **Placeholder scan** — no `TBD`, `TODO`, or incomplete sections. The dev `jwt.secret` placeholder is deliberate (contractor regenerates via `openssl rand -base64 32`); documented as such.
- **Internal consistency** — the `AuthResult` / `AuthResponse` split is described in §3 (package structure), §7 (controller + records), and §14 (footgun). Cross-references are consistent. The `tokenVersion` story is consistent across §1, §2, §4, §6, §10, and §12 (test).
- **Scope check** — one vertical slice (auth) with two documented cross-slice touches (user.tokenVersion column, GET /me implementation). Appropriately sized for a single implementation plan.
- **Ambiguity check** — every decision that had two reasonable answers in brainstorming is now locked to one. The `@WithSecurityContext` wiring path has a documented fallback for the one case where the primary path fails, but the primary path is specified.

---

## Next step

Invoke the `writing-plans` skill to produce the task-by-task implementation plan. The plan lives at `docs/superpowers/plans/2026-04-11-task-01-07-jwt-auth-backend.md` on the same branch.
