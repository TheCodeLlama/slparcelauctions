# Username Login Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the email-based login identifier with a Unicode-permissive `username` field. Email column stays dormant. Forgot-password page is removed as a reachable surface; component code stays for re-use when email returns.

**Architecture:** Single source-of-truth `users.username` column (`varchar(64) NOT NULL`), case-preserved, NFC-normalized, whitespace-collapsed at write. Functional unique index `LOWER(username)` provides case-insensitive uniqueness. Backend service layer normalizes input; repository lookup uses `LOWER()` JPQL. JWT claim renamed `email` → `username`.

**Tech Stack:** Spring Boot 4 / Java 26 / Hibernate / Flyway / PostgreSQL / Next.js 16 / React 19 / Zod / TypeScript 5.

**Spec:** `docs/superpowers/specs/2026-05-05-username-login-design.md`.

---

### Task 1: Flyway V14 migration

**Files:**
- Create: `backend/src/main/resources/db/migration/V14__username_field.sql`

- [ ] **Step 1: Write the migration**

```sql
-- V14: add username login identifier.
--
-- Adds users.username (case-preserved, NFC-normalized, trimmed, internal-
-- whitespace-collapsed at the application layer). Uniqueness is case-
-- insensitive via a functional unique index on LOWER(username).
--
-- The DB is wiped before this ships (paying-customers-mode hasn't started),
-- so this migration runs against a freshly-created schema where the users
-- table has no rows. NOT NULL without DEFAULT is therefore safe.
--
-- The pre-existing UNIQUE on users.email (V1 constraint
-- uk6dotkott2kjsp8vw4d0m25fb7) is intentionally left in place — email stays
-- in the model as a future-notification channel and "two accounts can't
-- claim the same email address" remains correct.

ALTER TABLE users
    ADD COLUMN username varchar(64) NOT NULL;

CREATE UNIQUE INDEX users_username_lower
    ON users (LOWER(username));
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/resources/db/migration/V14__username_field.sql
git commit -m "feat(db): V14 username column + functional unique index"
```

---

### Task 2: User entity + UserResponse DTO

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/User.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/dto/UserResponse.java`

- [ ] **Step 1: Add `username` field to `User`**

Replace the `email` field declaration block with:

```java
@Column(unique = true)
private String email;

@Column(nullable = false, length = 64)
private String username;
```

(Keep `email` exactly as-is — only adding `username` underneath it.)

- [ ] **Step 2: Add `username` to `UserResponse`**

Insert `String username,` between `UUID publicId,` and `String email,` in the record declaration. Insert `user.getUsername(),` between `user.getPublicId(),` and `user.getEmail(),` in BOTH `from(...)` factory bodies.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/user/User.java backend/src/main/java/com/slparcelauctions/backend/user/dto/UserResponse.java
git commit -m "feat(user): add username field to entity and response DTO"
```

---

### Task 3: AuthPrincipal + JwtService

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auth/AuthPrincipal.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auth/JwtService.java`

- [ ] **Step 1: Rename `email` → `username` in `AuthPrincipal`**

Replace the record declaration's `String email,` with `String username,`. Update the JavaDoc reference if it mentions email by name.

- [ ] **Step 2: Update `JwtService.issueAccessToken`**

Replace `.claim("email", principal.email())` with `.claim("username", principal.username())`.

- [ ] **Step 3: Update `JwtService.parseAccessToken`**

Replace:
```java
String email = (String) claims.get("email");
```
with:
```java
String username = (String) claims.get("username");
```
And the `return new AuthPrincipal(...)` line: pass `username` in place of `email`.

- [ ] **Step 4: Update class JavaDoc on `JwtService`**

Replace `{@code email}` with `{@code username}` in the "Access token claims:" comment paragraph.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auth/AuthPrincipal.java backend/src/main/java/com/slparcelauctions/backend/auth/JwtService.java
git commit -m "feat(auth): rename JWT email claim to username"
```

---

### Task 4: Auth DTOs + new UsernameAlreadyExistsException

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auth/dto/LoginRequest.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auth/dto/RegisterRequest.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/dto/CreateUserRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auth/exception/UsernameAlreadyExistsException.java`
- Delete: `backend/src/main/java/com/slparcelauctions/backend/auth/exception/EmailAlreadyExistsException.java`

- [ ] **Step 1: Rewrite `LoginRequest`**

```java
package com.slparcelauctions.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auth/login}.
 *
 * <p>Username is matched case-insensitively against {@code users.username} via
 * the functional unique index on {@code LOWER(username)}. Server normalizes
 * leading/trailing whitespace before lookup, so the user can sign in with the
 * casing or padding they typed at registration without the field rejecting them.
 */
