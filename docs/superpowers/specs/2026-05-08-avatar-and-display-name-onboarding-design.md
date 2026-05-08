# Avatar + Display-Name Onboarding (post-verify) Design

**Date:** 2026-05-08
**Status:** Draft → ready for plan
**Owner:** Heath

## 1. Goal

Two new forced-onboarding steps wedged between the existing SL-verify gate and the dashboard:

1. **Avatar step** — pull the user's SL profile photo from `world.secondlife.com`, let them crop it (pan + zoom inside a circular preview); or upload their own; or skip ("no avatar").
2. **Display-name step** — text input pre-filled with their SLParcels `username`; user can keep it, customise it, or clear-and-skip.

After both steps complete (or are explicitly skipped) the user reaches the dashboard. The gate fires once per account; settings-page edits later are unaffected.

## 2. Why

- A blank avatar on auctions / bid history / seller cards looks unfinished and reduces seller trust signals. Today's bug — empty `<h2>` next to the seller card when `displayName=null` — was a partial symptom. Better default: every account leaves onboarding with a face and a name.
- The SL profile photo is the cheapest source of a user-recognisable picture. Most SL residents already have one; pulling it costs nothing on our side beyond a one-shot HTML scrape.
- Sequential gates (verify → avatar → name → app) match what users already saw with verify. No new mental model.

## 3. Architecture

```
Register → Verify gate (existing) → Avatar gate (NEW) → Display-name gate (NEW) → Dashboard
```

Two boolean columns on `users` track step completion:

- `avatar_step_completed boolean not null default false`
- `display_name_step_completed boolean not null default false`

Two flags (not one combined `onboarding_complete`) so a tab close mid-flow resumes the user on the next-uncompleted step instead of re-firing one they already finished.

**Backfill on the migration that adds the columns:**
```sql
UPDATE users SET avatar_step_completed = true WHERE profile_pic_url IS NOT NULL;
UPDATE users SET display_name_step_completed = true WHERE display_name IS NOT NULL;
```
Pre-existing users who already uploaded a pic or set a name are not re-prompted. Pre-existing verified users with neither set will hit the gate on next visit.

**Frontend gate routing:**

Next.js route groups (folder names in parentheses) don't appear in the URL. The existing `(verified)` route group keeps gating verified-or-bust. A new nested `(onboarded)` route group wraps the dashboard tab pages so its layout only mounts for already-verified users.

File layout under `frontend/src/app/dashboard/`:

```
dashboard/page.tsx                                 → URL /dashboard (existing redirect-only)
dashboard/verify/page.tsx                          → URL /dashboard/verify (unchanged)
dashboard/(verified)/layout.tsx                    → verify-gate only (tabs MOVE OUT)
dashboard/(verified)/avatar/page.tsx               → URL /dashboard/avatar — the avatar onboarding gate
dashboard/(verified)/display-name/page.tsx         → URL /dashboard/display-name — the display-name onboarding gate
dashboard/(verified)/(onboarded)/layout.tsx        → onboarding-gate + tabs + h1 + container
dashboard/(verified)/(onboarded)/overview/page.tsx → URL /dashboard/overview (moved from (verified)/overview)
dashboard/(verified)/(onboarded)/bids/page.tsx     → URL /dashboard/bids
dashboard/(verified)/(onboarded)/listings/page.tsx → URL /dashboard/listings
```

`(onboarded)/layout.tsx` reads `useCurrentUser()` and:
- if `avatarStepCompleted === false` → `router.replace('/dashboard/avatar')`
- else if `displayNameStepCompleted === false` → `router.replace('/dashboard/display-name')`
- else → render children

The avatar and display-name pages live OUTSIDE the `(onboarded)` group (siblings, not children) so they never redirect-loop to themselves. Each page also self-checks: if the user lands on `/dashboard/avatar` after they've already completed it (bookmark / back button), the page forwards to the next-uncompleted step.

`(verified)/layout.tsx` is simplified: it keeps the redirect to `/dashboard/verify` for unverified users but stops rendering the tabs / `<h1>Dashboard</h1>` / `max-w-6xl` container. Those move into `(onboarded)/layout.tsx`. The forced onboarding pages (`avatar`, `display-name`) are siblings of `(onboarded)` so they're free to render their own page chrome without inheriting the tabs strip — appropriate because they aren't navigation targets, they're forced steps.

