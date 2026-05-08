# Avatar + Display-Name Onboarding Implementation Plan

**Goal:** Ship the two-step forced onboarding flow defined in [the design doc](../specs/2026-05-08-avatar-and-display-name-onboarding-design.md) — avatar (SL photo crop / upload / skip) followed by display-name (pre-filled with username, save / skip), gated post-verify.

**Architecture:** New `(onboarded)` route group nested inside `(verified)` with its own layout-level redirect. Two new `users` columns (`avatar_step_completed`, `display_name_step_completed`) drive the gate. Backend gets a SL profile photo scraper, two onboarding endpoints, and an `AvatarService.upload` flag flip. Frontend gets a shared `<AvatarCropper>` component (react-easy-crop), two new pages, and a dashboard route restructure.

**Tech Stack:** Spring Boot 4 / Java 26 / Hibernate / Flyway / jsoup (new) / WebClient / Redis. Next.js 16 / React 19 / TypeScript 5 / Tailwind 4 / react-easy-crop (new) / vitest.

---

## Task 1 — User entity + V20 migration

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/User.java`
- Create: `backend/src/main/resources/db/migration/V20__avatar_and_display_name_onboarding.sql`
- Modify: `backend/src/test/java/com/slparcelauctions/backend/user/UserEntityTest.java`

**Steps:**

1. Add two columns to `User.java` after `emailVerified`:

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

2. Write `V20__avatar_and_display_name_onboarding.sql`:

```sql
ALTER TABLE users
    ADD COLUMN avatar_step_completed boolean NOT NULL DEFAULT false,
    ADD COLUMN display_name_step_completed boolean NOT NULL DEFAULT false;

UPDATE users SET avatar_step_completed = true       WHERE profile_pic_url IS NOT NULL;
UPDATE users SET display_name_step_completed = true WHERE display_name    IS NOT NULL;
```

3. Add a unit test `UserEntityTest.onboardingFlagsDefaultToFalse` that asserts both flags default to `false` on a fresh `User.builder().build()`.

4. Commit: `feat(user): add onboarding step-completion flags + V20 backfill`.

---

## Task 2 — Extend UserResponse DTO

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/dto/UserResponse.java`
- Modify: any tests that exhaustively assert `UserResponse` field shape

**Steps:**

1. Add two `boolean` fields to the `UserResponse` record after `emailVerified`:

```java
boolean avatarStepCompleted,
boolean displayNameStepCompleted,
```

2. Update both `from(...)` factories to populate them via `user.getAvatarStepCompleted()` / `user.getDisplayNameStepCompleted()`. Boxed `Boolean` from the entity unboxes to `boolean` because `@Builder.Default` ensures non-null.

3. Run the existing `UserResponseUnreadCountTest` (and any other slice tests touching `UserResponse.from`). Update snapshots / assertions if any test exhaustively checks every field.

4. Commit: `feat(user): expose onboarding flags on UserResponse`.

---

## Task 3 — Add jsoup dependency + SlProfilePhotoService

**Files:**
- Modify: `backend/pom.xml` (jsoup)
- Create: `backend/src/main/java/com/slparcelauctions/backend/sl/SlProfilePhotoService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/sl/SlProfilePhotoConfig.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/sl/SlProfilePhotoServiceTest.java`

**Steps:**

1. Add to `backend/pom.xml`:

```xml
<dependency>
    <groupId>org.jsoup</groupId>
    <artifactId>jsoup</artifactId>
    <version>1.18.3</version>
</dependency>
```

2. Create `SlProfilePhotoConfig` with two `@Bean WebClient`s — `slWorldWebClient` (base `https://world.secondlife.com`) and `slPictureServiceWebClient` (base `https://picture-service.secondlife.com`). Both use a shared `ReactorClientHttpConnector` with 5 s connect + 5 s read timeouts and `ExchangeStrategies` capping `maxInMemorySize` at 2 MB.

3. Create `SlProfilePhotoService` per the spec algorithm. Cache key: `sl:profile-photo:{uuid}`. Sentinel `"NONE"` (5 min TTL) for negative cache. Bytes are stored as base64 (1 hour TTL).