public record LoginRequest(
        @NotBlank
        @Size(min = 3, max = 64)
        @Pattern(
                regexp = "^[\\p{L}\\p{M}\\p{N}\\p{P}\\p{S} ]+$",
                message = "username may only contain letters, digits, marks, punctuation, symbols, and spaces")
        String username,

        @NotBlank @Size(max = 255) String password) {
}
```

- [ ] **Step 2: Rewrite `RegisterRequest`** (drop `displayName`)

```java
package com.slparcelauctions.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auth/register}.
 *
 * <p>Password constraints mirror {@link com.slparcelauctions.backend.user.dto.CreateUserRequest}.
 */
public record RegisterRequest(
        @NotBlank
        @Size(min = 3, max = 64)
        @Pattern(
                regexp = "^[\\p{L}\\p{M}\\p{N}\\p{P}\\p{S} ]+$",
                message = "username may only contain letters, digits, marks, punctuation, symbols, and spaces")
        String username,

        @NotBlank
        @Size(min = 10, max = 255)
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*[\\d\\W]).{10,}$",
                message = "password must contain at least one letter and at least one digit or symbol")
        String password) {
}
```

- [ ] **Step 3: Rewrite `CreateUserRequest`** (drop `displayName`)

```java
package com.slparcelauctions.backend.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank
        @Size(min = 3, max = 64)
        @Pattern(
                regexp = "^[\\p{L}\\p{M}\\p{N}\\p{P}\\p{S} ]+$",
                message = "username may only contain letters, digits, marks, punctuation, symbols, and spaces")
        String username,

        // At least 10 characters, must contain at least one letter and at least
        // one digit or non-alphanumeric character. Length cap matches the
        // password_hash column width upstream of BCrypt encoding. This is the
        // floor for self-registration; we tighten further (entropy / breach
        // checks) once we have real users to migrate.
        @NotBlank
        @Size(min = 10, max = 255)
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*[\\d\\W]).{10,}$",
                message = "password must contain at least one letter and at least one digit or symbol")
        String password) {
}
```

- [ ] **Step 4: Create `UsernameAlreadyExistsException`**

```java
package com.slparcelauctions.backend.auth.exception;

/**
 * Thrown by {@code AuthService.register} when the username is already in use. Maps to
 * 409 AUTH_USERNAME_EXISTS via {@code AuthExceptionHandler}.
 */
public class UsernameAlreadyExistsException extends RuntimeException {
    public UsernameAlreadyExistsException(String username) {
        super("An account with username " + username + " already exists.");
    }
}
```

- [ ] **Step 5: Delete `EmailAlreadyExistsException`**

```bash
rm backend/src/main/java/com/slparcelauctions/backend/auth/exception/EmailAlreadyExistsException.java
```

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auth/dto/LoginRequest.java backend/src/main/java/com/slparcelauctions/backend/auth/dto/RegisterRequest.java backend/src/main/java/com/slparcelauctions/backend/user/dto/CreateUserRequest.java backend/src/main/java/com/slparcelauctions/backend/auth/exception/UsernameAlreadyExistsException.java
git add -u backend/src/main/java/com/slparcelauctions/backend/auth/exception/EmailAlreadyExistsException.java
git commit -m "feat(auth): swap email for username in request DTOs and exception types"
```

---

### Task 5: UserAlreadyExistsException factory + UserRepository + UserService

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/UserAlreadyExistsException.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/UserRepository.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/UserService.java`

- [ ] **Step 1: Replace static factory in `UserAlreadyExistsException`**

```java
package com.slparcelauctions.backend.user;

public class UserAlreadyExistsException extends RuntimeException {

    public UserAlreadyExistsException(String message) {
        super(message);
    }

    public static UserAlreadyExistsException username(String username) {
        return new UserAlreadyExistsException("User with username already exists: " + username);
    }
}
```

- [ ] **Step 2: Update `UserRepository`**

Replace these three lookup methods:

```java
Optional<User> findByEmail(String email);
boolean existsByEmail(String email);
```

with:

```java
@Query("SELECT u FROM User u WHERE LOWER(u.username) = LOWER(:username)")
Optional<User> findByUsername(@Param("username") String username);

