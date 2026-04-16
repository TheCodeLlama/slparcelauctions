# Epic 02 — Sub-spec 2b: Dashboard UI + Public Profile Page

**Date:** 2026-04-14
**Epic:** 02 — Player Verification
**Sub-spec scope:** Task 02-04 (dashboard verification UI) + Task 02-05 (public profile page)
**Status:** design
**Author:** brainstorming session

---

## 1. Summary

Ship the frontend half of Epic 02's profile work: an authenticated user visits `/dashboard` and either (a) lands on a full-page verification flow if their SL avatar isn't linked yet, or (b) lands on the Overview tab of a tabbed dashboard showing their verified SL identity, account settings, profile picture upload, and placeholder tabs for future bids and listings content. Any client visits `/users/[id]` and sees a read-only public profile with reputation placeholders. All backend endpoints already exist from sub-spec 2a.

After this sub-spec merges, Epic 02 is functionally complete — a new user can register, verify their SL avatar (via the dev-profile simulate helper until Epic 11 ships real LSL integration), edit their profile, upload an avatar, and have their public profile visible to other users.

---

## 2. Scope

### In scope

1. **Dashboard route structure.** `frontend/src/app/dashboard/` gets a new `layout.tsx`, a redirect `page.tsx`, a `verify/page.tsx` takeover, and a `(verified)/` route group with three sub-routes (`overview`, `bids`, `listings`). The verified route group's `layout.tsx` carries the "must be verified" gate that redirects to `/dashboard/verify` if the user hasn't linked an SL avatar yet.
2. **Public profile route.** `frontend/src/app/users/[id]/page.tsx` server-validates the ID and renders a client-side `<PublicProfileView />` that calls `GET /api/v1/users/:id` via TanStack Query. 404 handling for invalid or nonexistent user IDs via Next.js `notFound()`.
3. **Five new `components/ui/` primitives.** `Tabs` (next-link-based nav primitive), `CountdownTimer` (MM:SS display), `CodeDisplay` (large-mono-type + copy button), `EmptyState` (icon + headline + description), `Toast` (portal-rendered stack with `useToast()` hook).
4. **Nine new `components/user/` domain composites.** `VerificationCodeDisplay`, `UnverifiedVerifyFlow`, `VerifiedIdentityCard`, `ProfilePictureUploader`, `ProfileEditForm`, `VerifiedOverview`, `PublicProfileView`, `ReputationStars`, `NewSellerBadge`.
5. **`lib/user/` data layer.** `api.ts` typed wrappers for the four user-facing backend endpoints, `hooks.ts` with `useCurrentUser`/`useUpdateProfile`/`useUploadAvatar`, `CurrentUser`/`PublicUserProfile` types. Separate from the existing `lib/auth/` which stays narrow.
6. **`lib/api.ts` extension for FormData bodies.** The existing `api.post` assumes JSON; sub-spec 2b extends it to detect `FormData` instances and skip setting `Content-Type` so the browser sets `multipart/form-data; boundary=...` automatically.
7. **One backend touch-up.** Add `OffsetDateTime updatedAt` field to `UserResponse` record + wire from `user.getUpdatedAt()`. The field already exists on the `User` entity via `@UpdateTimestamp`; the backend DTO just needs it projected. This is load-bearing for the frontend's `<Avatar cacheBust={user.updatedAt}>` pattern — see §10.
8. **ToastProvider wired into `frontend/src/app/providers.tsx`** alongside the existing `QueryClientProvider` and `ThemeProvider`.
9. **Polling-based post-verification transition.** The `/dashboard/verify` page polls `GET /me` every 5 seconds (visibility-aware — pauses when tab backgrounded) AND exposes a manual "Refresh status" button, so the user can either wait for the poll or click for instant transition after firing the Dev simulate helper.
10. **Three levels of tests.** Per-primitive unit tests, per-domain-component tests with MSW-backed hooks, per-page integration smoke tests covering the verify-to-overview transition and the public profile render.

### Out of scope (deferred to future epics)

- **Task 02-03 backend** — already shipped in sub-spec 2a (merged to `dev` on 2026-04-14). This sub-spec consumes those endpoints.
- **Real SL integration** — the LSL script that calls `POST /api/v1/sl/verify` is Epic 11 work. Sub-spec 2b uses the dev-profile `Dev/Simulate SL verify` Postman helper for the verification flow end-to-end.
- **WebSocket push for verification completion** — earlier brainstorming considered STOMP push over the existing WS infrastructure. Deferred to Epic 11 when real LSL integration lands, because the backend publisher needs to know when a real avatar verification call succeeds. Polling is the correct tool for sub-spec 2b's dev-flow scope.
- **Partial-star visual rendering** for `ReputationStars`. Phase 1 ships with a simpler numeric "4.7 ★" display. Partial-star SVG rendering lands later when review counts actually exist.
- **Drag-drop for listing image uploads** (Epic 04). `ProfilePictureUploader` gets drag-drop, but the primitive for listing-image upload (which will support multi-file) is a future Epic 04 concern.
- **Password change / email change flows.** The dashboard's profile edit form only covers `displayName` and `bio` per sub-spec 2a's `UpdateUserRequest` contract.
- **Active listings / recent reviews data.** Public profile shows empty-state placeholders for both sections. Real data lands when Epic 04 (auctions) and Epic 06 (reviews) ship.
- **Account deletion UI.** `DELETE /me` stays 501 per sub-spec 2a; the dashboard doesn't expose a delete button.

### Non-goals

- **No new routes beyond dashboard + public profile.** Sub-spec 2b does not touch `/browse`, `/auction/*`, or any other route.
- **No changes to the existing `useAuth` hook.** It stays narrow and session-state focused. `useCurrentUser` is additive.
- **No changes to the existing `components/ui/` primitives** (Button, Card, Input, Avatar, etc.). Task 5 of 2b adds NEW primitives; existing ones are consumed as-is.
- **No design system tokens added.** All styling uses existing Tailwind tokens (`bg-surface-container`, `text-on-surface-variant`, `border-primary`, etc.) from the Digital Curator design system locked in Epic 01.
- **No new Java dependencies.** The backend touch-up is a one-field addition to an existing record.

---

## 3. Background and references

- `docs/implementation/epic-02/02-player-verification.md` — Epic 02 goal.
- `docs/implementation/epic-02/task-04-dashboard-verification-ui.md` — Task 02-04 spec.
- `docs/implementation/epic-02/task-05-public-profile-page.md` — Task 02-05 spec.
- `docs/superpowers/specs/2026-04-14-epic-02-sub-2a-profile-api-avatar-upload.md` — immediate predecessor; established the backend endpoints this sub-spec consumes.
- `docs/implementation/CONVENTIONS.md` — project-wide rules (RSC first, TanStack Query for API state, design system tokens only, modular component architecture).
- `docs/stitch_generated-design/DESIGN.md` — binding design system reference.
- `docs/stitch_generated-design/dark_mode/user_dashboard/code.html` and `light_mode/user_dashboard/code.html` — visual reference for the tabbed dashboard aesthetic (this is a bidding dashboard, not a verification dashboard; the verification flow has no stitch mockup and is designed from scratch).
- `frontend/src/app/layout.tsx` — root layout with `AppShell` wrapping.
- `frontend/src/app/providers.tsx` — where `ToastProvider` will wire in alongside `QueryClientProvider`.
- `frontend/src/components/auth/RequireAuth.tsx` — existing auth gate for `/login` redirect.
- `frontend/src/components/ui/Avatar.tsx` — existing primitive; sub-spec 2b adds a `cacheBust` prop for avatar re-upload cache invalidation.
- `frontend/src/lib/auth/hooks.ts` — existing `useAuth` hook and TanStack Query session bootstrap pattern. Sub-spec 2b matches this pattern for the new `useCurrentUser` hook.
- `frontend/src/lib/api.ts` — existing API client. Sub-spec 2b extends it with `FormData` support.
- `frontend/src/test/msw/handlers.ts` — existing MSW handler factory pattern. Sub-spec 2b adds `userHandlers` and `verificationHandlers` alongside the existing `authHandlers`.
- `frontend/AGENTS.md` — "This is NOT the Next.js you know" warning. Sub-spec 2b uses route groups, `usePathname`, `useSearchParams`, and `router.replace` — all per Next.js 16 conventions. Read `frontend/node_modules/next/dist/docs/` before writing any routing code.

---

## 4. API surface (frontend consumes backend endpoints from sub-spec 2a)

No new backend endpoints. Sub-spec 2b's frontend calls endpoints that already exist:

```
GET  /api/v1/users/me                      auth, returns CurrentUser
PUT  /api/v1/users/me                      auth, returns updated CurrentUser
POST /api/v1/users/me/avatar               auth, multipart/form-data, returns updated CurrentUser
GET  /api/v1/users/{id}/avatar/{size}      public, returns image/png bytes
GET  /api/v1/users/{id}                    public, returns UserProfileResponse (narrower than CurrentUser)

GET  /api/v1/verification/active           auth, returns ActiveCodeResponse or 404
POST /api/v1/verification/generate         auth, returns GenerateCodeResponse

POST /api/v1/dev/sl/simulate-verify        public (dev profile), simulates SL verification for testing
```

All of these exist and are tested at the backend integration level from sub-spec 2a.

**New frontend TypeScript types** (see §6 for the full definitions):
- `CurrentUser` — 18 fields after the `updatedAt` touch-up (17 from sub-spec 2a + `updatedAt`)
- `PublicUserProfile` — 14 fields, narrower read-only shape from `GET /users/:id`

---

## 5. Route structure

### Dashboard routes

```
frontend/src/app/dashboard/
├── layout.tsx                    shared: RequireAuth wrapper
├── page.tsx                      thin redirect: /dashboard → /dashboard/verify OR /dashboard/overview
├── verify/
│   └── page.tsx                  full-page verification takeover — <UnverifiedVerifyFlow />
└── (verified)/                   route group, doesn't appear in URL
    ├── layout.tsx                tab rail + gate: redirects to /dashboard/verify if !verified
    ├── overview/
    │   └── page.tsx              <VerifiedOverview />
    ├── bids/
    │   └── page.tsx              <EmptyState /> "No bids yet"
    └── listings/
        └── page.tsx              <EmptyState /> "No listings yet"
```

**Route group mechanics (Next.js 16):**

- `(verified)/` is a route group per Next.js 16 convention — the parenthesized segment does NOT appear in the URL. So the user's address bar shows `/dashboard/overview`, not `/dashboard/(verified)/overview`.
- The group's `layout.tsx` is a shared layout for all routes inside it. The gate logic (redirect if unverified) runs ONCE per sub-route mount, not per page component render.
- The `verify/page.tsx` route sits OUTSIDE the group, so it has no gate — an unverified user can access it freely. If a verified user somehow lands on `/dashboard/verify`, the page component itself uses a local `useEffect` to redirect to `/dashboard/overview`.
- The root `dashboard/layout.tsx` wraps BOTH `verify/` and `(verified)/` with `<RequireAuth>` — this handles the unauthenticated `/login` redirect before any verification logic fires.