The existing `(verified)/layout.test.tsx` is updated for the new responsibility (verify-redirect only). New `(onboarded)/layout.test.tsx` covers the onboarding redirect logic + tab rendering.

## 4. Backend

### 4.1 SlProfilePhotoService (new)

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class SlProfilePhotoService {
    private final WebClient slWebClient;       // base: https://world.secondlife.com
    private final WebClient pictureWebClient;  // base: https://picture-service.secondlife.com
    private final StringRedisTemplate redis;

    public Optional<byte[]> fetchProfilePhoto(UUID slAvatarUuid) { ... }
}
```

**Algorithm:**

1. Cache lookup: `redis.opsForValue().get("sl:profile-photo:" + uuid)`. Sentinel value `"NONE"` means "previously confirmed no photo, don't re-scrape". Bytes return as a base64-encoded body. Hit → return.
2. `GET https://world.secondlife.com/resident/{uuid}` with `Accept: text/html`, 5s connect + 5s read timeout.
3. On 404 / 5xx / timeout → cache `"NONE"` for 5 min, return `Optional.empty()`.
4. Parse HTML with jsoup. Extract `document.selectFirst("img.parcelimg")`. If absent → cache `"NONE"` 5 min, return empty.
5. Read the `src` attribute. Validate it starts with `https://picture-service.secondlife.com/` (refuse anything else — defence against an injected absolute URL on a compromised page).
6. `GET {src}` against `pictureWebClient`. Validate `Content-Type: image/jpeg` and body ≤ 2 MB.
7. Cache the bytes for 1 hour at `sl:profile-photo:{uuid}` (base64-encoded). Return `Optional.of(bytes)`.

**Rate-limit hygiene:** all calls go through a single `WebClient` with `MaxInMemorySize(2 MB)` and the timeouts above. Negative cache prevents thundering-herd retries on no-photo accounts.

**Tests:**
- HTML with `parcelimg` → returns bytes (mocked picture-service response).
- HTML without `parcelimg` → empty + `NONE` cached.
- world.sl returns 404 → empty + `NONE` cached.
- picture-service 5xx → empty.
- HTML present but malformed → empty.
- Cache hit (positive) → no HTTP calls made.
- Cache hit (NONE sentinel) → no HTTP calls made.
- `src` outside the allow-listed host → rejected, returns empty.

### 4.2 Schema migration

`backend/src/main/resources/db/migration/V20__avatar_and_display_name_onboarding.sql`:

```sql
ALTER TABLE users
    ADD COLUMN avatar_step_completed boolean NOT NULL DEFAULT false,
    ADD COLUMN display_name_step_completed boolean NOT NULL DEFAULT false;

UPDATE users SET avatar_step_completed = true       WHERE profile_pic_url IS NOT NULL;
UPDATE users SET display_name_step_completed = true WHERE display_name    IS NOT NULL;
```

Both columns are `NOT NULL DEFAULT false`. Hibernate's `ddl-auto: update` is a no-op on existing columns (Flyway runs first); the explicit migration is the source of truth per the Spring Boot 4 + Flyway interaction documented in `CLAUDE.md`.

### 4.3 User entity changes

```java
@Builder.Default
@Column(name = "avatar_step_completed", nullable = false,
        columnDefinition = "boolean not null default false")
private Boolean avatarStepCompleted = false;

@Builder.Default
@Column(name = "display_name_step_completed", nullable = false,
        columnDefinition = "boolean not null default false")
private Boolean displayNameStepCompleted = false;
```

### 4.4 UserResponse DTO changes

`UserResponse` (the `/api/v1/users/me` shape consumed by `useCurrentUser` on the frontend) gains two boolean fields:

```java
public record UserResponse(
    ...,
    boolean avatarStepCompleted,
    boolean displayNameStepCompleted
) {
    public static UserResponse from(User u) { ... include both flags ... }
}
```

`UserProfileResponse` (the public `/api/v1/users/{id}` shape) is unchanged — these flags are caller-private.

### 4.5 Onboarding controller (new)