@Query("SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END FROM User u WHERE LOWER(u.username) = LOWER(:username)")
boolean existsByUsername(@Param("username") String username);
```

Replace `bulkPromoteByEmailIfUser` with:

```java
@Modifying
@Transactional
@Query("UPDATE User u SET u.role = com.slparcelauctions.backend.user.Role.ADMIN " +
       "WHERE LOWER(u.username) IN :usernames AND u.role = com.slparcelauctions.backend.user.Role.USER")
int bulkPromoteByUsernameIfUser(@Param("usernames") List<String> lowercaseUsernames);
```

Update `searchAdmin` JPQL — replace:
```
LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%'))
```
with:
```
LOWER(u.username) LIKE LOWER(CONCAT('%', :search, '%'))
```

- [ ] **Step 3: Update `UserService.createUser`**

Replace the method body with:

```java
@Transactional
public UserResponse createUser(CreateUserRequest request) {
    String normalized = normalizeUsername(request.username());
    if (userRepository.existsByUsername(normalized)) {
        throw UserAlreadyExistsException.username(normalized);
    }

    User user = User.builder()
            .username(normalized)
            .passwordHash(passwordEncoder.encode(request.password()))
            .build();

    try {
        User saved = userRepository.save(user);
        userRepository.flush();
        log.info("Created user id={} username={}", saved.getId(), saved.getUsername());
        return UserResponse.from(saved);
    } catch (org.springframework.dao.DataIntegrityViolationException e) {
        throw UserAlreadyExistsException.username(normalized);
    }
}

/**
 * Normalize a username for storage and lookup: trim, NFC-normalize, then
 * collapse runs of internal whitespace to a single regular space. The
 * resulting string is case-preserved — case-insensitivity is enforced by
 * {@code LOWER(username)} at query and unique-index time, never at the app
 * layer.
 */
private static String normalizeUsername(String raw) {
    String nfc = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFC);
    String trimmed = nfc.trim();
    return trimmed.replaceAll("\\s+", " ");
}
```

Add the imports if missing: `java.text.Normalizer`, `org.springframework.dao.DataIntegrityViolationException`. Note we removed the `.displayName(...)` call — the entity's `displayName` field stays in the schema (set via the future profile-edit UI), it just isn't seeded at registration.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/user/UserAlreadyExistsException.java backend/src/main/java/com/slparcelauctions/backend/user/UserRepository.java backend/src/main/java/com/slparcelauctions/backend/user/UserService.java
git commit -m "feat(user): username-based create + lookup with NFC normalize"
```

---

### Task 6: AuthService + AuthExceptionHandler

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auth/AuthService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auth/exception/AuthExceptionHandler.java`

- [ ] **Step 1: Update `AuthService` imports**

Remove `import com.slparcelauctions.backend.auth.exception.EmailAlreadyExistsException;`. Add `import com.slparcelauctions.backend.auth.exception.UsernameAlreadyExistsException;`.

- [ ] **Step 2: Rewrite `AuthService.register`**

```java
public AuthResult register(RegisterRequest request, HttpServletRequest httpReq) {
    String ip = httpReq.getRemoteAddr();
    banCheckService.assertNotBanned(ip, null);
    UserResponse created;
    try {
        created = userService.createUser(
                new CreateUserRequest(request.username(), request.password()));
    } catch (UserAlreadyExistsException e) {
        throw new UsernameAlreadyExistsException(request.username());
    }

    User user = userRepository.findByPublicId(created.publicId())
            .orElseThrow(() -> new IllegalStateException(
                    "User disappeared immediately after creation: publicId=" + created.publicId()));

    return buildResult(user, httpReq);
}
```

- [ ] **Step 3: Rewrite `AuthService.login`**

```java
public AuthResult login(LoginRequest request, HttpServletRequest httpReq) {
    String ip = httpReq.getRemoteAddr();
    banCheckService.assertNotBanned(ip, null);
    User user = userRepository.findByUsername(request.username())
            .orElseThrow(InvalidCredentialsException::new);

    if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
        throw new InvalidCredentialsException();
    }

    return buildResult(user, httpReq);
}
```

- [ ] **Step 4: Update `AuthService.refresh` and `buildResult` to pass username**

In both methods, change every `new AuthPrincipal(user.getId(), user.getPublicId(), user.getEmail(), user.getTokenVersion(), user.getRole())` to use `user.getUsername()` in place of `user.getEmail()`.

- [ ] **Step 5: Update method JavaDoc on register/login**

Replace any "email" references in the JavaDoc with "username". Update `@throws` line for `register` to reference `UsernameAlreadyExistsException`.

- [ ] **Step 6: Update `AuthExceptionHandler`**

Replace the `EmailAlreadyExistsException` handler with:

```java
@ExceptionHandler(UsernameAlreadyExistsException.class)
public ProblemDetail handleUsernameExists(UsernameAlreadyExistsException e, HttpServletRequest req) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(
        HttpStatus.CONFLICT, e.getMessage());
    pd.setType(URI.create("https://slpa.example/problems/auth/username-exists"));
    pd.setTitle("Username already taken");
    pd.setInstance(URI.create(req.getRequestURI()));
    pd.setProperty("code", "AUTH_USERNAME_EXISTS");
    return pd;
}
```

Remove the `import com.slparcelauctions.backend.auth.exception.EmailAlreadyExistsException;` line.
Add `import com.slparcelauctions.backend.auth.exception.UsernameAlreadyExistsException;`.

Update the invalid-credentials detail in `handleInvalidCredentials`: change `"Email or password is incorrect."` → `"Username or password is incorrect."`.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auth/AuthService.java backend/src/main/java/com/slparcelauctions/backend/auth/exception/AuthExceptionHandler.java
git commit -m "feat(auth): login/register by username, swap exception mapping"
```

