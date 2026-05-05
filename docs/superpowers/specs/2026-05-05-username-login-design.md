# Username login (drop email-based login)

> Status: approved 2026-05-05. Implementation plan: `docs/superpowers/plans/2026-05-05-username-login.md`.

## Goal

Replace the email-based login identifier with a `username` so users can sign up
without giving us an email address. Allow email-shaped usernames and SL-style
display names (Unicode, spaces, fancy letters) so a resident can use their SL
display name as their SLParcels username if they choose.

## Non-goals

- Email is **not removed** from the data model. The `users.email` column stays
  (nullable, unique) for whenever email notifications/recovery come back. We
  just stop asking for it on signup, stop logging in with it, and stop
  branching any business logic on it.
- No backfill / migration of existing rows. The DB will be wiped before this
  ships (per project policy: paying-customers-mode hasn't started, so DB is
  disposable). Existing Flyway migrations replay clean from V1; this work adds
  V14.
- The forgot-password page is removed as a reachable surface but its component
  code (`ForgotPasswordForm`, `useForgotPassword`, `forgotPasswordSchema`,
  associated tests) is **kept in the tree** for re-use when email returns.
- No reserved-username denylist (`admin`, `root`, `system`, etc.). YAGNI; can
  add when abuse appears.
- No "set your email later" settings UI. Defer until email notifications ship.
- No avatar UUID-based login (existing SL verification flow is unrelated).

## Approach

### Mapping

| Today                                  | After this spec                          |
|----------------------------------------|------------------------------------------|
| `LoginRequest.email`                   | `LoginRequest.username`                  |
| `RegisterRequest.email`                | `RegisterRequest.username`               |
| `CreateUserRequest.email`              | `CreateUserRequest.username`             |
| `User.email` (unique, login)           | `User.username` (unique, login); `User.email` stays (nullable, dormant) |
| `findByEmail` / `existsByEmail`        | `findByUsername` / `existsByUsername`    |
| `EmailAlreadyExistsException`          | `UsernameAlreadyExistsException`         |
| `AUTH_EMAIL_EXISTS` (problem code)     | `AUTH_USERNAME_EXISTS`                   |
| Problem URI `auth/email-exists`        | `auth/username-exists`                   |
| JWT claim `email`                      | JWT claim `username`                     |
| `AuthPrincipal.email`                  | `AuthPrincipal.username`                 |
| `slpa.admin.bootstrap-emails`          | `slpa.admin.bootstrap-usernames`         |
| `bulkPromoteByEmailIfUser`             | `bulkPromoteByUsernameIfUser`            |
| Admin search: `LOWER(email) LIKE ...`  | Admin search: `LOWER(username) LIKE ...` |
| Forgot-password link on `/login`       | (removed)                                |
| `/forgot-password` route               | (removed; component code retained)       |

### Username rules

**Character set (server-side, source of truth):**

- Length 3–64 codepoints **after** normalization.
- Allowed Unicode categories: `\p{L}` (letters), `\p{M}` (combining marks),
  `\p{N}` (numbers), `\p{P}` (punctuation), `\p{S}` (symbols), and the
  regular ASCII space `U+0020`.
- Forbidden: `\p{C}` (control / format / private-use, which catches ZWJ,
  ZWNJ, RTL-override, BOM, etc.), tab, newline, line/paragraph separators,
  and any whitespace other than `U+0020`.

**Normalization at write (server, before persist + before the `existsBy`
check):**

1. Trim leading/trailing whitespace.
2. NFC-normalize.
3. Collapse runs of internal whitespace to a single `U+0020`.

The post-normalization string is what gets stored, case-preserved.

**Storage:**

- Column: `users.username varchar(64) NOT NULL`.
- No `UNIQUE` keyword on the column itself.
- Unique functional index: `CREATE UNIQUE INDEX users_username_lower ON users (LOWER(username));`
- Hibernate manages the column declaration (`@Column(name = "username", nullable = false, length = 64)`).
  The functional index is created via Flyway V14 — Hibernate doesn't see it
  but the DB enforces it on insert/update.

**Lookup (case-insensitive):**

- `Optional<User> findByUsername(String username)` — JPQL
  `SELECT u FROM User u WHERE LOWER(u.username) = LOWER(:username)`. The
  functional index serves the query.
- `boolean existsByUsername(String username)` — same form.
- The `LOWER` Postgres operator is locale-aware on UTF-8 databases, so
  `Müller` and `müller` collide as expected.

**Race condition (two concurrent registrations of `Foo` and `foo`):** the
service checks `existsByUsername` first, but the unique index is the actual
authority. `UserService.createUser` catches `DataIntegrityViolationException`
on the save path and rethrows as `UsernameAlreadyExistsException` so the
caller gets a 409 instead of a 500.