4. `SlProfilePhotoServiceTest` cases — use `okhttp3.mockwebserver` (already on classpath via Spring Boot test), feed each WebClient a per-test base URL:
   - HTML with `<img class="parcelimg" src="https://picture-service.secondlife.com/{uuid}/256x192.jpg">` + picture-service returns image bytes → service returns `Optional.of(bytes)`.
   - HTML without `parcelimg` → empty + `NONE` cached. Verify Redis put.
   - World-SL returns 404 → empty + `NONE` cached.
   - Picture-service returns 500 → empty.
   - HTML present but `<img>` `src` host is `https://evil.example.com/foo.jpg` → empty (no fetch attempted; assert mock server got zero requests).
   - Picture-service body > 2 MB → empty.
   - Cache hit (positive base64 entry) → no HTTP traffic, returns decoded bytes.
   - Cache hit (`NONE` sentinel) → no HTTP traffic, returns empty.

5. Commit: `feat(sl): SL profile-photo scraper + Redis-cached fetcher`.

---

## Task 4 — OnboardingService + OnboardingController + DTOs

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/onboarding/OnboardingController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/onboarding/OnboardingService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/onboarding/dto/OnboardingDisplayNameRequest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/onboarding/OnboardingControllerSliceTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/onboarding/OnboardingServiceTest.java`

**Steps:**

1. `OnboardingDisplayNameRequest`:

```java
@JsonIgnoreProperties(ignoreUnknown = false)
public record OnboardingDisplayNameRequest(
        @Size(max = 50, message = "displayName must be at most 50 characters")
        String displayName) {}
```

2. `OnboardingService`:

```java
@Service
@RequiredArgsConstructor
public class OnboardingService {
    private final UserRepository userRepository;

    @Transactional
    public UserResponse skipAvatar(Long userId) {
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        if (!Boolean.TRUE.equals(u.getAvatarStepCompleted())) {
            u.setAvatarStepCompleted(true);
        }
        return UserResponse.from(u);
    }

    @Transactional
    public UserResponse setDisplayName(Long userId, String displayName) {
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        String trimmed = displayName == null ? "" : displayName.trim();
        if (!trimmed.isEmpty()) {
            u.setDisplayName(trimmed);
        }
        if (!Boolean.TRUE.equals(u.getDisplayNameStepCompleted())) {
            u.setDisplayNameStepCompleted(true);
        }
        return UserResponse.from(u);
    }
}
```

3. `OnboardingController` constructor-injects `SlProfilePhotoService slProfilePhoto`, `OnboardingService onboarding`, and `UserRepository userRepository`. The `GET /sl-profile-photo` handler:

```java
@GetMapping("/sl-profile-photo")
public ResponseEntity<byte[]> slProfilePhoto(@AuthenticationPrincipal AuthPrincipal p) {
    User u = userRepository.findById(p.userId())
            .orElseThrow(() -> new UserNotFoundException(p.userId()));
    if (u.getSlAvatarUuid() == null) {
        return ResponseEntity.notFound().build();
    }
    return slProfilePhoto.fetchProfilePhoto(u.getSlAvatarUuid())
            .map(bytes -> ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                    .body(bytes))
            .orElseGet(() -> ResponseEntity.notFound().build());
}
```

The `POST /avatar/skip` and `POST /display-name` handlers delegate to `onboarding.skipAvatar(p.userId())` and `onboarding.setDisplayName(p.userId(), body.displayName())` respectively.

4. `OnboardingServiceTest` (Mockito): `skipAvatar` flips when false, no-ops when true; `setDisplayName` writes trimmed value when non-blank, skips writing when null/empty/whitespace, always flips flag.

5. `OnboardingControllerSliceTest` (`@WebMvcTest`):
   - Anonymous → 401 on all three endpoints.
   - Authenticated `POST /avatar/skip` → 200 + service called with the right userId.
   - `POST /display-name` body `{"displayName": "Alice"}` → 200; body `{"displayName": null}` → 200; body `{"displayName": ""}` → 200; body `{"displayName": "  "}` → 200; body `{"displayName": "<51 chars>"}` → 400; body with extra unknown field → 400 (canary).
   - `GET /sl-profile-photo` with `slProfilePhoto` mocked: empty → 404; bytes → 200 + image/jpeg + Cache-Control header.

6. Commit: `feat(onboarding): controller + service for skip-avatar + set-display-name`.

---

## Task 5 — AvatarService.upload flips avatar_step_completed

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/AvatarService.java`
- Modify: `backend/src/test/java/com/slparcelauctions/backend/user/AvatarServiceTest.java` (extend existing test class)