---

### Task 7: Admin bootstrap (Properties + Initializer + application.yml)

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/AdminBootstrapProperties.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/AdminBootstrapInitializer.java`
- Modify: `backend/src/main/resources/application.yml`

- [ ] **Step 1: Rewrite `AdminBootstrapProperties`**

Replace the field declaration:

```java
private List<String> bootstrapEmails = List.of();
```

with:

```java
private List<String> bootstrapUsernames = List.of();
```

(Lombok `@Getter`/`@Setter` regenerates the accessors.)

- [ ] **Step 2: Update `AdminBootstrapInitializer`**

Replace the JavaDoc and method body:

```java
/**
 * On startup, promotes any user whose username matches the configured
 * bootstrap-usernames list AND whose current role is USER. The
 * promote-only-currently-USER guard is a forward push at startup, not a
 * configurable opt-out — a deliberately-demoted bootstrap username will be
 * re-promoted on the next restart unless removed from the config list.
 * Matching is case-insensitive (the JPQL lowercases both sides).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrapInitializer {

    private final UserRepository userRepository;
    private final AdminBootstrapProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void promoteBootstrapAdmins() {
        if (properties.getBootstrapUsernames().isEmpty()) {
            log.info("Admin bootstrap: no bootstrap-usernames configured, skipping.");
            return;
        }
        List<String> lowercased = properties.getBootstrapUsernames().stream()
                .map(s -> s.toLowerCase(java.util.Locale.ROOT))
                .toList();
        int promoted = userRepository.bulkPromoteByUsernameIfUser(lowercased);
        log.info("Admin bootstrap: promoted {} of {} configured usernames to ADMIN.",
                promoted, properties.getBootstrapUsernames().size());
    }
}
```

(Add the `java.util.List` and `java.util.Locale` imports if not already present.)

- [ ] **Step 3: Update `application.yml`**

Replace:

```yaml
  admin:
    bootstrap-emails:
      - heath@slparcels.com
      - heath@slparcelauctions.com
```

with:

```yaml
  admin:
    bootstrap-usernames:
      - heath
```

(Heath bootstraps as `heath`. Add additional usernames here as needed; the list is the authoritative seed for ADMIN promotion at startup.)

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/admin/AdminBootstrapProperties.java backend/src/main/java/com/slparcelauctions/backend/admin/AdminBootstrapInitializer.java backend/src/main/resources/application.yml
git commit -m "feat(admin): bootstrap by username instead of email"
```

---

### Task 8: Backend test sweep

**Files:** every backend test that creates a user via `User.builder().email(...)` or posts JSON with `"email":` to register/login. Compile failures from Tasks 2–7 will surface most of them. Use grep to find the rest.

- [ ] **Step 1: Inventory the affected test files**

Run:

```bash
cd backend && grep -rl --include="*.java" -e '\.email(' -e '"email"' src/test
```

This produces the working list. Expected ~30 files including: `AuthFlowIntegrationTest`, `AuthServiceTest`, `AuthExceptionHandlerTest`, `UserIntegrationTest`, `UserServiceTest`, `AvatarUploadFlowIntegrationTest`, `SlParcelVerifyControllerIntegrationTest`, `SlVerificationFlowIntegrationTest`, `DevSlSimulateIntegrationTest`, `NotificationControllerIntegrationTest`, `FullFlowSmokeTest`, `BotTaskControllerIntegrationTest`, `DevBotTaskControllerTest`, `MeWalletPayListingFeeTest`, `SavedAuctionControllerIntegrationTest`, `MyBidsIntegrationTest`, `SnipeAndBuyNowIntegrationTest`, `ProxyBidResurrectionTest`, `ProxyBidTieFlipTest`, `ProxyBidControllerTest`, `ParcelLockingRaceIntegrationTest`, `DevAuctionControllerTest`, `BidVsProxyCounterIntegrationTest`, `BidPlacementIntegrationTest`, `AuctionPhotoControllerIntegrationTest`, `AuctionControllerIntegrationTest`, `AdminBootstrapIntegrationTest`.

- [ ] **Step 2: Edit replacement strategy**

For `User.builder()` fixtures, replace each `.email("alice@example.com")` with a username form like `.username("alice")` (use the local-part of the previous email — that keeps fixtures human-readable). Drop any `.displayName(...)` chain that depended on the old `CreateUserRequest` constructor signature only if the test directly constructs `CreateUserRequest`.

For JSON payload fixtures (e.g. `{"email":"alice@example.com","password":"..."}`), replace the JSON key `"email"` with `"username"` and the value with the username form.

For `AdminBootstrapIntegrationTest`:
- `slpa.admin.bootstrap-emails[0]=present-user@bootstrap.test` → `slpa.admin.bootstrap-usernames[0]=present-user`
- `slpa.admin.bootstrap-emails[1]=present-admin@bootstrap.test` → `slpa.admin.bootstrap-usernames[1]=present-admin`
- `slpa.admin.bootstrap-emails[2]=absent@bootstrap.test` → `slpa.admin.bootstrap-usernames[2]=absent-user`

For `AuthExceptionHandlerTest`:
- `EmailAlreadyExistsException` references → `UsernameAlreadyExistsException`
- `AUTH_EMAIL_EXISTS` assertions → `AUTH_USERNAME_EXISTS`
- problem URI `auth/email-exists` → `auth/username-exists`
- "Email or password is incorrect." assertions → "Username or password is incorrect."

- [ ] **Step 3: Run the build to surface compile errors**

```bash
cd backend && ./mvnw -q -DskipTests compile test-compile
```

Iterate on remaining compile errors until clean.

- [ ] **Step 4: Run the full test suite**

```bash
cd backend && ./mvnw -q test
```

Fix any test-time failures. Most likely sources: `AuthExceptionHandlerTest` body assertions, JWT claim assertions in `JwtServiceTest`/`AuthServiceTest`.

- [ ] **Step 5: Commit**

```bash
git add -u backend/src/test
git commit -m "test(backend): sweep email-based fixtures to username"
```

---

### Task 9: Frontend schemas + errors + api

**Files:**
- Modify: `frontend/src/lib/auth/schemas.ts`
- Modify: `frontend/src/lib/auth/errors.ts`
- Modify: `frontend/src/lib/auth/api.ts`

- [ ] **Step 1: Update `schemas.ts`**

Add a `usernameSchema` and rewire `loginSchema` and `registerSchema`:

```ts
// frontend/src/lib/auth/schemas.ts
import { z } from "zod";

/**
 * Email schema — only used by the orphan forgotPasswordSchema today, kept
 * for when email-based recovery returns. Do NOT use for login/register.
 */