**`dashboard/page.tsx` — the root redirect:**

```tsx
"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { useCurrentUser } from "@/lib/user/hooks";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";

export default function DashboardIndex() {
  const router = useRouter();
  const { data: user, isPending, isError } = useCurrentUser();

  useEffect(() => {
    if (isPending || isError) return;
    if (user?.verified) {
      router.replace("/dashboard/overview");
    } else {
      router.replace("/dashboard/verify");
    }
  }, [isPending, isError, user?.verified, router]);

  return <LoadingSpinner />;
}
```

**Note on `LoadingSpinner`:** This is a tiny primitive that may or may not already exist in `components/ui/`. If not present, sub-spec 2b adds it as a sixth UI primitive (just a centered spinner + optional label, ~15 lines). Check `components/ui/` before writing it.

### Public profile route

```
frontend/src/app/users/
└── [id]/
    └── page.tsx                  server component — validates ID, renders <PublicProfileView />
```

**`users/[id]/page.tsx` — server component that delegates to a client view:**

```tsx
import { notFound } from "next/navigation";
import { PublicProfileView } from "@/components/user/PublicProfileView";

type Props = { params: Promise<{ id: string }> };

export default async function PublicProfilePage({ params }: Props) {
  const { id } = await params;
  const userId = Number(id);
  if (!Number.isInteger(userId) || userId <= 0) {
    notFound();
  }
  return <PublicProfileView userId={userId} />;
}
```

**Why server component + client view.** The page validates the path parameter (cheap server-side work) and delegates to a client component that owns TanStack Query. This is the canonical Next.js 16 App Router composition pattern — server components for routing + validation, client components for interactivity + data.

**Next.js 16 note.** The `params` prop is a Promise in Next.js 16 (changed from a plain object in 15). Always `await params` at the top of the page function.

---

## 6. Data fetching — `lib/user/` layer

### 6.1 Typed API layer (`lib/user/api.ts`)

```typescript
import { api } from "@/lib/api";

export type CurrentUser = {
  id: number;
  email: string;
  displayName: string | null;
  bio: string | null;
  profilePicUrl: string | null;
  slAvatarUuid: string | null;
  slAvatarName: string | null;
  slUsername: string | null;
  slDisplayName: string | null;
  slBornDate: string | null;       // ISO date
  slPayinfo: number | null;         // 0-3 (DATA_PAYINFO enum)
  verified: boolean;
  verifiedAt: string | null;        // ISO datetime
  emailVerified: boolean;
  notifyEmail: Record<string, unknown>;
  notifySlIm: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;                // NEW — added by §10 backend touch-up
};

export type PublicUserProfile = {
  id: number;
  displayName: string | null;
  bio: string | null;
  profilePicUrl: string | null;
  slAvatarUuid: string | null;
  slAvatarName: string | null;
  slUsername: string | null;
  slDisplayName: string | null;
  verified: boolean;
  avgSellerRating: number | null;
  avgBuyerRating: number | null;
  totalSellerReviews: number;
  totalBuyerReviews: number;
  completedSales: number;
  createdAt: string;
};

export type UpdateProfileRequest = {
  displayName?: string;
  bio?: string;
};

export const userApi = {
  me: () => api.get<CurrentUser>("/api/v1/users/me"),
  updateMe: (body: UpdateProfileRequest) =>
    api.put<CurrentUser>("/api/v1/users/me", body),
  uploadAvatar: (file: File) => {
    const form = new FormData();
    form.append("file", file);
    return api.post<CurrentUser>("/api/v1/users/me/avatar", form);
  },
  publicProfile: (id: number) =>
    api.get<PublicUserProfile>(`/api/v1/users/${id}`),
};

export type ActiveCodeResponse = {
  code: string;
  expiresAt: string;
};

export type GenerateCodeResponse = {
  code: string;
  expiresAt: string;
};

export const verificationApi = {
  active: () => api.get<ActiveCodeResponse>("/api/v1/verification/active"),
  generate: () => api.post<GenerateCodeResponse>("/api/v1/verification/generate"),
};
```

**Note on `api.post<T>(url, FormData)`.** The existing `lib/api.ts` client sets `Content-Type: application/json` and `JSON.stringify`s the body. Sub-spec 2b extends it: if `body instanceof FormData`, skip both — let the browser set `multipart/form-data; boundary=...` from the FormData. This is a ~5-line change to the `request<T>` function in `lib/api.ts`, covered by Task 1.

### 6.2 TanStack Query hooks (`lib/user/hooks.ts`)

```typescript
"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth";
import { useToast } from "@/components/ui/Toast";
import {
  userApi,
  verificationApi,
  type CurrentUser,
  type PublicUserProfile,
  type UpdateProfileRequest,
} from "./api";

const CURRENT_USER_KEY = ["currentUser"] as const;
const VERIFICATION_ACTIVE_KEY = ["verification", "active"] as const;

/**
 * Fetches the full UserResponse from GET /api/v1/users/me. Separate from useAuth
 * (which returns a narrow AuthUser for session-state gating) so that components
 * consuming profile data don't re-render every time the session cache changes.
 *
 * The refetchInterval option enables visibility-aware polling for the dashboard
 * verify page — pass 5000 to poll every 5 seconds while the tab is visible,
 * omit or pass false to disable polling.
 *
 * @param options.refetchInterval - milliseconds between refetches, or false
 */
export function useCurrentUser(options?: { refetchInterval?: number | false }) {
  const session = useAuth();
  return useQuery({
    queryKey: CURRENT_USER_KEY,
    queryFn: () => userApi.me(),
    enabled: session.status === "authenticated",
    staleTime: 60_000,
    gcTime: Infinity,
    refetchInterval: options?.refetchInterval ?? false,
    refetchIntervalInBackground: false,  // pause polling when tab is hidden
    refetchOnWindowFocus: true,           // catch changes from other tabs
    retry: false,
  });
}

/**
 * PUT /api/v1/users/me mutation. On success, invalidates ["currentUser"] to
 * refetch the canonical shape from the server (avoids drift between optimistic
 * updates and backend-computed fields like updatedAt).
 */
export function useUpdateProfile() {
  const queryClient = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: (body: UpdateProfileRequest) => userApi.updateMe(body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: CURRENT_USER_KEY });
      toast.success("Profile updated");
    },
    onError: (error) => {
      // Inline FormError handles this per-form; toast is redundant for form
      // validation failures. Show toast only for unexpected errors.
      if (!(error instanceof ApiError && error.status === 400)) {
        toast.error("Failed to update profile");
      }
    },
  });
}

/**
 * POST /api/v1/users/me/avatar multipart mutation. On success, invalidates
 * ["currentUser"] so the new updatedAt value propagates to any Avatar component
 * using it as a cacheBust key.
 */
export function useUploadAvatar() {
  const queryClient = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: (file: File) => userApi.uploadAvatar(file),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: CURRENT_USER_KEY });
      toast.success("Avatar uploaded");
    },
    onError: (error) => {
      if (error instanceof ApiError && error.status === 413) {
        toast.error("Avatar must be 2MB or less");
      } else if (error instanceof ApiError && error.status === 400) {
        toast.error("Upload must be a JPEG, PNG, or WebP image");
      } else {
        toast.error("Failed to upload avatar");
      }
    },
  });
}

/**
 * GET /api/v1/verification/active — fetches the user's currently-active code,
 * if any. 404 response (no active code) surfaces as a null result rather than
 * an error via the select transform.
 */
export function useActiveVerificationCode() {
  return useQuery({
    queryKey: VERIFICATION_ACTIVE_KEY,
    queryFn: async () => {
      try {
        return await verificationApi.active();
      } catch (error) {
        if (error instanceof ApiError && error.status === 404) {
          return null;
        }
        throw error;
      }
    },
    staleTime: 30_000,
  });
}

/**
 * POST /api/v1/verification/generate mutation. On success, invalidates
 * ["verification", "active"] so the new code hydrates into the display.
 */
export function useGenerateVerificationCode() {
  const queryClient = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: () => verificationApi.generate(),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: VERIFICATION_ACTIVE_KEY });
      toast.success("New verification code generated");
    },
    onError: () => {
      toast.error("Failed to generate verification code");
    },
  });
}
```

**Why `enabled: session.status === "authenticated"`.** Without this guard, `useCurrentUser` fires a `/me` request before the auth bootstrap completes, hits a 401, and the 401 interceptor tries to refresh — which races the auth bootstrap itself. Gating on the session status ensures `/me` only fires after the auth layer has confirmed the user is logged in.

**Why `staleTime: 60_000` on `useCurrentUser`.** Profile data (bio, avatar URL, settings) changes infrequently. A 1-minute staleness window prevents unnecessary refetches on tab switches and re-renders while still catching out-of-band updates reasonably quickly.

**Why `retry: false`.** A 401 on `/me` is legitimate unauthenticated state (e.g., token expired between auth bootstrap and the `/me` fetch). Retrying silently masks the real failure.

---

## 7. Primitive inventory

### `components/ui/` — generic reusable primitives (6 new)

| Component | File | Purpose |
|---|---|---|
| `Tabs` | `Tabs.tsx` + test | Next-link-based nav rail. Reads `usePathname()` internally. |
| `CountdownTimer` | `CountdownTimer.tsx` + test | MM:SS countdown to a target Date. `onExpire` callback. |
| `CodeDisplay` | `CodeDisplay.tsx` + test | Large mono-type code + copy button. |
| `Toast` | `Toast.tsx` + `ToastProvider.tsx` + `useToast.ts` + tests | Portal-rendered toast stack + hook. |
| `EmptyState` | `EmptyState.tsx` + test | Icon + headline + description for empty collections. |
| `LoadingSpinner` | `LoadingSpinner.tsx` + test | Centered spinner + optional label. Added only if not already present. |

### `components/user/` — domain-specific composites (9 new)

| Component | File | Purpose |
|---|---|---|
| `VerificationCodeDisplay` | `VerificationCodeDisplay.tsx` + test | Composes `CodeDisplay` + `CountdownTimer` + generate/regenerate handlers |
| `UnverifiedVerifyFlow` | `UnverifiedVerifyFlow.tsx` + test | `/dashboard/verify` page body: code display + instructions + manual refresh button |
| `VerifiedIdentityCard` | `VerifiedIdentityCard.tsx` + test | SL identity display. Variants: `"dashboard"` and `"public"` |
| `ProfilePictureUploader` | `ProfilePictureUploader.tsx` + test | Drag-drop + click-select + preview + upload |
| `ProfileEditForm` | `ProfileEditForm.tsx` + test | displayName + bio edit form with RHF + Zod |
| `VerifiedOverview` | `VerifiedOverview.tsx` + test | `/dashboard/overview` composition — two-column layout |
| `PublicProfileView` | `PublicProfileView.tsx` + test | `/users/[id]` composition — profile + reputation + placeholders |
| `ReputationStars` | `ReputationStars.tsx` + test | Star rating display. Phase 1 ships numeric, partial-star deferred |
| `NewSellerBadge` | `NewSellerBadge.tsx` + test | Chip shown if `completedSales < 3` |