### Frontend rules

- The Zod schema mirrors the server regex but is purely cosmetic — the server
  is the source of truth. Schema:

  ```ts
  const usernameSchema = z
    .string()
    .min(3, "At least 3 characters")
    .max(64, "At most 64 characters")
    .regex(/^[\p{L}\p{M}\p{N}\p{P}\p{S} ]+$/u, "Disallowed character")
    .refine((v) => v.trim() === v, "No leading or trailing spaces")
    .refine((v) => !/\s{2,}/.test(v), "Collapse multiple spaces");
  ```
- Inputs use `<input type="text" autoComplete="username" />`. Standard
  `autoComplete="username"` lets password managers pair the field with the
  password field — broken if the field name is `email` and `type="email"` is
  swapped to `type="text"` without the autocomplete hint.

## File scope

### Backend (modify)

- `backend/src/main/java/com/slparcelauctions/backend/user/User.java` — add `username` field.
- `backend/src/main/java/com/slparcelauctions/backend/user/UserRepository.java` — rename `findByEmail`/`existsByEmail` → `findByUsername`/`existsByUsername`; rename `bulkPromoteByEmailIfUser` → `bulkPromoteByUsernameIfUser`; update `searchAdmin` JPQL to substring-match `username` not `email`.
- `backend/src/main/java/com/slparcelauctions/backend/user/UserService.java` — normalize on createUser, check existsByUsername, save with username; catch DataIntegrityViolationException as UsernameAlreadyExistsException.
- `backend/src/main/java/com/slparcelauctions/backend/user/UserAlreadyExistsException.java` — replace `.email(...)` static factory with `.username(...)`.
- `backend/src/main/java/com/slparcelauctions/backend/user/dto/CreateUserRequest.java` — `email` field → `username` field with new validation.
- `backend/src/main/java/com/slparcelauctions/backend/user/dto/UserResponse.java` — add `username`; keep nullable `email`.
- `backend/src/main/java/com/slparcelauctions/backend/auth/AuthService.java` — login by username; register by username; bubble `UserAlreadyExistsException.username(...)` → `UsernameAlreadyExistsException`.
- `backend/src/main/java/com/slparcelauctions/backend/auth/AuthPrincipal.java` — `email` field → `username`.
- `backend/src/main/java/com/slparcelauctions/backend/auth/JwtService.java` — JWT claim `email` → `username` (issue + parse).
- `backend/src/main/java/com/slparcelauctions/backend/auth/dto/LoginRequest.java` — `@Email email` → `username` with new validation.
- `backend/src/main/java/com/slparcelauctions/backend/auth/dto/RegisterRequest.java` — same; **drop** `displayName` field (not used by the new register form; backend signature simplifies).
- `backend/src/main/java/com/slparcelauctions/backend/auth/exception/EmailAlreadyExistsException.java` — **delete**, replaced by `UsernameAlreadyExistsException.java`.
- `backend/src/main/java/com/slparcelauctions/backend/auth/exception/UsernameAlreadyExistsException.java` — **new**.
- `backend/src/main/java/com/slparcelauctions/backend/auth/exception/AuthExceptionHandler.java` — swap email-exists handler for username-exists; update error code, problem URI, title; update invalid-credentials detail copy.
- `backend/src/main/java/com/slparcelauctions/backend/admin/AdminBootstrapProperties.java` — `bootstrapEmails` → `bootstrapUsernames`.
- `backend/src/main/java/com/slparcelauctions/backend/admin/AdminBootstrapInitializer.java` — call `bulkPromoteByUsernameIfUser`; log copy.
- `backend/src/main/resources/application.yml` — `bootstrap-emails:` → `bootstrap-usernames:` with placeholder values appropriate for username-based bootstrap.

### Backend (delete)

- `backend/src/main/java/com/slparcelauctions/backend/auth/exception/EmailAlreadyExistsException.java`

### Backend (new)