export const emailSchema = z
  .string()
  .min(1, "Email is required")
  .email("Enter a valid email")
  .max(255);

/**
 * Username schema (login + register).
 *
 * Server is the source of truth — this regex is cosmetic UX. The server
 * accepts any printable Unicode (letters, marks, numbers, punctuation,
 * symbols, regular space) up to 64 codepoints after NFC + trim + whitespace
 * collapse. Frontend mirrors the regex and adds two cosmetic refinements
 * (no leading/trailing spaces, no double spaces) so the user is told
 * sooner.
 */
export const usernameSchema = z
  .string()
  .min(3, "At least 3 characters")
  .max(64, "At most 64 characters")
  .regex(/^[\p{L}\p{M}\p{N}\p{P}\p{S} ]+$/u, "Disallowed character")
  .refine((v) => v.trim() === v, "No leading or trailing spaces")
  .refine((v) => !/\s{2,}/.test(v), "Collapse multiple spaces to one");

/**
 * Password schema for CREATION (register form). Mirrors the backend regex
 * exactly:
 *   ^(?=.*[A-Za-z])(?=.*[\d\W]).{10,}$
 * 10+ chars, at least one letter, at least one digit OR symbol.
 */
export const passwordCreateSchema = z
  .string()
  .min(10, "At least 10 characters")
  .regex(
    /^(?=.*[A-Za-z])(?=.*[\d\W]).{10,}$/,
    "Must contain a letter and a digit or symbol"
  )
  .max(255);