**Total new frontend files:** 6 UI primitives + 9 domain components + the `dashboard/` route tree + `users/[id]/page.tsx` + `lib/user/` layer + MSW handler additions.

---

## 8. UI primitive design

### 8.1 `Tabs.tsx`

```typescript
"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/cn";

export type TabItem = {
  id: string;
  label: string;
  href: string;
};

type TabsProps = {
  tabs: readonly TabItem[];
  className?: string;
};

export function Tabs({ tabs, className }: TabsProps) {
  const pathname = usePathname();

  return (
    <nav
      role="tablist"
      aria-label="Dashboard sections"
      className={cn(
        "flex gap-8 border-b border-outline-variant/20 overflow-x-auto",
        className
      )}
    >
      {tabs.map((tab) => {
        const isActive = pathname === tab.href || pathname.startsWith(`${tab.href}/`);
        return (
          <Link
            key={tab.id}
            href={tab.href}
            role="tab"
            aria-selected={isActive}
            aria-controls={`${tab.id}-panel`}
            className={cn(
              "pb-4 px-2 font-headline font-bold text-lg transition-colors",
              isActive
                ? "text-primary border-b-2 border-primary"
                : "text-on-surface-variant hover:text-on-surface"
            )}
          >
            {tab.label}
          </Link>
        );
      })}
    </nav>
  );
}
```

- Active tab match: exact path OR path prefix (so `/dashboard/overview/settings` would still activate the `Overview` tab if such a nested route were added).
- Keyboard accessible via native `<Link>` focus behavior; no custom arrow-key navigation because that's over-scope for Phase 1 (tabs have few items).
- Responsive: `overflow-x-auto` lets the rail horizontally scroll on mobile if tabs overflow. Matches the stitch reference's horizontal scroll behavior.

### 8.2 `CountdownTimer.tsx`

```typescript
"use client";

import { useEffect, useState } from "react";
import { cn } from "@/lib/cn";

type CountdownTimerProps = {
  expiresAt: Date;
  onExpire?: () => void;
  format?: "mm:ss" | "hh:mm:ss";
  className?: string;
};

export function CountdownTimer({
  expiresAt,
  onExpire,
  format = "mm:ss",
  className,
}: CountdownTimerProps) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const interval = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(interval);
  }, []);

  const remaining = Math.max(0, expiresAt.getTime() - now);
  const seconds = Math.floor(remaining / 1000) % 60;
  const minutes = Math.floor(remaining / 60_000) % 60;
  const hours = Math.floor(remaining / 3_600_000);

  useEffect(() => {
    if (remaining === 0 && onExpire) {
      onExpire();
    }
  }, [remaining, onExpire]);

  const display =
    format === "hh:mm:ss"
      ? `${hours.toString().padStart(2, "0")}:${minutes.toString().padStart(2, "0")}:${seconds.toString().padStart(2, "0")}`
      : remaining === 0
      ? "--:--"
      : `${minutes.toString().padStart(2, "0")}:${seconds.toString().padStart(2, "0")}`;

  return (
    <span className={cn("font-mono text-2xl", className)} aria-live="polite">
      {display}
    </span>
  );
}
```

- `setInterval(1000)` is sufficient for second-precision. Not `requestAnimationFrame` — 60 fps is overkill, and rAF throttles aggressively when the tab is backgrounded, which would break the countdown.
- `now` recalculates from `Date.now()` on every tick, so clock drift and tab-throttling are self-correcting. A backgrounded tab that wakes up after 30 seconds immediately shows the correct remaining time.
- `onExpire` fires once when `remaining` crosses zero. Guarded by the `useEffect` dependency array to prevent firing every tick after expiry.
- `aria-live="polite"` so screen readers announce countdown updates without interrupting other content.

### 8.3 `CodeDisplay.tsx`

```typescript
"use client";

import { IconButton } from "@/components/ui/IconButton";
import { Icons } from "@/components/ui/icons";
import { cn } from "@/lib/cn";

type CodeDisplayProps = {
  code: string;
  label?: string;
  onCopySuccess?: () => void;
  onCopyError?: (err: Error) => void;
  className?: string;
};

export function CodeDisplay({
  code,
  label,
  onCopySuccess,
  onCopyError,
  className,
}: CodeDisplayProps) {
  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(code);
      onCopySuccess?.();
    } catch (err) {
      onCopyError?.(err instanceof Error ? err : new Error(String(err)));
    }
  };

  return (
    <div className={cn("flex flex-col items-center gap-2", className)}>
      {label && (
        <span className="text-label-sm uppercase tracking-widest text-on-surface-variant">
          {label}
        </span>
      )}
      <div className="flex items-center gap-4">
        <span className="font-mono text-5xl font-bold tracking-widest text-primary">
          {code}
        </span>
        <IconButton
          icon={Icons.Copy}
          onClick={handleCopy}
          aria-label="Copy code to clipboard"
        />
      </div>
    </div>
  );
}
```

- Pure presentational. Parent owns the toast dispatch via `onCopySuccess`.
- `navigator.clipboard.writeText` is the modern clipboard API. Some browser configurations (iframes, HTTP contexts) reject it — the `onCopyError` path handles that gracefully.
- Icon comes from the existing `@/components/ui/icons` module (lucide-react wrapper).

### 8.4 `Toast.tsx` + `ToastProvider.tsx` + `useToast.ts`

Three files because the toast system has infrastructure beyond a single component:

**`ToastProvider.tsx` — context provider + portal container**

```typescript
"use client";

import { createPortal } from "react-dom";
import { createContext, useCallback, useEffect, useState } from "react";
import { cn } from "@/lib/cn";
import { Icons } from "@/components/ui/icons";

type ToastKind = "success" | "error";
type ToastItem = { id: string; kind: ToastKind; message: string };

type ToastContextValue = {
  toasts: ToastItem[];
  push: (kind: ToastKind, message: string) => void;
  dismiss: (id: string) => void;
};

export const ToastContext = createContext<ToastContextValue | null>(null);

const MAX_VISIBLE = 3;
const AUTO_DISMISS_MS = 3000;

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true); // eslint-disable-line react-hooks/set-state-in-effect
  }, []);

  const dismiss = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const push = useCallback((kind: ToastKind, message: string) => {
    const id = crypto.randomUUID();
    setToasts((prev) => {
      const next = [...prev, { id, kind, message }];
      return next.length > MAX_VISIBLE ? next.slice(-MAX_VISIBLE) : next;
    });
    setTimeout(() => dismiss(id), AUTO_DISMISS_MS);
  }, [dismiss]);

  return (
    <ToastContext.Provider value={{ toasts, push, dismiss }}>
      {children}
      {mounted &&
        createPortal(
          <div className="fixed top-4 right-4 flex flex-col gap-2 z-50 pointer-events-none">
            {toasts.map((toast) => (
              <div
                key={toast.id}
                role={toast.kind === "error" ? "alert" : "status"}
                className={cn(
                  "pointer-events-auto flex items-center gap-3 px-4 py-3 rounded-lg shadow-lg",
                  "animate-slide-in-from-top",
                  toast.kind === "success"
                    ? "bg-primary text-on-primary"
                    : "bg-error text-on-error"
                )}
              >
                <span className="material-symbols-outlined">
                  {toast.kind === "success" ? Icons.CheckCircle : Icons.AlertCircle}
                </span>
                <span className="text-body-md font-medium">{toast.message}</span>
              </div>
            ))}
          </div>,
          document.body
        )}
    </ToastContext.Provider>
  );
}
```

**`useToast.ts` — consumer hook**

```typescript
"use client";

import { useContext } from "react";
import { ToastContext } from "./ToastProvider";

export function useToast() {
  const ctx = useContext(ToastContext);
  if (!ctx) {
    throw new Error("useToast must be used inside a ToastProvider");
  }
  return {
    success: (message: string) => ctx.push("success", message),
    error: (message: string) => ctx.push("error", message),
  };
}
```

**Wiring into `app/providers.tsx`:**

The existing `providers.tsx` wraps children with `QueryClientProvider` and `ThemeProvider`. Add `ToastProvider` as the innermost wrapper so it has access to both the query client and the theme:

```typescript
<QueryClientProvider>
  <ThemeProvider>
    <ToastProvider>
      {children}
    </ToastProvider>
  </ThemeProvider>
</QueryClientProvider>
```

**Hydration guard (`mounted`).** Next.js server-renders components with `createPortal(..., document.body)` — but `document` doesn't exist during SSR. The `mounted` state flips to `true` only after the component mounts on the client, preventing the portal from being constructed during SSR. The `eslint-disable-line react-hooks/set-state-in-effect` comment is a sanctioned deviation (same pattern as `ThemeToggle.tsx` from Epic 01).

**Animation via Tailwind `animate-slide-in-from-top`.** This class doesn't exist in stock Tailwind. Sub-spec 2b adds a minimal keyframe to `globals.css` (or the tailwind config `extend.animation` block — pick whichever the codebase currently uses for animations).

**Auto-dismiss tracking.** `setTimeout` fires after 3 seconds; no cleanup needed on the timeout handle itself because `dismiss(id)` is a no-op if the toast was already dismissed manually. The `setTimeout` is leaked on component unmount, but since `ToastProvider` wraps the entire app and never unmounts, this is fine.

**Maximum visible.** `MAX_VISIBLE = 3` means a 4th toast pushes the oldest out via `slice(-MAX_VISIBLE)`. No queue — dropped toasts are gone.

### 8.5 `EmptyState.tsx`

```typescript
import type { LucideIcon } from "lucide-react";
import { cn } from "@/lib/cn";

type EmptyStateProps = {
  icon: LucideIcon;
  headline: string;
  description?: string;
  className?: string;
};

export function EmptyState({
  icon: Icon,
  headline,
  description,
  className,
}: EmptyStateProps) {
  return (
    <div
      className={cn(
        "flex flex-col items-center justify-center gap-3 py-16 text-center",
        className
      )}
    >
      <Icon className="size-12 text-on-surface-variant" strokeWidth={1.5} />
      <h3 className="text-title-md font-display font-bold text-on-surface">
        {headline}
      </h3>
      {description && (
        <p className="text-body-md text-on-surface-variant max-w-md">
          {description}
        </p>
      )}
    </div>
  );
}
```

~25 lines. Pure presentational. Used by bids/listings empty tabs + public profile's "No reviews yet" / "No active listings" sections.

### 8.6 `LoadingSpinner.tsx` (if not already present)

```typescript
import { cn } from "@/lib/cn";

type LoadingSpinnerProps = {
  label?: string;
  className?: string;
};

export function LoadingSpinner({ label, className }: LoadingSpinnerProps) {
  return (
    <div
      role="status"
      aria-live="polite"
      className={cn("flex flex-col items-center justify-center gap-3 py-8", className)}
    >
      <div className="size-8 animate-spin rounded-full border-2 border-primary border-t-transparent" />
      {label && (
        <span className="text-body-sm text-on-surface-variant">{label}</span>
      )}
    </div>
  );
}
```