- `backend/src/main/resources/db/migration/V14__username_field.sql` — adds the column + functional unique index. Drops the existing `users.email` UNIQUE constraint name (keeps the column, drops the index — email is no longer login, but it remains unique-when-present so two accounts can't claim the same future-email-address; the V1-generated `uk6dotkott2kjsp8vw4d0m25fb7` constraint stays for that reason — leave it alone).
- `backend/src/main/java/com/slparcelauctions/backend/auth/exception/UsernameAlreadyExistsException.java`

### Backend (test changes)

Every backend test that creates a user via `User.builder().email(...)` or via
`POST /api/v1/auth/register` with `{"email": ...}` must switch to
`.username(...)` / `{"username": ...}`. Counts: ~30 files (per `findByEmail`/
`existsByEmail` / `"email"` JSON grep).

Notable: `AdminBootstrapIntegrationTest` config keys
(`slpa.admin.bootstrap-emails[N]=...`) → `slpa.admin.bootstrap-usernames[N]=...`,
fixture values change from emails to usernames.

### Frontend (modify)

- `frontend/src/lib/auth/schemas.ts` — replace `emailSchema` with `usernameSchema`; update `loginSchema` and `registerSchema` to use it; **drop** `confirmPassword` is unaffected; `forgotPasswordSchema` stays.
- `frontend/src/lib/auth/errors.ts` — `AUTH_EMAIL_EXISTS` → `AUTH_USERNAME_EXISTS`; field-level error key `email` → `username`; copy "Email or password is incorrect" → "Username or password is incorrect".
- `frontend/src/lib/auth/api.ts` — `RegisterRequest.email` / `LoginRequest.email` → `username`. Drop `displayName: string | null` from RegisterRequest (backend no longer accepts it).
- `frontend/src/components/auth/LoginForm.tsx` — `email` field → `username`; `KNOWN_FIELDS`; default values.
- `frontend/src/components/auth/RegisterForm.tsx` — `email` field → `username`; `KNOWN_FIELDS`; default values; mutate body shape (`username` instead of `email`, drop `displayName: null`).
- `frontend/src/app/login/page.tsx` — remove the forgot-password Link block.

### Frontend (delete)

- `frontend/src/app/forgot-password/page.tsx` — route gone; URL 404s.

### Frontend (kept as orphan, intentionally not deleted)

- `frontend/src/components/auth/ForgotPasswordForm.tsx`
- `frontend/src/components/auth/ForgotPasswordForm.test.tsx`
- `frontend/src/lib/auth/hooks.ts` — `useForgotPassword` stays.
- `frontend/src/lib/auth/hooks.test.tsx` — forgot-password tests stay.
- `frontend/src/lib/auth/index.ts` — `useForgotPassword` re-export stays.
- `frontend/src/lib/auth/schemas.ts` — `forgotPasswordSchema` + `emailSchema` (used only by forgot-password) stay. The `emailSchema` becomes orphan-but-correct.

### Frontend (test changes)

- `frontend/src/components/auth/LoginForm.test.tsx`, `LoginForm.next.test.tsx`, `RegisterForm.test.tsx` — replace email fixtures with username fixtures.
- `frontend/src/components/auth/AuthCard.test.tsx` — if it asserts forgot-password link presence, update; otherwise no change.
- `frontend/src/lib/auth/errors.test.ts` — update fixtures for `AUTH_USERNAME_EXISTS` and the new copy.
- `frontend/src/components/auth/ForgotPasswordForm.test.tsx`, `lib/auth/hooks.test.tsx` forgot-password slice — **untouched** (orphan code stays green).

### Postman

- Collection `SLPA` (id `8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288` per CLAUDE.md):
  - `POST /api/v1/auth/register` body: `{"email": ...}` → `{"username": ...}`. Drop `displayName` if present.
  - `POST /api/v1/auth/login` body: `{"email": ...}` → `{"username": ...}`.
  - Test scripts: any chaining of `pm.environment.set("email", ...)` should reflect username instead. (Inspect the collection during Task 17 of the plan.)
- Environment `SLPA Dev` variables: `email` → `username` if such a variable exists.

### Live docs

- `README.md`, `CLAUDE.md` — only if they reference email-based login (none do today; verify with grep during plan execution).
- `docs/implementation/FOOTGUNS.md` §F.99 — references `slpa.admin.bootstrap-emails`. Update to `bootstrap-usernames`.
- `docs/initial-design/DESIGN.md` line 1666 — same. Per `feedback_no_lazy_scope_trimming` and the rebrand spec's precedent, initial-design is **not** swept (historical artifact); leave alone.
- `FULL_TESTING_PROCEDURES.md` — references `slpa.admin.bootstrap-*`; the wildcard line stays accurate, no edit.

## Edge cases

- **Empty username after normalization:** trim + collapse can produce a blank
  if the input was all whitespace. The 3-char `min` and `@NotBlank` catch this
  on the request DTO before normalization runs in the service. Defensive
  `assert post.length() >= 1` after normalization still rejects with a 400.
- **Email-shaped username:** `firstname.last@example.com` is valid (matches
  the regex). The user can register the literal email string; we just don't
  treat it as an email address. No `@Email` annotation anywhere.
- **Two users registering `Foo` and `foo` simultaneously:** unique index
  catches the second insert; service translates the
  `DataIntegrityViolationException` to `UsernameAlreadyExistsException` and
  the client sees a 409 `AUTH_USERNAME_EXISTS`.
- **Existing email-only users:** none. DB will be wiped before this ships.
- **JWT cookies in flight at deploy:** none (DB wipe invalidates every
  refresh token).
- **Display name parity:** today the register form sends `displayName: null`
  unconditionally. The new register form drops the field entirely; the
  `RegisterRequest` DTO drops it; `UserService.createUser` no longer expects
  it. Existing `User.displayName` column stays for the future profile UI.
- **`UserResponse.email` consumers:** no frontend code reads `user.email`
  today (verified by grep during plan exec). Keeping it nullable is a
  forward-compat hedge.

## Verification

### V1 — character/length rules round-trip

1. `POST /api/v1/auth/register {"username":"Heath Barcus","password":"...10chars"}` → 201.
2. `POST /api/v1/auth/register {"username":"heath barcus","password":"..."}` → 409 `AUTH_USERNAME_EXISTS` (case-insensitive collision).
3. `POST /api/v1/auth/register {"username":"a","password":"..."}` → 400 (too short).
4. `POST /api/v1/auth/register {"username":"   ","password":"..."}` → 400.
5. `POST /api/v1/auth/register {"username":"foo​bar","password":"..."}` → 400 (ZWSP rejected).
6. `POST /api/v1/auth/register {"username":"ֆօʐɛ","password":"..."}` → 201.
7. `POST /api/v1/auth/register {"username":"hello@world.com","password":"..."}` → 201 (email-shaped username allowed).
8. `POST /api/v1/auth/login {"username":"HEATH BARCUS","password":"..."}` after step 1 → 200 (case-insensitive lookup).
9. `POST /api/v1/auth/login {"username":"  Heath  Barcus  ","password":"..."}` after step 1 → fails on form validation pre-normalization or succeeds via server normalization (server should normalize before lookup; verify behavior).

### V2 — JWT claim shape

After a register call, decode the issued access token (jwt.io or `JwtService.parseAccessToken`):
- `sub` = user publicId UUID
- `username` = the registered username (claim renamed from `email`)
- No `email` claim present.

### V3 — no live email-based code paths remain

Greps from `backend/src` and `frontend/src`:
- `findByEmail`, `existsByEmail` → 0 hits (allow the orphan `emailSchema` use in `forgotPasswordSchema`)
- `EmailAlreadyExistsException`, `AUTH_EMAIL_EXISTS` → 0 hits.
- `bootstrap-emails`, `bootstrapEmails` → 0 hits (allow `docs/initial-design`, `FOOTGUNS.md` references — see live-docs file scope).
- `JWT.*claim.*"email"` → 0 hits.

### V4 — full backend test suite green

`./mvnw test` from `backend/` — 0 failures.

### V5 — frontend tests + verify guards green

`npm test` and `npm run verify` from `frontend/` — 0 failures.

### V6 — manual smoke

1. `docker compose up --build`
2. Register a fresh user with username `Heath Barcus` and password.
3. Sign out, sign back in with `heath barcus` (case mismatch) — succeeds.
4. Confirm `/forgot-password` 404s.
5. Confirm there is no "Forgot password?" link on `/login`.
6. Confirm the registered user got their `username` in the `/api/v1/me`
   response and that `email` is `null`.

## Risks

| ID | Risk | Mitigation |
|----|------|------------|
| R1 | Functional unique index ambiguity (case insensitivity that doesn't lowercase Unicode the same way Java's `String.toLowerCase(Locale.ROOT)` does) | Service layer doesn't lowercase before insert/lookup — it lets Postgres do it via `LOWER()` at query and index time. App-level lowercase is not used; both authority and lookup go through the same Postgres function. |
| R2 | NFC normalization on the server differs from frontend normalization | Server is source of truth; frontend regex is cosmetic. Frontend sends what the user typed; server normalizes before persist + check. |
| R3 | Forgot-password orphan code rots | Tests stay green via existing `ForgotPasswordForm.test.tsx` and `useForgotPassword` test. The four-step swap docstring on `useForgotPassword` already documents the un-orphan procedure. |
| R4 | A test fixture file uses `User.builder().email("...")` and is missed in the sweep | Compile-time fail (the field is renamed, not just deprecated) — the build catches it. |
| R5 | Postman variable chaining breaks for collaborators (`email` env var) | Update Postman in the same task; document the new variable name. |
| R6 | The `displayName` drop from `RegisterRequest` breaks an integration test fixture | Compile-time fail on the DTO record signature; sweep all test usages in the same task. |

## Deferred work

1. Email collection at signup (settings UI; defer until email notifications ship).
2. Forgot-password by email (real backend endpoint; revives the orphan UI).
3. Account-recovery channels (SL-IM-based recovery, security questions, etc.).
4. Reserved-username denylist (`admin`, `root`, `system`, `slparcels`, …).
5. Username change after signup (currently immutable; will need username-history table to prevent identity squatting).
6. Username search index (right now admin-search uses `LIKE LOWER(username)`; a trigram index can come later if perf demands).