/**
 * Password schema for INPUT (login form). Just non-empty.
 *
 * KEEP THIS DISTINCT FROM passwordCreateSchema. Login is checking credentials,
 * not creating a new password — a pre-existing user with a 6-character password
 * (from before regex tightening) must still be able to log in. A contributor
 * who "unifies" them breaks login for legacy passwords.
 */
export const passwordInputSchema = z
  .string()
  .min(1, "Password is required")
  .max(255);

/**
 * Register form schema. Composes username + passwordCreate + confirmPassword + terms.
 * Cross-field validation: passwords must match.
 */
export const registerSchema = z
  .object({
    username: usernameSchema,
    password: passwordCreateSchema,
    confirmPassword: z.string().min(1, "Confirm your password"),
    terms: z.literal(true, {
      errorMap: () => ({ message: "You must accept the terms" }),
    }),
  })
  .refine((data) => data.password === data.confirmPassword, {
    message: "Passwords don't match",
    path: ["confirmPassword"],
  });

/**
 * Login form schema. Username + non-empty password.
 */
export const loginSchema = z.object({
  username: usernameSchema,
  password: passwordInputSchema,
});

/**
 * Forgot-password form schema. Email only.
 *
 * ORPHAN: the page that mounts this is removed; component code stays for the
 * day email-based recovery returns. Do not delete.
 */
export const forgotPasswordSchema = z.object({
  email: emailSchema,
});

export type RegisterFormValues = z.infer<typeof registerSchema>;
export type LoginFormValues = z.infer<typeof loginSchema>;
export type ForgotPasswordFormValues = z.infer<typeof forgotPasswordSchema>;
```

- [ ] **Step 2: Update `errors.ts`**

Find the two email-specific branches and replace:

```ts
if (code === "AUTH_EMAIL_EXISTS") {
  form.setError("email" as Path<T>, {
    type: "server",
    message: "An account with this email already exists. Sign in instead?",
  });
  return;
}

if (code === "AUTH_INVALID_CREDENTIALS") {
  form.setError("root.serverError" as Path<T>, {
    type: "server",
    message: "Email or password is incorrect.",
  });
  return;
}
```

with:

```ts
if (code === "AUTH_USERNAME_EXISTS") {
  form.setError("username" as Path<T>, {
    type: "server",
    message: "That username is already taken.",
  });
  return;
}

if (code === "AUTH_INVALID_CREDENTIALS") {
  form.setError("root.serverError" as Path<T>, {
    type: "server",
    message: "Username or password is incorrect.",
  });
  return;
}
```

Update the JSDoc comment block at the top of the file: replace `AUTH_EMAIL_EXISTS (409) → field-level on `email`` with `AUTH_USERNAME_EXISTS (409) → field-level on `username``.

- [ ] **Step 3: Update `api.ts`**

```ts
export type RegisterRequest = {
  username: string;
  password: string;
};

export type LoginRequest = {
  username: string;
  password: string;
};
```

(Drop `displayName` from `RegisterRequest` — backend no longer accepts it.)

- [ ] **Step 4: Commit**

```bash
git add frontend/src/lib/auth/schemas.ts frontend/src/lib/auth/errors.ts frontend/src/lib/auth/api.ts
git commit -m "feat(frontend): username schemas, error mapping, and API types"
```

---

### Task 10: LoginForm + RegisterForm

**Files:**
- Modify: `frontend/src/components/auth/LoginForm.tsx`
- Modify: `frontend/src/components/auth/RegisterForm.tsx`

- [ ] **Step 1: Update `LoginForm.tsx`**

Replace `KNOWN_FIELDS`, `defaultValues`, and the email Input with:

```tsx
const KNOWN_FIELDS = ["username", "password"] as const;
```

```tsx
const form = useForm<LoginFormValues>({
  resolver: zodResolver(loginSchema),
  mode: "onBlur",
  reValidateMode: "onChange",
  defaultValues: { username: "", password: "" },
});
```

```tsx
<Input
  label="Username"
  type="text"
  autoComplete="username"
  {...form.register("username")}
  error={form.formState.errors.username?.message}