~20 lines. Check `components/ui/` first — if a spinner primitive already exists, skip this and use the existing one.

---

## 9. Domain composite design

### 9.1 `VerificationCodeDisplay.tsx`

```typescript
"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { CodeDisplay } from "@/components/ui/CodeDisplay";
import { CountdownTimer } from "@/components/ui/CountdownTimer";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { useToast } from "@/components/ui/useToast";
import {
  useActiveVerificationCode,
  useGenerateVerificationCode,
} from "@/lib/user/hooks";

export function VerificationCodeDisplay() {
  const { data: activeCode, isPending } = useActiveVerificationCode();
  const generateMutation = useGenerateVerificationCode();
  const toast = useToast();
  const [showRegenerateConfirm, setShowRegenerateConfirm] = useState(false);

  if (isPending) return <LoadingSpinner label="Checking for active code..." />;

  if (!activeCode) {
    return (
      <div className="flex flex-col items-center gap-6 py-8">
        <p className="text-body-lg text-on-surface-variant text-center max-w-md">
          Click the button below to generate a 6-digit verification code.
          You&apos;ll have 15 minutes to enter it at any SLPA Verification
          Terminal in Second Life.
        </p>
        <Button
          onClick={() => generateMutation.mutate()}
          disabled={generateMutation.isPending}
          size="lg"
        >
          {generateMutation.isPending ? "Generating..." : "Generate Verification Code"}
        </Button>
      </div>
    );
  }

  const expiresAt = new Date(activeCode.expiresAt);

  return (
    <div className="flex flex-col items-center gap-6 py-8">
      <CodeDisplay
        code={activeCode.code}
        label="Enter this code at any SLPA Verification Terminal"
        onCopySuccess={() => toast.success("Code copied to clipboard")}
        onCopyError={() => toast.error("Failed to copy — copy the code manually")}
      />
      <div className="flex items-center gap-2 text-on-surface-variant">
        <span className="text-body-sm">Expires in</span>
        <CountdownTimer
          expiresAt={expiresAt}
          onExpire={() => {
            // On expiry, the query will refetch and find no active code,
            // transitioning back to the "Generate" button state.
          }}
        />
      </div>
      {!showRegenerateConfirm ? (
        <Button
          variant="secondary"
          onClick={() => setShowRegenerateConfirm(true)}
          disabled={generateMutation.isPending}
        >
          Regenerate Code
        </Button>
      ) : (
        <div className="flex flex-col items-center gap-3">
          <p className="text-body-sm text-on-surface-variant">
            This will invalidate the current code. Are you sure?
          </p>
          <div className="flex gap-2">
            <Button variant="ghost" onClick={() => setShowRegenerateConfirm(false)}>
              Cancel
            </Button>
            <Button
              variant="secondary"
              onClick={() => {
                setShowRegenerateConfirm(false);
                generateMutation.mutate();
              }}
            >
              Yes, regenerate
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
```

~85 lines. Owns the confirm dialog state locally (no need for a separate `Dialog` primitive — Phase 1 can inline a simple confirm pattern).

### 9.2 `UnverifiedVerifyFlow.tsx`

```typescript
"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/Button";
import { VerificationCodeDisplay } from "@/components/user/VerificationCodeDisplay";
import { useCurrentUser } from "@/lib/user/hooks";

export function UnverifiedVerifyFlow() {
  const router = useRouter();
  const { data: user, refetch } = useCurrentUser({ refetchInterval: 5000 });

  // Watch for mid-session verification (either via Dev simulate or real SL).
  // When verified: true lands in the cache, transition to the verified dashboard.
  useEffect(() => {
    if (user?.verified) {
      router.replace("/dashboard/overview");
    }
  }, [user?.verified, router]);

  return (
    <div className="flex flex-col items-center gap-8">
      <div className="text-center max-w-2xl">
        <p className="text-body-lg text-on-surface-variant">
          To bid, list parcels for sale, or participate in auctions, you need to
          link your Second Life avatar to your SLPA account. This is a one-time
          verification that proves you control the avatar you claim to own.
        </p>
      </div>

      <div className="bg-surface-container rounded-xl p-8 w-full max-w-2xl">
        <VerificationCodeDisplay />
      </div>

      <div className="text-center max-w-2xl space-y-2">
        <p className="text-title-sm font-bold">How to verify:</p>
        <ol className="list-decimal list-inside text-body-md text-on-surface-variant space-y-1">
          <li>Click &quot;Generate Verification Code&quot; above</li>
          <li>Copy the 6-digit code</li>
          <li>Go to any SLPA Verification Terminal in Second Life</li>
          <li>Touch the terminal and enter your code</li>
          <li>This page will automatically detect when you&apos;re verified</li>
        </ol>
      </div>

      <Button
        variant="ghost"
        onClick={() => refetch()}
        className="text-on-surface-variant"
      >
        I&apos;ve entered the code in-world — refresh my status
      </Button>
    </div>
  );
}
```

~60 lines. The `useCurrentUser({ refetchInterval: 5000 })` triggers visibility-aware polling. The `useEffect` watches `user?.verified` for the transition trigger. The manual refresh button calls `refetch()` for instant feedback during dev testing with the `Dev/Simulate SL verify` Postman helper.

### 9.3 `VerifiedIdentityCard.tsx`

```typescript
import { Card } from "@/components/ui/Card";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { Icons } from "@/components/ui/icons";
import type { CurrentUser, PublicUserProfile } from "@/lib/user/api";

type VerifiedIdentityCardProps = {
  user: Pick<
    CurrentUser,
    "slAvatarName" | "slUsername" | "slDisplayName" | "slBornDate" | "slPayinfo" | "verifiedAt"
  > | Pick<
    PublicUserProfile,
    "slAvatarName" | "slUsername" | "slDisplayName"
  >;
  variant?: "dashboard" | "public";
};

const PAY_INFO_LABELS: Record<number, string> = {
  0: "No payment info",
  1: "Payment info on file",
  2: "Payment info used",
  3: "Verified payment",
};

function calculateAccountAge(bornDate: string): string {
  const born = new Date(bornDate);
  const now = new Date();
  const years = now.getFullYear() - born.getFullYear();
  const months = now.getMonth() - born.getMonth();
  const totalMonths = years * 12 + months;
  if (totalMonths < 12) return `${totalMonths} month${totalMonths === 1 ? "" : "s"}`;
  const yearStr = Math.floor(totalMonths / 12);
  return `${yearStr} year${yearStr === 1 ? "" : "s"}`;
}

export function VerifiedIdentityCard({
  user,
  variant = "dashboard",
}: VerifiedIdentityCardProps) {
  return (
    <Card className="p-6">
      <div className="flex items-center gap-3 mb-4">
        <span
          className="material-symbols-outlined text-primary"
          aria-label="Verified"
        >
          verified
        </span>
        <span className="text-label-sm font-bold uppercase tracking-widest text-primary">
          Verified Second Life Identity
        </span>
      </div>

      <h3 className="text-headline-sm font-display font-bold mb-2">
        {user.slAvatarName ?? "—"}
      </h3>
      {user.slUsername && user.slUsername !== user.slAvatarName && (
        <p className="text-body-sm font-mono text-on-surface-variant mb-1">
          {user.slUsername}
        </p>
      )}
      {user.slDisplayName && user.slDisplayName !== user.slAvatarName && (
        <p className="text-body-sm text-on-surface-variant mb-4">
          Display: {user.slDisplayName}
        </p>
      )}

      {variant === "dashboard" && "slBornDate" in user && user.slBornDate && (
        <div className="space-y-2 text-body-sm text-on-surface-variant">
          <p>SL member for {calculateAccountAge(user.slBornDate)}</p>
          {user.slPayinfo != null && (
            <StatusBadge>{PAY_INFO_LABELS[user.slPayinfo] ?? "Unknown"}</StatusBadge>
          )}
          {user.verifiedAt && (
            <p className="text-xs">
              Verified on {new Date(user.verifiedAt).toLocaleDateString()}
            </p>
          )}
        </div>
      )}
    </Card>
  );
}
```

~70 lines. The `variant` prop controls whether verification date + pay info + account age are shown. Public variant omits them because they're less relevant to viewers and `PublicUserProfile` doesn't include `slBornDate`/`slPayinfo`/`verifiedAt` anyway.

Uses the existing `Card` and `StatusBadge` primitives from Epic 01.

### 9.4 `ProfilePictureUploader.tsx`

~150 lines with the drag-drop state machine. Key highlights:

```typescript
"use client";

import { useRef, useState } from "react";
import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { cn } from "@/lib/cn";
import { useUploadAvatar } from "@/lib/user/hooks";
import type { CurrentUser } from "@/lib/user/api";

const MAX_BYTES = 2 * 1024 * 1024;
const ALLOWED_TYPES = new Set(["image/jpeg", "image/png", "image/webp"]);

type UploaderState =
  | { status: "idle" }
  | { status: "file-selected"; file: File; previewUrl: string }
  | { status: "uploading"; file: File; previewUrl: string }
  | { status: "error"; message: string };

type Props = {
  user: Pick<CurrentUser, "id" | "profilePicUrl" | "updatedAt">;
};

export function ProfilePictureUploader({ user }: Props) {
  const [state, setState] = useState<UploaderState>({ status: "idle" });
  const [isDragOver, setIsDragOver] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const uploadMutation = useUploadAvatar();

  const validateAndSelect = (file: File) => {
    if (!ALLOWED_TYPES.has(file.type)) {
      setState({
        status: "error",
        message: "File must be a JPEG, PNG, or WebP image.",
      });
      return;
    }
    if (file.size > MAX_BYTES) {
      setState({
        status: "error",
        message: "File must be 2MB or less.",
      });
      return;
    }
    const previewUrl = URL.createObjectURL(file);
    setState({ status: "file-selected", file, previewUrl });
  };

  const handleSave = () => {
    if (state.status !== "file-selected") return;
    setState({ ...state, status: "uploading" });
    uploadMutation.mutate(state.file, {
      onSuccess: () => {
        URL.revokeObjectURL(state.previewUrl);
        setState({ status: "idle" });
      },
      onError: (error) => {
        setState({
          status: "error",
          message: error instanceof Error ? error.message : "Upload failed",
        });
      },
    });
  };

  const handleCancel = () => {
    if (state.status === "file-selected" || state.status === "uploading") {
      URL.revokeObjectURL(state.previewUrl);
    }
    setState({ status: "idle" });
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragOver(false);
    const file = e.dataTransfer.files[0];
    if (file) validateAndSelect(file);
  };

  return (
    <div className="flex flex-col items-center gap-4">
      {/* Current avatar display */}
      <Avatar
        src={user.profilePicUrl ?? undefined}
        alt="Your profile picture"
        size="xl"
        cacheBust={user.updatedAt}
      />

      {/* Idle: drop zone + click to select */}
      {state.status === "idle" && (
        <div
          onDragOver={(e) => {
            e.preventDefault();
            setIsDragOver(true);
          }}
          onDragLeave={() => setIsDragOver(false)}
          onDrop={handleDrop}
          onClick={() => fileInputRef.current?.click()}
          className={cn(
            "border-2 border-dashed rounded-xl p-8 w-full max-w-sm cursor-pointer transition-colors",
            isDragOver
              ? "border-primary bg-primary/5"
              : "border-outline-variant/40 hover:border-outline-variant"
          )}
        >
          <p className="text-center text-body-md text-on-surface-variant">
            Drop an image here or click to select
          </p>
          <p className="text-center text-body-sm text-on-surface-variant/60 mt-2">
            JPEG, PNG, or WebP · max 2MB
          </p>
          <input
            ref={fileInputRef}
            type="file"
            accept="image/jpeg,image/png,image/webp"
            className="hidden"
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) validateAndSelect(file);
            }}
          />
        </div>
      )}

      {/* File selected: preview + save/cancel */}
      {(state.status === "file-selected" || state.status === "uploading") && (
        <div className="flex flex-col items-center gap-3">
          <img
            src={state.previewUrl}
            alt="Avatar preview"
            className="size-64 rounded-full object-cover"
          />
          <div className="flex gap-2">
            <Button
              variant="ghost"
              onClick={handleCancel}
              disabled={state.status === "uploading"}
            >
              Cancel
            </Button>
            <Button
              onClick={handleSave}
              disabled={state.status === "uploading"}
            >
              {state.status === "uploading" ? "Uploading..." : "Save"}
            </Button>
          </div>
        </div>
      )}

      {/* Error state */}
      {state.status === "error" && (
        <div className="flex flex-col items-center gap-3">
          <p className="text-body-sm text-error">{state.message}</p>
          <Button variant="ghost" onClick={() => setState({ status: "idle" })}>
            Try again
          </Button>
        </div>
      )}
    </div>
  );
}
```