```java
@RestController
@RequestMapping("/api/v1/users/me/onboarding")
@RequiredArgsConstructor
public class OnboardingController {
    private final SlProfilePhotoService slProfilePhoto;
    private final OnboardingService onboarding;

    @GetMapping("/sl-profile-photo")
    public ResponseEntity<byte[]> slProfilePhoto(@AuthenticationPrincipal AuthPrincipal p) { ... }

    @PostMapping("/avatar/skip")
    public UserResponse skipAvatar(@AuthenticationPrincipal AuthPrincipal p) { ... }

    @PostMapping("/display-name")
    public UserResponse setDisplayName(
        @AuthenticationPrincipal AuthPrincipal p,
        @Valid @RequestBody OnboardingDisplayNameRequest body) { ... }
}
```

**`GET /sl-profile-photo`**
- Resolves the caller's `User`, reads `slAvatarUuid`.
- If null (somehow verified without an SL avatar — defensive) → `404`.
- Calls `slProfilePhoto.fetchProfilePhoto(uuid)`. Empty → `404`. Bytes → `200 image/jpeg` with body, `Cache-Control: private, max-age=3600`.

**`POST /avatar/skip`**
- Idempotent: flips `avatarStepCompleted = true` if false; if already true, no-op.
- Returns the updated `UserResponse`.

**`POST /display-name`** body `OnboardingDisplayNameRequest`:
```java
@JsonIgnoreProperties(ignoreUnknown = false)
public record OnboardingDisplayNameRequest(
    @Size(max = 50, message = "displayName must be at most 50 characters")
    String displayName
) {}
```
The DTO uses only `@Size(max = 50)` — empty, whitespace-only, and `null` all pass validation because the onboarding endpoint treats them as "skip". Trim + emptiness logic lives in `OnboardingService.setDisplayName`:
- `value == null` OR `value.trim().isEmpty()` → flip flag, do NOT write `displayName`.
- otherwise → `setDisplayName(value.trim())` + flip flag.

This is intentionally laxer than the existing `UpdateUserRequest` (which uses `@Size(min=1)` + a no-padded-whitespace regex) because the onboarding endpoint has explicit "skip" semantics. Subsequent updates via `PUT /api/v1/users/me` keep the stricter contract — once a user has a displayName, clearing it requires going through the dedicated update endpoint with a non-blank value.

`@JsonIgnoreProperties(ignoreUnknown = false)` mirrors the existing `UpdateUserRequest` posture so the global `fail-on-unknown-properties: true` flag rejects extra fields. Returns updated `UserResponse`.

### 4.6 OnboardingService (new)

```java
@Service
@RequiredArgsConstructor
public class OnboardingService {
    private final UserRepository userRepository;

    @Transactional
    public UserResponse skipAvatar(Long userId) { ... flip + return ... }

    @Transactional
    public UserResponse setDisplayName(Long userId, String displayName) {
        // null/blank → flip flag only
        // non-blank → write field + flip flag
    }
}
```

### 4.7 AvatarService.upload change

`AvatarService.upload(Long userId, MultipartFile file)` flips `avatarStepCompleted = true` unconditionally as part of its existing transaction. Re-uploads are no-ops on the flag.

### 4.8 Endpoint security

All four onboarding endpoints require an authenticated principal (`@AuthenticationPrincipal AuthPrincipal`). No `@PreAuthorize("verified")` because the verify gate is enforced at the route layer in the frontend, and we don't want to surprise an admin-tool caller. Backend treats the flags as plain user state.

## 5. Frontend

### 5.1 New dependency

`react-easy-crop` (^5.x, MIT, ~30 KB gzipped). Provides pan + zoom + circular preview + a `getCroppedImg(imageSrc, crop, zoom)` helper for the canvas extraction.

### 5.2 `<AvatarCropper>` (new shared component)

`frontend/src/components/user/AvatarCropper.tsx`

```tsx
interface Props {
  imageSrc: string;            // blob: URL or our /sl-profile-photo URL
  onSave: (blob: Blob) => void | Promise<void>;
  onCancel?: () => void;
  saveLabel?: string;          // default "Save"
}
```

- Wraps `<Cropper>` from react-easy-crop with `aspect={1}`, `cropShape="round"`.
- Internal state: `crop: {x, y}`, `zoom: number`, `croppedAreaPixels: Area`.
- "Save" → runs the canvas extraction (helper in `frontend/src/lib/avatar/cropImage.ts`) → 512×512 PNG `Blob` → `onSave(blob)`.
- Image-load failure → renders an error panel with the user's `onCancel` so the parent can swap to a different source.
- Pan via drag, zoom via slider + scroll/pinch (library-provided).