/>
```

- [ ] **Step 2: Update `RegisterForm.tsx`**

```tsx
const KNOWN_FIELDS = ["username", "password", "confirmPassword", "terms"] as const;
```

```tsx
const form = useForm<RegisterFormValues>({
  resolver: zodResolver(registerSchema),
  mode: "onBlur",
  reValidateMode: "onChange",
  defaultValues: {
    username: "",
    password: "",
    confirmPassword: "",
    terms: false as unknown as true,
  },
});
```

```tsx
const onSubmit = form.handleSubmit((values) => {
  register.mutate(
    {
      username: values.username,
      password: values.password,
    },
    {
      onSuccess: () => {
        const next = getSafeRedirect(searchParams.get("next"));
        router.push(next);
      },
      onError: (error) => {
        mapProblemDetailToForm(error, form, KNOWN_FIELDS);
      },
    }
  );
});
```

```tsx
<Input
  label="Username"
  type="text"
  autoComplete="username"
  {...form.register("username")}
  error={form.formState.errors.username?.message}
/>
```

- [ ] **Step 3: Commit**

```bash
git add frontend/src/components/auth/LoginForm.tsx frontend/src/components/auth/RegisterForm.tsx
git commit -m "feat(frontend): login and register forms use username"
```

---

### Task 11: Remove forgot-password entry point + delete route page

**Files:**
- Modify: `frontend/src/app/login/page.tsx`
- Delete: `frontend/src/app/forgot-password/page.tsx`

- [ ] **Step 1: Remove the forgot-password Link block from `login/page.tsx`**

Delete this block:

```tsx
<div className="text-center">
  <Link
    href="/forgot-password"
    className="text-xs font-medium text-brand hover:underline"
  >
    Forgot your password?
  </Link>