**Key design decisions:**
- State machine with four discriminated states — no boolean flags for `isUploading`, `hasError`, etc.
- `URL.createObjectURL` creates a blob URL for the preview; `URL.revokeObjectURL` cleans up in the idle-return paths to prevent memory leaks.
- Drag-drop handlers call `preventDefault()` on dragover to enable the drop (default browser behavior is to reject).
- Click handler on the whole drop zone opens the hidden file input — one-click select without needing a separate button.
- Client-side validation matches the backend's rules exactly (2MB, JPEG/PNG/WebP). Belt-and-suspenders — the backend enforces, the frontend pre-rejects to save bandwidth and give immediate feedback.
- The existing `<Avatar>` primitive is extended with an optional `cacheBust?: string | number` prop. When present, the component appends `?v=${encodeURIComponent(cacheBust)}` to the `src` URL internally — centralizing the cache-busting logic so every consumer doesn't have to re-invent it. The change is ~4 lines in `Avatar.tsx` and the existing tests still pass (`cacheBust` is optional). A new test case verifies the prop round-trips to the `<img src>`.

### 9.5 `ProfileEditForm.tsx`

```typescript
"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { Button } from "@/components/ui/Button";
import { FormError } from "@/components/ui/FormError";
import { Input } from "@/components/ui/Input";
import { useUpdateProfile } from "@/lib/user/hooks";
import type { CurrentUser } from "@/lib/user/api";

const schema = z.object({
  displayName: z
    .string()
    .min(1, "Display name is required")
    .max(50, "Display name must be 50 characters or less")
    .regex(/^\S+(?:\s+\S+)*$/, "Display name cannot be only whitespace"),
  bio: z.string().max(500, "Bio must be 500 characters or less").optional(),
});

type FormValues = z.infer<typeof schema>;

type Props = {
  user: Pick<CurrentUser, "email" | "displayName" | "bio">;
};

export function ProfileEditForm({ user }: Props) {
  const {
    register,
    handleSubmit,
    formState: { errors, isDirty, isSubmitting },
    reset,
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      displayName: user.displayName ?? "",
      bio: user.bio ?? "",
    },
  });

  const updateMutation = useUpdateProfile();

  const onSubmit = handleSubmit((values) => {
    updateMutation.mutate(values, {
      onSuccess: (updated) => {
        reset({
          displayName: updated.displayName ?? "",
          bio: updated.bio ?? "",
        });
      },
    });
  });

  return (
    <form onSubmit={onSubmit} className="space-y-4">
      <div>
        <label className="text-label-sm font-bold text-on-surface-variant">
          Email
        </label>
        <p className="text-body-md font-mono">{user.email}</p>
      </div>

      <div>
        <label htmlFor="displayName" className="text-label-sm font-bold text-on-surface-variant">
          Display Name
        </label>
        <Input
          id="displayName"
          {...register("displayName")}
          aria-invalid={!!errors.displayName}
        />
        {errors.displayName && <FormError>{errors.displayName.message}</FormError>}
      </div>

      <div>
        <label htmlFor="bio" className="text-label-sm font-bold text-on-surface-variant">
          Bio
        </label>
        <textarea
          id="bio"
          {...register("bio")}
          rows={4}
          className="w-full rounded-lg bg-surface-container-high p-3 text-body-md focus:outline-none focus:ring-2 focus:ring-primary/40"
        />
        {errors.bio && <FormError>{errors.bio.message}</FormError>}
      </div>

      {updateMutation.isError && !errors.displayName && !errors.bio && (
        <FormError>Failed to save — please try again.</FormError>
      )}

      <Button type="submit" disabled={!isDirty || isSubmitting}>
        {isSubmitting ? "Saving..." : "Save changes"}
      </Button>
    </form>
  );
}
```

~80 lines. RHF + Zod pattern matches the existing `LoginForm` / `RegisterForm` from Epic 01. Email is read-only (no email-change flow in this sub-spec). The Zod schema matches the backend's `UpdateUserRequest` validation exactly: min 1 / max 50 on displayName, max 500 on bio, regex rejects whitespace-only.

### 9.6 `VerifiedOverview.tsx`

```typescript
"use client";

import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { ProfileEditForm } from "@/components/user/ProfileEditForm";
import { ProfilePictureUploader } from "@/components/user/ProfilePictureUploader";
import { VerifiedIdentityCard } from "@/components/user/VerifiedIdentityCard";
import { useCurrentUser } from "@/lib/user/hooks";

export function VerifiedOverview() {
  const { data: user, isPending } = useCurrentUser();

  if (isPending || !user) return <LoadingSpinner label="Loading profile..." />;

  return (
    <div className="grid gap-8 md:grid-cols-2">
      <div className="flex flex-col gap-6">
        <VerifiedIdentityCard user={user} variant="dashboard" />
        <ProfilePictureUploader user={user} />
      </div>
      <div>
        <ProfileEditForm user={user} />
      </div>
    </div>
  );
}
```

~30 lines. The `useCurrentUser` call here shares cache with the layout's call, so this is a cache hit, not a second fetch. Passes down a shared `user` prop to all three children so they don't each call `useCurrentUser` independently.

### 9.7 `PublicProfileView.tsx`

```typescript
"use client";

import { notFound } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { Avatar } from "@/components/ui/Avatar";
import { Card } from "@/components/ui/Card";
import { EmptyState } from "@/components/ui/EmptyState";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { Icons } from "@/components/ui/icons";
import { NewSellerBadge } from "@/components/user/NewSellerBadge";
import { ReputationStars } from "@/components/user/ReputationStars";
import { VerifiedIdentityCard } from "@/components/user/VerifiedIdentityCard";
import { userApi } from "@/lib/user/api";
import { ApiError } from "@/lib/api";

type Props = { userId: number };

export function PublicProfileView({ userId }: Props) {
  const {
    data: profile,
    isPending,
    error,
  } = useQuery({
    queryKey: ["publicProfile", userId],
    queryFn: () => userApi.publicProfile(userId),
    retry: (failureCount, err) => {
      if (err instanceof ApiError && err.status === 404) return false;
      return failureCount < 2;
    },
  });

  if (isPending) return <LoadingSpinner label="Loading profile..." />;
  if (error instanceof ApiError && error.status === 404) {
    notFound();
  }
  if (error || !profile) {
    return (
      <EmptyState
        icon={Icons.AlertCircle}
        headline="Could not load profile"
        description="Please try refreshing the page."
      />
    );
  }

  const completionRate =
    profile.completedSales + profile.totalSellerReviews > 0
      ? (profile.completedSales /
          (profile.completedSales + profile.totalSellerReviews)) *
        100
      : null;

  return (
    <div className="mx-auto max-w-4xl px-4 py-8 space-y-8">
      {/* Header: avatar + name + verified badge + SL identity */}
      <div className="flex items-start gap-6">
        <Avatar
          src={profile.profilePicUrl ?? undefined}
          alt={profile.displayName ?? "User"}
          name={profile.displayName ?? undefined}
          size="xl"
        />
        <div className="flex-1 space-y-2">
          <div className="flex items-center gap-3">
            <h1 className="text-headline-md font-display font-bold">
              {profile.displayName ?? "Anonymous"}
            </h1>
            {profile.verified ? (
              <StatusBadge variant="success">
                <span className="material-symbols-outlined text-sm">verified</span>
                Verified
              </StatusBadge>
            ) : (
              <StatusBadge variant="default">Unverified</StatusBadge>
            )}
          </div>
          {profile.verified && profile.slAvatarName && (
            <p className="text-body-md text-on-surface-variant font-mono">
              {profile.slAvatarName}
            </p>
          )}
          <p className="text-body-sm text-on-surface-variant">
            Member since {new Date(profile.createdAt).toLocaleDateString()}
          </p>
          {profile.bio && (
            <p className="text-body-md text-on-surface mt-4">{profile.bio}</p>
          )}
        </div>
      </div>

      {/* Reputation section */}
      <Card className="p-6">
        <h2 className="text-title-md font-bold mb-4">Reputation</h2>
        <div className="grid gap-4 md:grid-cols-2">
          <ReputationStars
            rating={profile.avgSellerRating}
            reviewCount={profile.totalSellerReviews}
            label="Seller Rating"
          />
          <ReputationStars
            rating={profile.avgBuyerRating}
            reviewCount={profile.totalBuyerReviews}
            label="Buyer Rating"
          />
        </div>
        <div className="flex items-center gap-3 mt-6">
          <NewSellerBadge completedSales={profile.completedSales} />
          <span className="text-body-sm text-on-surface-variant">
            {profile.completedSales} completed sales
            {completionRate !== null && ` · ${Math.round(completionRate)}% completion rate`}
          </span>
        </div>
      </Card>

      {/* Placeholder: Recent reviews */}
      <Card className="p-6">
        <h2 className="text-title-md font-bold mb-4">Recent Reviews</h2>
        <EmptyState
          icon={Icons.MessageSquare}
          headline="No reviews yet"
          description="Reviews will appear here after this user completes their first auction."
        />
      </Card>

      {/* Placeholder: Active listings */}
      <Card className="p-6">
        <h2 className="text-title-md font-bold mb-4">Active Listings</h2>
        <EmptyState
          icon={Icons.Gavel}
          headline="No active listings"
          description="Listings will appear here when this user puts a parcel up for auction."
        />
      </Card>
    </div>
  );
}
```