### 5.3 `cropImage` helper

`frontend/src/lib/avatar/cropImage.ts` — pure function `getCroppedImg(imageSrc: string, areaPixels: Area): Promise<Blob>`. Loads the image into an off-screen `HTMLImageElement`, draws to a 512×512 `OffscreenCanvas`, calls `canvas.convertToBlob({ type: 'image/png' })`. Falls back to `<canvas>` + `toBlob` for browsers without OffscreenCanvas (Safari ≤ 16.3, Firefox older betas).

### 5.4 `dashboard/(verified)/avatar/page.tsx` (new) — URL `/dashboard/avatar`

```tsx
"use client";
export default function AvatarOnboardingPage() {
  // 1. forward redirect if avatarStepCompleted is already true
  // 2. fetch /api/v1/users/me/onboarding/sl-profile-photo (Blob via apiUrl + fetch)
  // 3. branch on 200 vs 404
}
```

Two render branches:

**SL photo present (200)**
- `<AvatarCropper imageSrc={blobUrl}>` mounted.
- Primary button: "Save this avatar" → POST `/api/v1/users/me/avatar` (multipart with the cropped Blob).
- Secondary link: "Upload a different image" → file picker, replaces the cropper source with the picked Blob URL.
- Tertiary: "Skip — no avatar" → POST `/api/v1/users/me/onboarding/avatar/skip` → invalidates `useCurrentUser`, navigates forward.

**SL photo absent (404)**
- File picker visible directly. Once picked, `<AvatarCropper>` mounts on the picked Blob.
- Same Save / Skip buttons.

After any save or skip, the page invalidates `useCurrentUser` and navigates: if `display_name_step_completed` is now false, → `/dashboard/display-name`; else → `/dashboard/overview`. The `(onboarded)` layout will redirect anyway, but doing it locally first avoids a brief flash through the gate.

### 5.5 `dashboard/(verified)/display-name/page.tsx` (new) — URL `/dashboard/display-name`

```tsx
"use client";
export default function DisplayNameOnboardingPage() {
  const { data: user } = useCurrentUser();
  const [value, setValue] = useState(user?.username ?? "");
  // ...
}
```

- Single text input, pre-filled with `user.username`. `maxLength={50}`.
- Helper text below: "This is the name people will see in auctions and reviews. You can change it later in settings."
- Buttons: "Save" (POST `/onboarding/display-name` with `{ displayName: value.trim() }`) and "Skip" (POST with `{ displayName: null }`).
- Empty/blank value submitted via Save behaves the same as Skip (server-side: empty/null → flip without writing).
- After response, invalidates `useCurrentUser`, navigates to `/dashboard/overview`.

### 5.6 `(onboarded)` layout (new)

`frontend/src/app/dashboard/(verified)/(onboarded)/layout.tsx`:

```tsx
"use client";
export default function OnboardedLayout({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const { data: user, isPending } = useCurrentUser();

  useEffect(() => {
    if (isPending || !user) return;
    if (!user.avatarStepCompleted) {
      router.replace("/dashboard/avatar");
      return;
    }
    if (!user.displayNameStepCompleted) {
      router.replace("/dashboard/display-name");
    }
  }, [isPending, user, router]);

  if (isPending || !user) return <LoadingSpinner label="Loading..." />;
  if (!user.avatarStepCompleted || !user.displayNameStepCompleted) {
    return <LoadingSpinner label="Redirecting..." />;
  }
  return <>{children}</>;
}
```

The existing `(verified)/layout.tsx` is simplified to:

```tsx
"use client";
export default function VerifiedLayout({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const { data: user, isPending, isError } = useCurrentUser();

  useEffect(() => {
    if (isPending || isError) return;
    if (!user?.verified) router.replace("/dashboard/verify");
  }, [isPending, isError, user?.verified, router]);

  if (isPending || !user) return <LoadingSpinner label="Loading..." />;
  if (!user.verified) return <LoadingSpinner label="Redirecting..." />;

  return <>{children}</>;
}
```

It keeps the verify-redirect, drops the `<Tabs>` / `<h1>Dashboard</h1>` / `max-w-6xl` chrome. That chrome moves into `(onboarded)/layout.tsx` (above), wrapping `{children}` AFTER the onboarding-redirect check passes.