</div>
```

If `Link` is no longer used in the file (it's used by the footer anchor — check), keep the import; otherwise prune.

- [ ] **Step 2: Delete `app/forgot-password/page.tsx`**

```bash
rm frontend/src/app/forgot-password/page.tsx
```

The directory will be empty afterward — delete it too:

```bash
rmdir frontend/src/app/forgot-password
```

- [ ] **Step 3: Commit**

```bash
git add frontend/src/app/login/page.tsx
git add -u frontend/src/app/forgot-password
git commit -m "feat(frontend): remove forgot-password entry point and route"
```

(Form/hook/schema/test code stays in tree — orphan-but-ready until email returns.)

---

### Task 12: Frontend test sweep + verify guards

**Files:**
- Modify: `frontend/src/components/auth/LoginForm.test.tsx`
- Modify: `frontend/src/components/auth/LoginForm.next.test.tsx`
- Modify: `frontend/src/components/auth/RegisterForm.test.tsx`
- Modify: `frontend/src/components/auth/AuthCard.test.tsx` (if it asserts forgot-password link)
- Modify: `frontend/src/lib/auth/errors.test.ts`
- Untouched: `frontend/src/components/auth/ForgotPasswordForm.test.tsx`, `frontend/src/lib/auth/hooks.test.tsx` forgot-password slice (they exercise the orphan code).

- [ ] **Step 1: Inventory**

```bash
cd frontend && grep -rln --include='*.test.tsx' --include='*.test.ts' -e '"email"' -e 'AUTH_EMAIL_EXISTS' -e 'forgot' src
```

- [ ] **Step 2: Replace email fixtures with username fixtures**

For each test in the list: substitute `email` field references / fixtures with `username`, `AUTH_EMAIL_EXISTS` with `AUTH_USERNAME_EXISTS`, "Email or password is incorrect" with "Username or password is incorrect", and `<Input label="Email" />` lookup helpers with `<Input label="Username" />` lookups (`getByLabelText(/email/i)` → `getByLabelText(/username/i)`). For `AuthCard.test.tsx`, if it asserts forgot-password link presence on the login page, **delete that assertion** (the link is gone).

- [ ] **Step 3: Run tests + verify guards**

```bash
cd frontend && npm test -- --run
```

```bash
cd frontend && npm run verify
```

Iterate until clean.

- [ ] **Step 4: Commit**

```bash
git add -u frontend/src
git commit -m "test(frontend): sweep email fixtures to username; drop forgot-password assertion"
```

---

### Task 13: Update FOOTGUNS.md F.99 reference

**File:**
- Modify: `docs/implementation/FOOTGUNS.md`

- [ ] **Step 1: Update §F.99**

Find the section heading "F.99 — Admin bootstrap config WILL re-promote a deliberately-demoted bootstrap email". Replace `bootstrap-emails` with `bootstrap-usernames` and "bootstrap email" with "bootstrap username" within the section body. Update the section heading to "F.99 — Admin bootstrap config WILL re-promote a deliberately-demoted bootstrap username".

- [ ] **Step 2: Commit**

```bash
git add docs/implementation/FOOTGUNS.md
git commit -m "docs(footguns): F.99 references bootstrap-usernames"
```

---

### Task 14: Postman collection sweep

**Surface:** SLPA collection (id `8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288`), `SLPA Dev` environment.

- [ ] **Step 1: Inspect**

Use `mcp__postman__getCollection` against the collection id; locate the `auth/register` and `auth/login` requests and any test scripts that reference `email`.

- [ ] **Step 2: Update register request body**

Change the request body for `POST /api/v1/auth/register`:
```json
{
  "username": "{{registerUsername}}",
  "password": "{{registerPassword}}"
}
```

(Drop `email` and `displayName` if present.)

- [ ] **Step 3: Update login request body**

Change the request body for `POST /api/v1/auth/login`:
```json
{
  "username": "{{registerUsername}}",
  "password": "{{registerPassword}}"
}
```

- [ ] **Step 4: Update environment variables**

In `SLPA Dev`, rename any `email` variable to `username`. Update test scripts that do `pm.environment.set("email", ...)` to set `username` instead.

- [ ] **Step 5: Verify**

Run the auth folder in the runner from the `SLPA Dev` environment to confirm the variables chain correctly through register → login → me.

(Postman edits are made via the Postman MCP tools; no git commit because the collection is hosted, not local.)

---

### Task 15: Final verification

- [ ] **Step 1: Wipe + restart the dev DB**

Either drop and recreate the local Postgres database or run the db wipe procedure (CLAUDE.md `DB wipe procedure`). Then:

```bash
docker compose down -v
docker compose up -d postgres redis minio
```

- [ ] **Step 2: Run backend full test suite**

```bash
cd backend && ./mvnw -q test
```

Expected: 0 failures.

- [ ] **Step 3: Run frontend tests + verify**

```bash
cd frontend && npm test -- --run && npm run verify
```

Expected: 0 failures.

- [ ] **Step 4: Manual smoke (V6 from spec)**

```bash
docker compose up --build
```

Then in a browser:

1. Navigate to `/register`. Sign up with username `Heath Barcus` and a 10+ char password. Confirm 201 + redirect.
2. Sign out.
3. Navigate to `/login`. Sign in with `heath barcus` (case mismatch) and the same password. Confirm 200 + redirect.
4. Navigate to `/forgot-password`. Confirm 404.
5. Inspect `/login`. Confirm there is no "Forgot your password?" link.
6. Hit `/api/v1/me` from the browser devtools. Confirm `username` is set and `email` is `null`.
7. Decode the `Authorization: Bearer ...` JWT (jwt.io). Confirm a `username` claim exists and there is no `email` claim.

- [ ] **Step 5: Negative grep**

```bash
git ls-files -z 'backend/src/main/java' | xargs -0 grep -nl --include='*.java' -e 'findByEmail' -e 'existsByEmail' -e 'EmailAlreadyExistsException' -e 'AUTH_EMAIL_EXISTS' -e 'bootstrap-emails' -e 'bootstrapEmails'
```

Expected: 0 hits in `backend/src/main`.

```bash
git ls-files -z 'frontend/src' | xargs -0 grep -nl -e '"email"' -e 'AUTH_EMAIL_EXISTS'
```

Expected: only `lib/auth/schemas.ts` (`emailSchema`/`forgotPasswordSchema`) and the orphan forgot-password component / its test files / hooks.ts forgot-password JSDoc.

- [ ] **Step 6: Push**

```bash
git push origin dev
```

(All earlier tasks committed locally; this push surfaces the whole chain on `origin/dev` so it shows up on GitHub.)

---

### Self-review notes

- Type consistency: `AuthPrincipal.username` is the canonical name; service code reads `principal.username()`; JWT claim is `"username"`; all three line up.
- DB wipe is non-negotiable for this task — V14 adds a NOT NULL column without a default and the unique index trips on duplicates if any pre-existing rows had blank usernames. Wipe is documented in the spec's Approach section and Task 15 Step 1.
- The `displayName` drop from `RegisterRequest` is a deliberate signature simplification (frontend never sent a real value, always `null`). The entity column stays. Future profile-edit UI will populate it.
- `userRepository.flush()` inside the createUser try-block forces the unique-violation to fire inside the catch instead of at transaction commit (where it would bypass the catch and propagate as 500). Without flush, the integrity exception fires only when the transaction commits.