~130 lines. Handles: loading, 404 → `notFound()`, error → inline message, happy-path render with header + reputation card + two empty-state placeholder sections.

### 9.8 `ReputationStars.tsx`

```typescript
import { Icons } from "@/components/ui/icons";

type ReputationStarsProps = {
  rating: number | null;
  reviewCount: number;
  label?: string;
};

export function ReputationStars({ rating, reviewCount, label }: ReputationStarsProps) {
  return (
    <div className="flex flex-col gap-1">
      {label && (
        <span className="text-label-sm font-bold uppercase tracking-widest text-on-surface-variant">
          {label}
        </span>
      )}
      {rating === null ? (
        <p className="text-body-md text-on-surface-variant">No ratings yet</p>
      ) : (
        <div className="flex items-center gap-2">
          <Icons.Star
            className="size-5 fill-primary text-primary"
            strokeWidth={1.5}
          />
          <span className="text-title-md font-bold">{rating.toFixed(1)}</span>
          <span className="text-body-sm text-on-surface-variant">
            ({reviewCount} review{reviewCount === 1 ? "" : "s"})
          </span>
        </div>
      )}
    </div>
  );
}
```

~30 lines. Single-star rendering with numeric value. Partial-star rendering deferred to a later epic.

### 9.9 `NewSellerBadge.tsx`

```typescript
import { StatusBadge } from "@/components/ui/StatusBadge";

type NewSellerBadgeProps = { completedSales: number };

export function NewSellerBadge({ completedSales }: NewSellerBadgeProps) {
  if (completedSales >= 3) return null;
  return (
    <StatusBadge variant="warning">
      New Seller
    </StatusBadge>
  );
}
```

~15 lines. Uses the existing `StatusBadge` primitive with a `warning` variant (amber chip).

---

## 10. Backend touch-up — `updatedAt` on `UserResponse`

**What:** Add `OffsetDateTime updatedAt` field to the backend `UserResponse` record + wire from `user.getUpdatedAt()`.

**Why:** The frontend's `ProfilePictureUploader` displays the current avatar via `<Avatar src={`${profilePicUrl}?v=${updatedAt}`} />`. The `?v=...` query parameter is a cache-buster — when a user re-uploads their avatar, the backend bumps `user.updatedAt` via `@UpdateTimestamp` (already present on the `User` entity), the `/me` refetch returns the new `updatedAt`, and the frontend's `<img src>` changes, forcing a re-fetch of the avatar bytes despite `Cache-Control: immutable`. Without this field projection, avatar re-uploads would not visually update until the browser cache expires (24 hours max-age).

**The change:** ~5 lines in `backend/src/main/java/com/slparcelauctions/backend/user/dto/UserResponse.java`.

```java
// Add to the record components list:
OffsetDateTime createdAt,
OffsetDateTime updatedAt  // NEW

// Add to the from(User) construction:
user.getCreatedAt(),
user.getUpdatedAt()       // NEW
```

**Test impact:** Any existing test that constructs `UserResponse` positionally needs a new `null` slot for `updatedAt`. Based on Task 5 of sub-spec 2a, this was ~3 sites across `UserControllerTest`, `AuthControllerTest`, and maybe `UserServiceTest`. Grep for `new UserResponse(` in `backend/src/test` and update each.

**Why this lands in sub-spec 2b, not sub-spec 2a.** Sub-spec 2a was backend-only and intentionally scoped to the avatar upload + serve endpoints. The `updatedAt` field isn't needed by the backend contract itself — only by the frontend's cache-busting strategy. Landing it in sub-spec 2b keeps the semantic connection clear: "this backend change exists because the frontend needs it."

---

## 11. Post-verification transition trace

End-to-end flow for an unverified user from arrival to transition:

1. **User arrives at `/dashboard`.** `dashboard/layout.tsx` wraps in `RequireAuth` — if unauthenticated, redirect to `/login`. If authenticated, `dashboard/page.tsx` mounts.
2. **`dashboard/page.tsx` reads verification status.** Calls `useCurrentUser()` which hits the cached `["currentUser"]` query (or fetches if cold). On resolution with `verified: false`, `router.replace("/dashboard/verify")`.
3. **User lands at `/dashboard/verify`.** The verify page mounts, which renders `<UnverifiedVerifyFlow />`. That component calls `useCurrentUser({ refetchInterval: 5000 })` — polling starts, 5-second interval, visibility-aware.
4. **User clicks "Generate Verification Code".** `useGenerateVerificationCode` mutation fires `POST /api/v1/verification/generate`. Response returns a 6-digit code + `expiresAt`. TanStack Query invalidates `["verification", "active"]`, which re-fetches and hydrates `<CodeDisplay>` with the new code. `<CountdownTimer>` starts counting down from 15:00.
5. **User clicks the copy icon.** `navigator.clipboard.writeText(code)` fires, success callback dispatches `toast.success("Code copied to clipboard")`, toast slides in from the top-right and auto-dismisses after 3 seconds.
6. **User (for dev testing) opens Postman.** Runs `Dev/Simulate SL verify` with the `verificationCode` env var (pre-populated by the earlier `Generate code` request's capture script). Backend marks the user as verified, writes SL fields, returns success.
7. **Within 5 seconds, `useCurrentUser` polling fires.** `GET /api/v1/users/me` returns the user with `verified: true`, the SL fields populated, and a fresh `verifiedAt` timestamp. TanStack Query updates the `["currentUser"]` cache.
8. **The `useEffect` in `UnverifiedVerifyFlow` watching `user?.verified` fires.** Calls `router.replace("/dashboard/overview")`.
9. **User lands on `/dashboard/overview`.** `dashboard/(verified)/layout.tsx` mounts, calls `useCurrentUser()` (cache hit — same data from step 7), confirms `verified: true`, renders the tab rail + children. `overview/page.tsx` renders `<VerifiedOverview />`, which calls `useCurrentUser()` (cache hit again), passes `user` to children: `<VerifiedIdentityCard />` displays the SL identity, `<ProfilePictureUploader />` shows the default avatar with drop zone, `<ProfileEditForm />` shows pre-filled displayName + bio.
10. **Total network requests from step 6 to step 9:** one (`GET /me` in step 7). Everything else is cache hits. No loading flashes during the transition.

**Manual refresh button fast path:** if the user doesn't want to wait 5 seconds, clicking "I've entered the code in-world — refresh my status" calls `refetch()` directly, which fires an immediate `GET /me` and bypasses the polling interval. Same cache-update + redirect flow, just faster.

**Backgrounded tab behavior:** TanStack Query's `refetchIntervalInBackground: false` (the default) pauses polling when `document.visibilityState === "hidden"`. When the user returns to the tab, polling resumes automatically. If the user leaves the tab for 30 minutes and comes back, no stale polls fire in the background — the first poll is triggered by the tab focus event, which refetches once and either transitions or keeps polling.

---

## 12. `app/providers.tsx` wiring

The existing `providers.tsx` currently wraps children with `QueryClientProvider` + `ThemeProvider`. Sub-spec 2b adds `ToastProvider` as the innermost wrapper:

```tsx
"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "next-themes";
import { useState } from "react";
import { ToastProvider } from "@/components/ui/Toast";

export function Providers({ children }: { children: React.ReactNode }) {
  const [queryClient] = useState(() => new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 60_000,
        gcTime: 5 * 60_000,
        retry: false,
      },
    },
  }));

  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider attribute="class" defaultTheme="dark">
        <ToastProvider>
          {children}
        </ToastProvider>
      </ThemeProvider>
    </QueryClientProvider>
  );
}
```

**Why innermost.** `ToastProvider` needs access to both the query client (so mutation hooks can call `toast.success` from their `onSuccess` handlers) and the theme (so toast styling responds to the current theme). Nesting it inside both providers ensures context is available when `useToast()` is called from anywhere in the tree.

**Why state-backed `QueryClient`.** This matches the existing Epic 01 pattern — `new QueryClient()` inside `useState`'s initializer prevents the client from being re-created on every render while still being component-local (not a module singleton).

---

## 13. Testing strategy

Three levels of tests matching the existing Vitest + RTL + MSW pattern.

### 13.1 Per-primitive unit tests

**`Tabs.test.tsx`** (~4 cases)
- Renders all tabs with labels
- Active tab highlighting based on `usePathname`
- Clicking a tab triggers `next/link` navigation (MSW isn't involved; we assert `href` attributes are correct)
- Keyboard accessible (tab key focuses each link)

**`CountdownTimer.test.tsx`** (~6 cases)
- Uses `vi.useFakeTimers()` + `vi.advanceTimersByTime()` for deterministic time control
- Initial render shows correct MM:SS for target Date
- Ticks forward on 1-second intervals
- `onExpire` fires exactly once at zero
- Displays `--:--` after expiry
- Cleanup on unmount — no leaked intervals (assert via `vi.getTimerCount()` or similar)

**`CodeDisplay.test.tsx`** (~4 cases)
- Renders the code + label
- Clicking copy triggers `navigator.clipboard.writeText` (mocked via `Object.defineProperty(navigator, "clipboard", { value: { writeText: vi.fn() } })`)
- `onCopySuccess` fires on successful clipboard write
- `onCopyError` fires when clipboard rejects

**`Toast.test.tsx`** (~6 cases)
- `useToast()` throws outside `ToastProvider`
- `toast.success(msg)` displays a toast with `role="status"`
- `toast.error(msg)` displays a toast with `role="alert"`
- Auto-dismisses after 3 seconds via fake timers
- Max 3 visible — dispatching a 4th removes the oldest
- Multiple toasts stack in order

**`EmptyState.test.tsx`** (~3 cases)
- Renders icon + headline
- Renders description when provided
- Accessible (headline is an `<h3>`)

**`LoadingSpinner.test.tsx`** (if new) (~2 cases)
- Renders spinner + label when provided
- `role="status"` for screen readers

### 13.2 Per-domain-component tests (MSW-backed)

**`VerificationCodeDisplay.test.tsx`** (~6 cases)
- No active code → "Generate" button state
- Click generate → MSW returns code → `<CodeDisplay>` renders
- Copy button dispatches toast (wrapped in `ToastProvider` via `renderWithProviders`)
- Regenerate button shows confirm dialog
- Confirm dialog cancel returns to previous state
- Confirm dialog yes triggers new generate mutation

**`UnverifiedVerifyFlow.test.tsx`** (~4 cases)
- Renders explanatory copy + verification code display
- Manual refresh button calls `refetch` on the useCurrentUser hook
- Post-verification transition: advance timers 5000ms, MSW returns `verified: true`, assert `router.replace("/dashboard/overview")` called
- Error state: MSW returns 500 on the /me poll, asserts the UI doesn't crash and shows an error state

**`VerifiedIdentityCard.test.tsx`** (~5 cases)
- Dashboard variant renders all fields (slAvatarName, slBornDate age, payInfo badge, verifiedAt)
- Public variant omits verifiedAt and pay info
- Account age calculation: `slBornDate = "2011-03-15"` against a fixed `now`, expect "14 years"
- Account age < 12 months shows "N months"
- Missing slBornDate shows nothing for age

**`ProfilePictureUploader.test.tsx`** (~8 cases)
- Idle state renders drop zone + current avatar
- Click drop zone opens file input
- File input change with valid PNG → file-selected state with preview
- Drop event with valid PNG → same
- File input change with BMP → error state with format message
- File input change with 3MB file → error state with size message
- Click Save in file-selected state → uploading → success → back to idle with new avatar
- Click Cancel in file-selected state → back to idle, blob URL revoked

**`ProfileEditForm.test.tsx`** (~6 cases)
- Pre-filled with user's displayName + bio
- Empty displayName → validation error via Zod
- 51-char displayName → validation error
- 501-char bio → validation error
- Whitespace-only displayName → validation error (matches backend `@Pattern`)
- Submit valid → mutation fires, toast dispatched

**`ReputationStars.test.tsx`** (~3 cases)
- `rating: null` → "No ratings yet"
- `rating: 4.7, reviewCount: 12` → "4.7" + "(12 reviews)"
- `reviewCount: 1` → "(1 review)" (singular)

**`NewSellerBadge.test.tsx`** (~2 cases)
- `completedSales: 0` → renders chip
- `completedSales: 3` → renders null

**`VerifiedOverview.test.tsx`** (~2 cases)
- MSW stubs `/me` returning verified user, renders all three children
- Loading state shows spinner

**`PublicProfileView.test.tsx`** (~5 cases)
- Verified user → renders with verified badge + SL identity
- Unverified user → renders with "Unverified" chip, no SL identity
- 404 error → `notFound()` called
- New seller (completedSales < 3) → NewSellerBadge visible
- Established seller (completedSales >= 3) → NewSellerBadge absent

### 13.3 Per-page integration tests

**`dashboard/page.test.tsx`** (~2 cases)
- MSW `/me` returns verified → `router.replace("/dashboard/overview")`
- MSW `/me` returns unverified → `router.replace("/dashboard/verify")`

**`dashboard/(verified)/layout.test.tsx`** (~2 cases)
- Verified user → renders children with tab rail
- Unverified user → `router.replace("/dashboard/verify")` called

**`verify/page.test.tsx`** (~1 case)
- Full-page integration test: renders UnverifiedVerifyFlow, simulates generate → copy → polling transition

### 13.4 Per-hook tests

**`hooks.test.tsx`** (~6 cases)
- `useCurrentUser` fires `GET /me` when session is authenticated
- `useCurrentUser` is disabled when session is not authenticated
- `useCurrentUser({ refetchInterval: 5000 })` fires at 5s intervals (fake timers)
- `useUpdateProfile` happy path invalidates `["currentUser"]`
- `useUploadAvatar` happy path invalidates `["currentUser"]`
- Toast dispatch on mutation success

### 13.5 MSW handler additions

New named handlers in `frontend/src/test/msw/handlers.ts`:

```typescript
export const userHandlers = {
  meUnverified: () => http.get("*/api/v1/users/me", () =>
    HttpResponse.json(mockUnverifiedCurrentUser)),
  meVerified: () => http.get("*/api/v1/users/me", () =>
    HttpResponse.json(mockVerifiedCurrentUser)),
  meError: () => http.get("*/api/v1/users/me", () =>
    HttpResponse.json({ status: 500, title: "Internal Server Error" }, { status: 500 })),

  updateMeSuccess: () => http.put("*/api/v1/users/me", ({ request }) => {
    // Echo back the merged user
  }),
  updateMeValidationError: () => http.put("*/api/v1/users/me", () =>
    HttpResponse.json(mockValidationProblemDetail, { status: 400 })),

  uploadAvatarSuccess: () => http.post("*/api/v1/users/me/avatar", () =>
    HttpResponse.json(mockVerifiedCurrentUser)),
  uploadAvatarOversized: () => http.post("*/api/v1/users/me/avatar", () =>
    HttpResponse.json(mockUploadTooLargeProblemDetail, { status: 413 })),
  uploadAvatarUnsupportedFormat: () => http.post("*/api/v1/users/me/avatar", () =>
    HttpResponse.json(mockUnsupportedFormatProblemDetail, { status: 400 })),

  publicProfileSuccess: (user = mockPublicProfile) =>
    http.get("*/api/v1/users/:id", () => HttpResponse.json(user)),
  publicProfileNotFound: () => http.get("*/api/v1/users/:id", () =>
    HttpResponse.json(mockUserNotFoundProblemDetail, { status: 404 })),
};

export const verificationHandlers = {
  activeNone: () => http.get("*/api/v1/verification/active", () =>
    HttpResponse.json(mockVerificationNotFoundProblemDetail, { status: 404 })),
  activeExists: () => http.get("*/api/v1/verification/active", () =>
    HttpResponse.json({ code: "123456", expiresAt: "2026-04-14T21:00:00Z" })),
  generateSuccess: () => http.post("*/api/v1/verification/generate", () =>
    HttpResponse.json({ code: "654321", expiresAt: "2026-04-14T21:15:00Z" })),
  generateAlreadyVerified: () => http.post("*/api/v1/verification/generate", () =>
    HttpResponse.json(mockAlreadyVerifiedProblemDetail, { status: 409 })),
};
```

Fixtures for `mockVerifiedCurrentUser`, `mockUnverifiedCurrentUser`, etc., live at `frontend/src/test/msw/fixtures.ts` alongside the existing `mockUser` and `mockAuthResponse`.

### 13.6 Test target

- Baseline: 185 frontend tests + 1 todo (post-sub-spec-2a)
- Expected delta: +40 to +55 tests across primitives, domain components, pages, and hooks
- Target: 225-240 frontend tests passing

Backend test count: 190 → 191 (one new test covering the `updatedAt` field in the `UserResponse` shape alignment).

---

## 14. Security considerations

### 14.1 XSS

All user-generated strings (displayName, bio, slAvatarName, etc.) are rendered via React's default JSX interpolation, which escapes HTML entities automatically. No `dangerouslySetInnerHTML` anywhere in sub-spec 2b. User-supplied HTML cannot execute.

### 14.2 Privilege escalation via form field injection

Sub-spec 2a's `UpdateUserRequest` with the global `fail-on-unknown-properties: true` Jackson flag blocks the backend from accepting extra fields. The frontend's `ProfileEditForm` only sends `displayName` and `bio` — nothing else. A malicious user editing the form via DevTools and injecting additional fields (`email`, `role`) would get a 400 from the backend's Jackson layer.

### 14.3 Authenticated routes

All `/dashboard/*` routes are wrapped in `RequireAuth` at the layout level — unauthenticated users get redirected to `/login` before any verification logic fires. The `(verified)/layout.tsx` adds a second check for the verification status. Both checks use TanStack Query cache, not direct token inspection, so the JWT stays securely in-memory in `lib/auth/session.ts`.

### 14.4 Public profile access

`/users/[id]` is a public route by design — anyone can view any user's public profile. This matches the backend's `GET /api/v1/users/:id` endpoint which is also public. Private fields (email, notification preferences, bio visibility settings) are not exposed in `UserProfileResponse` — the backend's serialization layer filters them.

### 14.5 Clipboard permission prompts

Some browsers (notably Safari in certain contexts) prompt for clipboard permission on the first `navigator.clipboard.writeText` call. The `CodeDisplay` component handles rejection via `onCopyError` and displays a toast ("Failed to copy — copy the code manually"). The user can still see the code on-screen and type it manually if clipboard access is blocked.

### 14.6 Avatar upload file sanitization

The frontend does client-side format + size checks before upload, but the backend's `AvatarImageProcessor` is the authoritative validator. A malicious user bypassing the frontend checks (via DevTools, curl, etc.) gets the same 400/413 responses from the backend. The backend uses ImageIO format sniffing, not trusting the client's Content-Type header.

---

## 15. Task breakdown

Eight tasks inside sub-spec 2b, in strict execution order. Each task ends with a commit + push.

### Task 1 — Backend touch-up + `lib/user/` foundation (~1 hour)

1. Add `updatedAt` field to `backend/src/main/java/com/slparcelauctions/backend/user/dto/UserResponse.java` (1 component + 1 `.from()` wiring line)
2. Grep `backend/src/test` for `new UserResponse(` — update each call site to add `null` for the new positional arg
3. `./mvnw test` → green (~191 tests)
4. Extend `frontend/src/lib/api.ts` `request<T>` function to detect `body instanceof FormData` and skip `Content-Type` + `JSON.stringify`
5. Add unit test for the FormData path to `lib/api.test.ts`
6. Create `frontend/src/lib/user/api.ts` with `userApi`, `verificationApi`, `CurrentUser`, `PublicUserProfile`, `UpdateProfileRequest`, `ActiveCodeResponse`, `GenerateCodeResponse`
7. Create `frontend/src/lib/user/hooks.ts` with `useCurrentUser`, `useUpdateProfile`, `useUploadAvatar`, `useActiveVerificationCode`, `useGenerateVerificationCode`
8. Add MSW handlers (`userHandlers`, `verificationHandlers`) + fixtures to `frontend/src/test/msw/handlers.ts` and `fixtures.ts`
9. Create `frontend/src/lib/user/hooks.test.tsx` with ~6 hook tests
10. `npm test -- --run` → green
11. Commit: `feat(user): add updatedAt to UserResponse + frontend user API client and hooks`

### Task 2 — UI primitives (Tabs, CountdownTimer, CodeDisplay, EmptyState, LoadingSpinner) + Avatar cacheBust (~2 hours)

1. `LoadingSpinner.tsx` + test (only if not already present — check first)
2. `Tabs.tsx` + test (4-5 cases)
3. `CountdownTimer.tsx` + test (fake timers, 5-6 cases)
4. `CodeDisplay.tsx` + test (clipboard mock, 4 cases)
5. `EmptyState.tsx` + test (3 cases)
6. Extend existing `components/ui/Avatar.tsx` with an optional `cacheBust?: string | number` prop. When present, the component appends `?v=${encodeURIComponent(cacheBust)}` to the `src` URL internally. Add 1-2 new test cases to `Avatar.test.tsx` confirming the prop round-trips to the rendered `<img src>`. Existing tests stay green because the prop is optional.
7. Export all new primitives from `components/ui/index.ts`
8. `npm test -- --run` → green
9. Commit: `feat(ui): add Tabs, CountdownTimer, CodeDisplay, EmptyState, LoadingSpinner primitives + Avatar cacheBust prop`

### Task 3 — Toast primitive + provider wiring (~1.5 hours)

1. Create `frontend/src/components/ui/Toast/ToastProvider.tsx` with context + portal + state + auto-dismiss
2. Create `frontend/src/components/ui/Toast/useToast.ts` hook
3. Create `frontend/src/components/ui/Toast/index.ts` export
4. Wire `<ToastProvider>` into `frontend/src/app/providers.tsx`
5. Add CSS animation classes for slide-in/out in `globals.css` or the Tailwind config
6. Create test: `Toast.test.tsx` with 6 cases
7. Commit: `feat(ui): add Toast primitive with portal-rendered stack and useToast hook`

### Task 4 — Verification domain components (~2 hours)

1. `VerificationCodeDisplay.tsx` — composes `CodeDisplay` + `CountdownTimer` + generate/regenerate handlers
2. Test covering all states (no code, active code, copy, regenerate, confirm dialog)
3. `UnverifiedVerifyFlow.tsx` — composition with polling + transition effect + manual refresh button
4. Test covering polling transition, manual refresh, error state
5. Commit: `feat(user): add VerificationCodeDisplay and UnverifiedVerifyFlow components`

### Task 5 — Profile domain components (~3 hours)

1. `ProfilePictureUploader.tsx` — drag-drop + click-select + preview state machine
2. Test with fake DataTransfer, file input change events, validation paths
3. `VerifiedIdentityCard.tsx` + test (variant prop, account age, pay info mapping)
4. `ProfileEditForm.tsx` + test (RHF + Zod + mutation + toast)
5. `VerifiedOverview.tsx` + test (composition)
6. Commit: `feat(user): add ProfilePictureUploader, VerifiedIdentityCard, ProfileEditForm, VerifiedOverview`

### Task 6 — Dashboard routes (~2 hours)

1. `dashboard/layout.tsx` — `RequireAuth` wrapper
2. `dashboard/page.tsx` — thin redirect based on verification status
3. `dashboard/verify/page.tsx` — wraps `<UnverifiedVerifyFlow />`
4. `dashboard/(verified)/layout.tsx` — tab rail + gate redirect
5. `dashboard/(verified)/overview/page.tsx` — wraps `<VerifiedOverview />`
6. `dashboard/(verified)/bids/page.tsx` — empty state "No bids yet"
7. `dashboard/(verified)/listings/page.tsx` — empty state "No listings yet"
8. Page tests for the redirect logic (index + gate layout)
9. Verify the existing `dashboard/page.tsx` stub is deleted (36-line stub from Epic 01)
10. Commit: `feat(app): add dashboard routes with verification gate and tabs`

### Task 7 — Public profile page + reputation components (~2 hours)

1. `ReputationStars.tsx` + test
2. `NewSellerBadge.tsx` + test
3. `PublicProfileView.tsx` + test (loading, 404, happy-path, unverified, new-seller)
4. `users/[id]/page.tsx` — server component that validates and delegates
5. Commit: `feat(app): add public profile page at /users/[id]`

### Task 8 — Final polish (~1 hour)

1. Add 2-3 full-flow integration smoke tests in `frontend/src/test/integration/` (or wherever the existing integration tests live)
2. Run verify chain: `npm run lint`, any `frontend/scripts/verify-*.sh` scripts, `npm run build`
3. README sweep: mention dashboard routes, new `components/user/` domain components, Toast primitive, updated test count
4. FOOTGUNS.md: add entries for any new gotchas discovered during implementation (Next.js 16 route groups, TanStack Query polling + visibility, MSW multipart, drag-drop in JSDOM, Toast portal hydration guard, etc.)
5. Open PR into `dev` via `gh pr create --base dev`
6. `git checkout dev` locally
7. Commit: `docs: README sweep and FOOTGUNS entries for Epic 02 sub-spec 2b`

**Total estimate:** ~14-15 hours across 8 tasks.

---

## 16. Done definition

Sub-spec 2b is done when all of the following are true on `task/02-sub-2b-dashboard-public-profile` off `dev`:

- [ ] All 8 tasks committed in order per §15
- [ ] `./mvnw test` → BUILD SUCCESS at ~191 tests (190 baseline + 1 for `UserResponse` shape alignment)
- [ ] `cd frontend && npm test -- --run` → BUILD SUCCESS at ~225-240 tests (185 baseline + 40-55 new)
- [ ] `npm run lint` → clean
- [ ] `npm run build` → production build passes (Next.js 16 compile + typecheck)
- [ ] Frontend verify chain (`frontend/scripts/verify-*.sh`) → all green
- [ ] `docker compose up` stands up all 5 services healthy (postgres, redis, minio, backend, frontend)
- [ ] Manual browser smoke end-to-end:
  1. Register a new user at `/register`
  2. Land on `/dashboard` → redirects to `/dashboard/verify`
  3. Click "Generate Verification Code" → 6-digit code appears with countdown timer
  4. Click the copy icon → toast slides in ("Code copied to clipboard")
  5. Open Postman SLPA collection → SLPA Dev environment → Auth/Login with the user's credentials → Dev/Simulate SL verify with the captured `verificationCode` env var
  6. Wait ≤5 seconds OR click "I've entered the code in-world — refresh my status" button
  7. Dashboard transitions to `/dashboard/overview` automatically
  8. `VerifiedIdentityCard` displays the SL identity from the simulate call
  9. Click the tab rail → navigate to `/dashboard/bids` → empty state "No bids yet"
  10. Navigate to `/dashboard/listings` → empty state "No listings yet"
  11. Return to Overview → upload an avatar via drag-drop → preview shows → click Save → avatar updates
  12. Edit displayName and bio → click Save → toast confirms update → `GET /me` reflects changes
  13. Open a new incognito window → navigate to `/users/{userId}` of the verified user → public profile shows verified identity card + reputation "No ratings yet" placeholders + empty-state sections for reviews and listings
- [ ] `README.md` swept with dashboard routes, new components, Toast primitive mention, test count bump
- [ ] `docs/implementation/FOOTGUNS.md` updated with any new gotchas discovered during implementation
- [ ] PR into `dev` opened (not `main`)
- [ ] No AI/tool attribution anywhere (commits, PR title, PR body)
- [ ] Local branch is `dev` after the PR opens

---

## 17. Deferred questions (for future sub-specs / epics)

Captured for context; not blocking sub-spec 2b.

1. **WebSocket push for verification completion.** Replace the 5-second polling with a STOMP subscription on `/topic/user/{userId}/verification`. Backend publishes when `SlVerificationService.verify` completes. Cleaner architecture, lower backend load, zero polling latency. Natural fit for Epic 11 when real LSL integration lands.
2. **Partial-star visual rendering for `ReputationStars`.** Phase 1 ships with a simpler numeric display. When review data actually exists (Epic 06), upgrade to SVG partial-star rendering with filled/empty wedges.
3. **Profile edit email change flow.** Currently read-only. Requires a re-verification flow (new email → confirmation link → swap). Epic 07 user settings expansion.
4. **Account deletion UI.** `DELETE /me` stays 501. When the backend GDPR sub-spec lands (future Epic 02 or Epic 07 task), the dashboard gains a delete-account button.
5. **Notification preferences editor.** `CurrentUser.notifyEmail` and `notifySlIm` are returned in the `/me` response but the dashboard doesn't expose an editor. Epic 07 settings expansion.
6. **Real data for My Bids and My Listings tabs.** Epic 04 (auctions) populates these. Sub-spec 2b ships the tab skeletons with empty-state placeholders so the routes exist before the data does.
7. **Realty group badge on public profile.** Task 02-05 task doc mentions "Realty group badge (if applicable, Phase 2 - just leave space)." Sub-spec 2b doesn't include it — Phase 2 feature.
8. **Follow/unfollow user from public profile.** Social features are out of scope for Phase 1.
9. **Profile page SEO metadata.** Next.js 16 `generateMetadata` for the public profile page could emit OpenGraph tags for social sharing. Out of scope for Phase 1; nice-to-have for Epic 07.
10. **Custom drag-drop animations.** The `ProfilePictureUploader` drop zone uses a static border highlight. A polished version would animate the border-color transition and show a scale effect on drop. Cosmetic polish deferred.

---

## 18. Decisions log (from brainstorm)

Locked via Q&A on 2026-04-14:

- **Q1 scope** → A: single sub-spec covering both task 02-04 (dashboard) and task 02-05 (public profile).
- **Q2 dashboard pre-verification state** → A: full-page takeover. Unverified users see only the verification flow; verified users see the full tabbed dashboard. Clean two-state component, no disabled-state quirks.
- **Q3 API hook strategy** → B: separate `useCurrentUser` hook hitting `GET /api/v1/users/me` via its own TanStack Query key `["currentUser"]`. `useAuth` stays narrow for session-state gating. Dashboard consumes both hooks.
- **Q4 primitive placement** → A: generic `CodeDisplay` in `components/ui/`, domain `VerificationCodeDisplay` in `components/user/` wraps it. Same pattern applied to all 5 primitives (`Tabs`, `CountdownTimer`, `CodeDisplay`, `EmptyState`, `Toast`) going to `components/ui/` and 9 domain composites going to `components/user/`.
- **Q5 toast/notification pattern** → C: hybrid. Inline messages for form validation (matches existing `LoginForm` / `RegisterForm` pattern). New `Toast` primitive in `components/ui/` for ephemeral success/error feedback on async actions (copy to clipboard, avatar upload, profile edit).
- **Q6 profile picture upload UX** → C: drag-drop + click-select + preview before save. Most polished of the three, handles both interaction modes, client-side validation matches backend rules.
- **Q7 tab state model** → C: nested routes under `/dashboard/overview`, `/dashboard/bids`, `/dashboard/listings`. Each tab is a separate page file with Next.js 16 App Router. User explicitly chose this over simpler query-param approach (B) for production-readiness.
- **Q8 post-verification transition** → A+B: polling with visibility awareness (5s interval, pauses when tab backgrounded) + manual "Refresh status" button for instant feedback during dev testing with the `Dev/Simulate SL verify` Postman helper. WebSocket push (C) deferred to Epic 11.
- **Q9 unverified-user route handling** → A: Next.js 16 route groups — `(verified)/layout.tsx` carries the gate redirect. Route group doesn't appear in URL. Adding new verified-only tabs in future epics is a single-file drop into `(verified)/` with the gate applied automatically. User explicitly challenged this decision ("which is best practice for production, not just getting it working?") and the answer held: route groups are safe-by-default (the failure mode is "404, not leak"), one source of truth for the gate logic, Next.js 16 idiomatic, and middleware (C) was disqualified because the access token is in-memory and middleware can't read it without widening the refresh-cookie Path (security regression) or adding backend round-trips.
- **Approach — component decomposition** → B: thin pages, fat domain components. Each `page.tsx` is ~20-30 lines importing domain components from `components/user/`. Matches existing codebase patterns (`app/page.tsx` landing page, `app/login/page.tsx`, etc.).
- **Checkpoint 2 addition** → `EmptyState` added to the primitive inventory as a 5th UI primitive. Used by bids/listings empty tabs AND by public profile's placeholder sections.
- **Checkpoint 3 addition** → backend touch-up to add `updatedAt` to `UserResponse` DTO. Sub-spec 2a was "frontend-only" by original framing, but the frontend's `<Avatar cacheBust>` pattern requires a timestamp that bumps on every profile mutation. The `User` entity already has `@UpdateTimestamp updatedAt`; the DTO just needs it projected. 5-line backend change.