**Steps:**

1. In `AvatarService.upload`, after `user.setProfilePicUrl(...)`:

```java
user.setProfilePicUrl("/api/v1/users/" + user.getPublicId() + "/avatar/256");
if (!Boolean.TRUE.equals(user.getAvatarStepCompleted())) {
    user.setAvatarStepCompleted(true);
}
```

2. Add `AvatarServiceTest.upload_setsAvatarStepCompleted` — call upload on a fresh user, assert `getAvatarStepCompleted()` is true after.

3. Commit: `feat(avatar): flip avatar_step_completed on upload`.

---

## Task 6 — End-to-end backend integration test

**Files:**
- Create: `backend/src/test/java/com/slparcelauctions/backend/onboarding/OnboardingFlowIntegrationTest.java`

**Steps:**

1. `@SpringBootTest` with real DB (existing `@AutoConfigureMockMvc` + Testcontainers Postgres if used elsewhere; otherwise an `@SpringBootTest` that exercises through the controllers).

2. Cases:
   - Register → verify (use the same flow `UserIntegrationTest` uses) → `POST /onboarding/avatar/skip` → assert `avatar_step_completed=true` in DB.
   - `POST /onboarding/display-name` with `{"displayName": "Alice"}` → assert `display_name="Alice"` AND `display_name_step_completed=true`.
   - `POST /onboarding/display-name` with empty body `{}` → flag flips, displayName remains null.
   - Re-call `POST /avatar/skip` → 200 idempotent.

3. Backfill test (separate test class `OnboardingMigrationBackfillTest` or in the same file): seed two users into the schema BEFORE V20 (use `@FlywayTestExtension` style or a `JdbcTemplate` insert before triggering migration), run `Flyway.migrate()`, assert flags backfilled correctly. **Skip this if Flyway test infrastructure is heavy** — the SQL is simple enough to manually verify, and the unit-level confidence on the SQL itself is high.

4. Commit: `test(onboarding): end-to-end backend flow + V20 backfill verification`.

---

## Task 7 — Frontend: extend CurrentUser type

**Files:**
- Modify: `frontend/src/lib/user/api.ts`
- Modify: `frontend/src/test/msw/fixtures.ts` (add new fields to mock user fixtures)

**Steps:**

1. Add to `CurrentUser`:

```ts
username: string;
avatarStepCompleted: boolean;
displayNameStepCompleted: boolean;
```

2. Update every fixture in `frontend/src/test/msw/fixtures.ts` that constructs a CurrentUser to include `username`, `avatarStepCompleted: true`, `displayNameStepCompleted: true` by default (so existing tests stay unaffected — pre-existing test users skip onboarding).

3. Commit: `feat(user): expose username + onboarding flags on CurrentUser`.

---

## Task 8 — react-easy-crop dependency + cropImage helper

**Files:**
- Modify: `frontend/package.json` (add react-easy-crop)
- Create: `frontend/src/lib/avatar/cropImage.ts`
- Create: `frontend/src/lib/avatar/cropImage.test.ts`

**Steps:**

1. `npm install --save react-easy-crop@^5` from `frontend/`. Re-run `npm run lint` to ensure clean install.

2. `cropImage.ts`:

```ts
import type { Area } from "react-easy-crop";

const OUTPUT_SIZE = 512;

export async function getCroppedImg(imageSrc: string, areaPixels: Area): Promise<Blob> {
  const image = await loadImage(imageSrc);
  const canvas = document.createElement("canvas");
  canvas.width = OUTPUT_SIZE;
  canvas.height = OUTPUT_SIZE;
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("Canvas 2D context unavailable");
  ctx.drawImage(
    image,
    areaPixels.x, areaPixels.y, areaPixels.width, areaPixels.height,
    0, 0, OUTPUT_SIZE, OUTPUT_SIZE,
  );
  return await new Promise<Blob>((resolve, reject) => {
    canvas.toBlob(
      (blob) => (blob ? resolve(blob) : reject(new Error("Canvas toBlob returned null"))),
      "image/png",
    );
  });
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.crossOrigin = "anonymous";
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error("Failed to load image: " + src));
    img.src = src;
  });
}
```

Use plain `<canvas>` not OffscreenCanvas — better Safari coverage with no measurable perf cost at 512×512.

3. `cropImage.test.ts`: minimal — test that `loadImage` rejects on a bad src (use a tiny vitest setup with happy-dom; if Image isn't fully simulated, skip and rely on the AvatarCropper component test for coverage).

4. Commit: `feat(avatar): cropImage helper + react-easy-crop dependency`.

---

## Task 9 — AvatarCropper shared component

**Files:**
- Create: `frontend/src/components/user/AvatarCropper.tsx`
- Create: `frontend/src/components/user/AvatarCropper.test.tsx`

**Steps:**

1. Component per spec §5.2:

```tsx
"use client";
import { useState, useCallback } from "react";
import Cropper, { type Area } from "react-easy-crop";
import { Button } from "@/components/ui/Button";
import { getCroppedImg } from "@/lib/avatar/cropImage";

interface Props {
  imageSrc: string;
  onSave: (blob: Blob) => void | Promise<void>;
  onCancel?: () => void;
  saveLabel?: string;
}

export function AvatarCropper({ imageSrc, onSave, onCancel, saveLabel = "Save" }: Props) {
  const [crop, setCrop] = useState({ x: 0, y: 0 });
  const [zoom, setZoom] = useState(1);
  const [croppedAreaPixels, setCroppedAreaPixels] = useState<Area | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const onCropComplete = useCallback((_area: Area, areaPixels: Area) => {
    setCroppedAreaPixels(areaPixels);
  }, []);

  const handleSave = async () => {
    if (!croppedAreaPixels) return;
    setSaving(true);
    try {
      const blob = await getCroppedImg(imageSrc, croppedAreaPixels);
      await onSave(blob);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to save");
    } finally {
      setSaving(false);
    }
  };

  if (error) {
    return (
      <div className="rounded bg-bg-subtle p-6 text-center">
        <p className="text-fg-muted text-sm">We couldn&apos;t load this image.</p>
        {onCancel && <Button variant="secondary" onClick={onCancel}>Try a different image</Button>}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="relative h-80 w-full bg-bg-subtle rounded">
        <Cropper
          image={imageSrc}
          crop={crop}
          zoom={zoom}
          aspect={1}
          cropShape="round"
          showGrid={false}
          onCropChange={setCrop}
          onZoomChange={setZoom}
          onCropComplete={onCropComplete}
        />
      </div>
      <input
        type="range" min={1} max={3} step={0.01}
        value={zoom}
        onChange={(e) => setZoom(Number(e.target.value))}
        aria-label="Zoom"
      />
      <div className="flex gap-2">
        <Button onClick={handleSave} disabled={saving}>
          {saving ? "Saving…" : saveLabel}
        </Button>
        {onCancel && (
          <Button variant="secondary" onClick={onCancel} disabled={saving}>
            Cancel
          </Button>
        )}
      </div>
    </div>
  );
}
```

2. `AvatarCropper.test.tsx`:
   - Mocks `react-easy-crop` (the inner Cropper is hard to drive in jsdom — mock its onCropComplete to fire synthetically).
   - Save calls `onSave` with a Blob produced by `getCroppedImg` (mocked).
   - Render with a known-bad imageSrc → simulated error state shows.
   - Cancel calls onCancel.

3. Commit: `feat(avatar): shared AvatarCropper component`.

---

## Task 10 — Replace ProfilePictureUploader inner with cropper

**Files:**
- Modify: `frontend/src/components/user/ProfilePictureUploader.tsx`
- Modify: existing `ProfilePictureUploader.test.tsx` if present (Glob in repo to confirm)

**Steps:**

1. After file pick (when state is `file-selected`), render `<AvatarCropper imageSrc={state.previewUrl} onSave={uploadBlob} onCancel={resetToIdle}>` instead of the existing preview + Upload button.

2. The `uploadBlob` handler hits the existing `useUploadAvatar` mutation, but with a `Blob` instead of `File` — the existing hook accepts File; either widen its signature to `File | Blob` or wrap the blob in a new `File([blob], 'avatar.png', { type: 'image/png' })` before passing in. Pick the wrap — leaves the mutation hook untouched.

3. Update the existing test (if any) to assert the cropper appears post-pick.

4. Commit: `refactor(profile): use shared AvatarCropper in settings uploader`.

---

## Task 11 — Restructure dashboard route groups

**Files:**
- Move: `frontend/src/app/dashboard/(verified)/overview/` → `frontend/src/app/dashboard/(verified)/(onboarded)/overview/`
- Move: `frontend/src/app/dashboard/(verified)/bids/` → `frontend/src/app/dashboard/(verified)/(onboarded)/bids/`
- Move: `frontend/src/app/dashboard/(verified)/listings/` → `frontend/src/app/dashboard/(verified)/(onboarded)/listings/`
- Modify: `frontend/src/app/dashboard/(verified)/layout.tsx` (strip tabs / chrome — keep verify-redirect only)
- Create: `frontend/src/app/dashboard/(verified)/(onboarded)/layout.tsx`
- Modify: `frontend/src/app/dashboard/(verified)/layout.test.tsx` (update assertions)
- Create: `frontend/src/app/dashboard/(verified)/(onboarded)/layout.test.tsx`

**Steps:**

1. Use `git mv` for the three subfolders to preserve history.

2. Rewrite `(verified)/layout.tsx` to verify-redirect only:

```tsx
"use client";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { useCurrentUser } from "@/lib/user";

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

3. Write `(onboarded)/layout.tsx` that does the onboarding redirects AND renders the dashboard chrome (h1 + tabs + container) that used to live in `(verified)/layout.tsx`.

4. Update `(verified)/layout.test.tsx` to test only verify-redirect. Move the tabs-rendering assertions into a new `(onboarded)/layout.test.tsx`. Add tests for the onboarding redirect logic per spec §8.2.

5. Commit: `refactor(dashboard): split (verified) and (onboarded) layout responsibilities`.

---

## Task 12 — Avatar onboarding page

**Files:**
- Create: `frontend/src/app/dashboard/(verified)/avatar/page.tsx`
- Create: `frontend/src/app/dashboard/(verified)/avatar/page.test.tsx`
- Modify: `frontend/src/lib/user/api.ts` (add `skipAvatar`, ` getSlProfilePhoto` helpers if not naturally extending existing functions)

**Steps:**

1. Page implements spec §5.4. On mount:
   - If `user.avatarStepCompleted` → forward redirect to `/dashboard/display-name` (or `/dashboard/overview` if both done).
   - Fetch `GET /api/v1/users/me/onboarding/sl-profile-photo` as a Blob (use `fetch` with `apiUrl` because `api.get` likely expects JSON). `URL.createObjectURL(blob)` → cropper source.
   - 404 → no-photo branch (file picker shown directly).

2. Buttons:
   - "Save this avatar" → `useUploadAvatar` mutation with the cropper output blob → invalidate `useCurrentUser` → `router.push('/dashboard/display-name')`.
   - "Upload a different image" → opens file picker, replaces cropper source.
   - "Skip — no avatar" → `POST /api/v1/users/me/onboarding/avatar/skip` → invalidate → push to display-name.

3. `page.test.tsx`:
   - Mock `/sl-profile-photo` returning a Blob → cropper renders.
   - Mock `/sl-profile-photo` returning 404 → upload form renders.
   - Click Skip → POST observed → router.push called.
   - Page mounted with `avatarStepCompleted=true` → forward redirect (assert router.replace).

4. Commit: `feat(onboarding): avatar gate page with SL photo + crop`.

---

## Task 13 — Display-name onboarding page

**Files:**
- Create: `frontend/src/app/dashboard/(verified)/display-name/page.tsx`
- Create: `frontend/src/app/dashboard/(verified)/display-name/page.test.tsx`
- Modify: `frontend/src/lib/user/api.ts` (add `submitDisplayName(displayName: string | null)` helper)

**Steps:**

1. Page per spec §5.5. Single `<input type="text" maxLength={50}>` pre-filled with `user.username`. Two buttons: Save (POST with current value) and Skip (POST with `null`).

2. After response, invalidate `useCurrentUser`, `router.push('/dashboard/overview')`.

3. Page self-redirects if `user.displayNameStepCompleted` is already true.

4. `page.test.tsx`:
   - Renders with input pre-filled = mock user's username.
   - Save with default → POST body has `{ displayName: <username> }`.
   - Save with custom (typed value) → POST body has typed value.
   - Skip → POST body has `{ displayName: null }`.
   - Each → router.push to `/dashboard/overview`.

5. Commit: `feat(onboarding): display-name gate page`.

---

## Task 14 — Final verification + Postman + README

**Files:**
- Modify: Postman collection (mirror new endpoints)
- Modify: `README.md` (root) — add a one-line entry under the recent-work bullets about the onboarding flow

**Steps:**

1. Postman: under `SLPA Dev` collection, add three requests under `Onboarding`:
   - `GET /api/v1/users/me/onboarding/sl-profile-photo` (returns image bytes)
   - `POST /api/v1/users/me/onboarding/avatar/skip`
   - `POST /api/v1/users/me/onboarding/display-name` with body `{"displayName": "{{displayName}}"}` and a sibling Skip variant with body `{}`.
   - Variable-thread `accessToken` from prior Login request.

2. Update root `README.md` per the user's "update README each task" rule. Add under the slice-by-slice description:

```
- **Avatar + display-name onboarding** — post-verify forced steps. Avatar gate
  pulls SL profile photo from world.secondlife.com (jsoup parse, Redis-cached)
  with a pan/zoom cropper (react-easy-crop), or upload your own, or skip.
  Display-name gate offers a text field pre-filled with the SLParcels username
  with save/skip. Two boolean columns on `users` (`avatar_step_completed`,
  `display_name_step_completed`) drive a new `(onboarded)` route group.
```

3. Run full backend test suite (`./mvnw test`) and full frontend (`npm test && npm run lint && npm run verify`) — fix anything red.

4. Commit: `chore: postman + README for onboarding flow`.

---

## Task 15 — Manual smoke + ship

**Steps:**

1. Push the branch (`feat/avatar-display-name-onboarding`), open PR → `dev`.
2. After CI green, squash-merge to `dev`.
3. PR `dev` → `main` (explicit per-task authorization granted by user).
4. After both pipelines deploy:
   - Tail `aws --profile slpa-prod ecs describe-services --cluster slpa-prod --services slpa-prod-backend` until rolloutState COMPLETED.
   - Verify Amplify build green.
5. Manual smoke per spec §8.3 against prod.

---

## Self-review checklist

- [ ] Spec §3 routing matches Task 11 file moves.
- [ ] Spec §4.5 `OnboardingDisplayNameRequest` matches Task 4 record definition.
- [ ] Spec §5.6 layout split matches Task 11 layout edits.
- [ ] Migration version V20 (V19 is the latest on disk).
- [ ] Backfill SQL is plain SQL, no PG-version-specific syntax.
- [ ] `react-easy-crop` v5+ is current MIT release (verify on npm).
- [ ] No placeholders, no "TBD".
- [ ] CurrentUser type addition includes `username` (not just the two flags).
- [ ] AvatarService.upload flag flip is in the same transaction as the existing setProfilePicUrl write.
- [ ] AvatarCropper handles error state (image-load failure) per spec §7.
- [ ] OnboardingService trims whitespace before deciding skip-vs-write.
- [ ] Tests cover: scrape success, all scrape-failure paths, cache hit/miss, controller validation, idempotent flag flips, layout redirect logic, avatar page two-branch render, display-name page pre-fill + three button paths.