Why split: the avatar and display-name pages are siblings of `(onboarded)` under `(verified)`. They inherit the verify-gate but NOT the tabs / dashboard chrome — appropriate, because they aren't navigation targets, they're forced steps with their own page chrome.

Existing dashboard tab pages move filesystem-wise:

```
BEFORE                                          AFTER
dashboard/(verified)/overview/page.tsx     →    dashboard/(verified)/(onboarded)/overview/page.tsx
dashboard/(verified)/bids/page.tsx         →    dashboard/(verified)/(onboarded)/bids/page.tsx
dashboard/(verified)/listings/page.tsx     →    dashboard/(verified)/(onboarded)/listings/page.tsx
```

URLs are unchanged (route groups don't appear in URLs). No client-visible URL break.

### 5.7 Settings page integration

`frontend/src/components/user/ProfilePictureUploader.tsx` keeps drag-and-drop file selection but the post-pick step replaces the previous "preview + Upload" with `<AvatarCropper imageSrc={blobUrl} onSave={uploadBlob}>`. Same `useUploadAvatar()` hook on save. The upload endpoint is unchanged.

This guarantees the avatar a user picks at settings looks identical to one picked during onboarding — no diverging crop semantics.

### 5.8 CurrentUser type

`frontend/src/lib/user/api.ts`'s `CurrentUser` type gains three fields:

```ts
username: string;                  // already emitted by backend UserResponse, just not in the type
avatarStepCompleted: boolean;
displayNameStepCompleted: boolean;
```

`username` is added because the display-name onboarding page pre-fills its input from it (`useState(user.username)`). The backend has been emitting `username` on `/me` since V14, the frontend type just hadn't kept up.

## 6. Storage

Avatars stay in S3 (MinIO in dev) at the existing keys: `avatars/{userId}/{64,128,256}.png`. We store **only the cropped, resized derivatives**. No original kept, no crop metadata persisted.

Pros: zero new infra, no DB columns, ~30–100 KB per user, fastest serve path. Matches Twitter / Discord / Slack semantics.

Cons: re-cropping later requires re-picking the source. For SL photos the source is free (URL is deterministic from `slAvatarUuid` so the user can re-trigger the SL fetch). For uploads, the user re-uploads. This is the same UX as today's settings page, so no regression.

If a future feature ever needs "edit crop without re-uploading", the cropper component is already producing crop metadata client-side — adding an `original.png` S3 object + crop metadata columns is a clean follow-up. The current design doesn't paint us into a corner.

## 7. Error handling

**Backend SL scrape failures** — every failure mode (network, parse, host-allow-list violation, picture-service non-200, oversized body) collapses to `Optional.empty()` + warn-level log. The frontend renders the no-photo branch.

**Cropper image load failure** — the `<AvatarCropper>` shows an inline error and disables Save. Parent provides `onCancel` so the user can pick a different source.

**Onboarding endpoints under concurrent calls** — flag flips are idempotent. Two tabs racing each other end up at the same final state.

**Race: user opens `/dashboard/avatar` directly after completing it** — the page reads `useCurrentUser()`, sees `avatarStepCompleted === true`, and forward-navigates to `/dashboard/display-name` (or `/dashboard/overview` if both done). No 404, no stuck state.

**JWT expiry mid-flow** — existing 401 handler kicks to `/login`. Onboarding flags are server-side, so re-login resumes the user on the next-uncompleted step.

**`slAvatarUuid` is null on a verified user** — defensive 404 from `/sl-profile-photo`. Page collapses to upload-or-skip.

**Migration backfill on a row with both `profile_pic_url` set AND `display_name` set** — both flags flip true, user skips both gates. Correct behaviour for a complete profile.

**Display-name input that trims to empty** — `OnboardingDisplayNameRequest` only enforces `@Size(max = 50)`. Empty / whitespace-only / null all pass DTO validation; `OnboardingService.setDisplayName` handles them as the skip path (flip flag, do not write). A field full of spaces submitted via Save reaches the controller as `"   "`; the service's `value.trim().isEmpty()` check routes it to skip. Subsequent updates via `PUT /api/v1/users/me` retain the stricter `UpdateUserRequest` contract (must be 1–50 non-blank chars).

## 8. Testing

### 8.1 Backend

- `SlProfilePhotoServiceTest` (unit, MockWebServer): all six scrape cases above + cache hit/miss for both positive and negative entries + host-allow-list rejection.
- `OnboardingControllerSliceTest` (`@WebMvcTest`): skip-avatar flips flag; display-name with non-blank value writes (trimmed) + flips; with null/empty/whitespace-only flips without writing; with 51 chars returns 400 + flag NOT flipped; with extra unknown JSON field returns 400 (canary for `fail-on-unknown-properties`); idempotent re-call returns 200.
- `OnboardingFlowIntegrationTest` (full Spring context, real DB): register → verify → skip-avatar → set-display-name → both flags true. Plus a separate test that runs Flyway from V1 against a synthetic dataset and asserts the V20 backfill flips the right rows.
- `AvatarServiceTest`: assert `upload(...)` flips `avatarStepCompleted` to true.
- `UserResponseTest`: both new fields surface on `UserResponse.from(user)`.

### 8.2 Frontend

- `AvatarCropper.test.tsx`: pan / zoom interactions, Save calls `onSave` with a Blob, image-load error renders the error state, Cancel resets.
- `app/dashboard/(verified)/avatar/page.test.tsx`: 200 SL photo → cropper renders with the photo, 404 → upload form renders, Skip → POST + redirect.
- `app/dashboard/(verified)/display-name/page.test.tsx`: pre-fills `username`, Save with default → POST `username`, Save with custom → POST custom, clear-and-Save → POST `null`. Each redirects.
- `(onboarded)/layout.test.tsx`: verified user with both flags false → redirect to `/dashboard/avatar`. Avatar done, name not done → redirect to `/dashboard/display-name`. Both done → renders children. Pending → spinner.
- `ProfilePictureUploader.test.tsx`: extend to assert cropper mounts after file pick (was direct-upload).

### 8.3 Manual smoke

1. Verified user, both flags false: visit `/dashboard/overview` → forwarded to `/dashboard/avatar`. SL photo loads. Crop, Save. Lands on `/dashboard/display-name`. Default = username. Save. Lands on `/dashboard/overview`.
2. Verified user, picks Skip on both: lands on dashboard with placeholder avatar (Avatar component renders initials from username) and `displayName=null` (renders username via the `User.getDisplayName()` fallback shipped in #211).
3. Re-visit `/dashboard/avatar` after completion: forward-redirects past it to `/dashboard/overview`.
4. Verified user with no SL profile photo: avatar page collapses to upload-or-skip. Upload a 1 MB JPEG. Crop. Save. Continues.

## 9. Out of scope

- "Edit crop without re-uploading" — see §6 follow-up note.
- Square / hexagonal / non-circular avatar masks — circle is the only shape today.
- Profile-photo nags for users who skip — no banner, no email, no re-prompt. Their choice is final until they manually visit settings.
- Bulk admin tools for resetting onboarding flags — admins can update individual rows in psql if needed.
- Animated GIF avatars — single-frame PNG output only.
- A CDN in front of `/api/v1/photos/...` or `/api/v1/users/.../avatar/...` — current S3-via-Spring-controller path is fine for our scale.

## 10. Migration / rollout

1. Both pipelines fire from the same merge to `main` — Amplify rebuilds on every push regardless of paths, ECS rolls when `backend/**` changes. The deploys finish in different orders depending on build times.
2. **Deploy-gap behaviour** (new frontend deployed before new backend taskdef rolls): the new `(onboarded)/layout.tsx` reads `user.avatarStepCompleted`. The old backend doesn't emit that field → frontend sees `undefined`. The redirect condition is `=== false`, so `undefined === false` is false → NO redirect → users stay on the normal dashboard. Once the new backend takes over, the field surfaces and redirects activate. No broken state during the gap.
3. **Reverse-gap** (new backend before new frontend): old frontend ignores unknown fields. Backend behaviour is unchanged for old clients.
4. **V20 migration applies on backend startup** (Flyway). Existing users with `profile_pic_url IS NOT NULL` get `avatar_step_completed = true`; existing users with `display_name IS NOT NULL` get `display_name_step_completed = true`. Pre-existing verified users with neither set will hit the gate on next visit.
5. There is no feature flag. If we need to disable the gate post-deploy, an admin SQL `UPDATE users SET avatar_step_completed = true, display_name_step_completed = true` skips all current users past the gate; new registrations would still flow through. Truly turning the feature off requires a frontend revert.
