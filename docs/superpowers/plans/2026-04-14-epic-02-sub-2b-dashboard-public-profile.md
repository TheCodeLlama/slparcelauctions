# Epic 02 Sub-spec 2b — Dashboard UI + Public Profile Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the frontend half of Epic 02 — a tabbed dashboard with a full-page verification takeover for unverified users, a public profile page, and a Toast primitive, consuming the backend endpoints already shipped in sub-spec 2a.

**Architecture:** Thin Next.js 16 App Router pages delegating to fat domain composites in `components/user/`. A new `lib/user/` data layer (separate from `lib/auth/`) owns TanStack Query hooks for `/me`, profile updates, avatar uploads, and verification codes. A `(verified)/` route group gates the verified sub-routes via its `layout.tsx`. `ProfilePictureUploader` uses a discriminated state machine for drag-drop + preview + upload. Cache-busting for avatar re-uploads rides on a new `updatedAt` field projected into the backend `UserResponse` DTO.

**Tech Stack:** Next.js 16.2.3 (App Router + route groups + Promise-params), React 19, TypeScript 5, Tailwind CSS 4 (Digital Curator tokens), TanStack Query 5 (visibility-aware polling), React Hook Form + Zod, Vitest 4 + React Testing Library + MSW 2, Spring Boot 4 / Java 26 for the one backend touch-up.

**Source spec:** `docs/superpowers/specs/2026-04-14-epic-02-sub-2b-dashboard-public-profile.md`

---

## Preflight checks

Before starting Task 1, confirm the branch + environment state.

- [ ] **Confirm you are on `dev`, fully up to date**

```bash
cd C:/Users/heath/Repos/Personal/slpa
git status
git fetch origin
git log --oneline -5
```

Expected: clean working tree, `dev` at or behind `origin/dev` by zero commits.

- [ ] **Create the feature branch**

```bash
git checkout -b task/02-sub-2b-dashboard-public-profile
git push -u origin task/02-sub-2b-dashboard-public-profile
```

- [ ] **Baseline test counts**

```bash
cd backend && ./mvnw test -q
```

Expected: `BUILD SUCCESS`, ~190 tests.

```bash
cd ../frontend && npm test -- --run
```

Expected: `Tests  185 passed | 1 todo (186)` (or within ±5 of this).

- [ ] **Verify dev services can start**

```bash
docker compose up -d postgres redis minio
```

Expected: all three containers healthy. Keep them running for manual smoke at Task 8.

- [ ] **Correctness notes baked into this plan (do NOT treat as optional):**

  1. **`StatusBadge` takes `tone`, not `variant`.** The spec code examples in §9.3, §9.7, §9.9 use `variant="success" | "warning" | "default"`; the real component uses `tone`. Plan code below uses `tone` throughout.
  2. **Icons are named imports, not an `Icons.X` namespace.** The spec references `Icons.Copy`, `Icons.CheckCircle`, etc.; this project's `components/ui/icons.ts` re-exports lucide icons by name. Plan code uses `import { Copy, CheckCircle2, ... } from "@/components/ui/icons"`.
  3. **`material-symbols-outlined` is not used anywhere in this codebase.** The spec has a few `<span className="material-symbols-outlined">verified</span>` references; this project uses lucide-react exclusively via `icons.ts`. Plan code uses `<BadgeCheck />`, `<CheckCircle2 />`, `<AlertCircle />`, etc.
  4. **`LoadingSpinner` does NOT already exist** in `components/ui/`. Task 2 must create it.
  5. **`Copy`, `CheckCircle2`, `AlertCircle`, `MessageSquare`, `Upload` are NOT currently exported** from `components/ui/icons.ts`. Task 2 adds them.
  6. **Existing `new UserResponse(` call sites** — Task 1 grep finds 3: `AuthControllerTest.java:155`, `UserControllerTest.java:54`, `UserControllerTest.java:158`. Each gets a new positional arg for `updatedAt`.

---

## File Structure

### New files (created in this sub-spec)

```
backend/src/main/java/com/slparcelauctions/backend/user/dto/UserResponse.java   modified (+updatedAt)

frontend/src/lib/user/
├── api.ts                                                                       new
├── hooks.ts                                                                     new
├── hooks.test.tsx                                                               new
└── index.ts                                                                     new

frontend/src/lib/api.ts                                                          modified (FormData path)
frontend/src/lib/api.test.ts                                                     modified (+FormData test)

frontend/src/components/ui/
├── icons.ts                                                                     modified (+Copy, CheckCircle2, AlertCircle, MessageSquare, Upload)
├── Avatar.tsx                                                                   modified (+cacheBust prop)
├── Avatar.test.tsx                                                              modified (+cacheBust test)
├── LoadingSpinner.tsx                                                           new
├── LoadingSpinner.test.tsx                                                      new
├── Tabs.tsx                                                                     new
├── Tabs.test.tsx                                                                new
├── CountdownTimer.tsx                                                           new
├── CountdownTimer.test.tsx                                                      new
├── CodeDisplay.tsx                                                              new
├── CodeDisplay.test.tsx                                                         new
├── EmptyState.tsx                                                               new
├── EmptyState.test.tsx                                                          new
├── Toast/
│   ├── ToastProvider.tsx                                                        new
│   ├── useToast.ts                                                              new
│   ├── index.ts                                                                 new
│   └── Toast.test.tsx                                                           new
└── index.ts                                                                     modified (+new exports)

frontend/src/components/user/
├── VerificationCodeDisplay.tsx                                                  new
├── VerificationCodeDisplay.test.tsx                                             new
├── UnverifiedVerifyFlow.tsx                                                     new
├── UnverifiedVerifyFlow.test.tsx                                                new
├── VerifiedIdentityCard.tsx                                                     new
├── VerifiedIdentityCard.test.tsx                                                new
├── ProfilePictureUploader.tsx                                                   new
├── ProfilePictureUploader.test.tsx                                              new
├── ProfileEditForm.tsx                                                          new
├── ProfileEditForm.test.tsx                                                     new
├── VerifiedOverview.tsx                                                         new
├── VerifiedOverview.test.tsx                                                    new
├── PublicProfileView.tsx                                                        new
├── PublicProfileView.test.tsx                                                   new
├── ReputationStars.tsx                                                          new
├── ReputationStars.test.tsx                                                     new
├── NewSellerBadge.tsx                                                           new
└── NewSellerBadge.test.tsx                                                      new

frontend/src/app/dashboard/
├── layout.tsx                                                                   replaces stub
├── page.tsx                                                                     replaces stub
├── page.test.tsx                                                                new
├── verify/
│   └── page.tsx                                                                 new
└── (verified)/
    ├── layout.tsx                                                               new
    ├── layout.test.tsx                                                          new
    ├── overview/
    │   └── page.tsx                                                             new
    ├── bids/
    │   └── page.tsx                                                             new
    └── listings/
        └── page.tsx                                                             new

frontend/src/app/users/[id]/page.tsx                                             new

frontend/src/app/providers.tsx                                                   modified (+ToastProvider)

frontend/src/test/msw/
├── handlers.ts                                                                  modified (+userHandlers, verificationHandlers)
└── fixtures.ts                                                                  modified (+profile fixtures)

frontend/src/test/integration/
└── dashboard-verify-flow.test.tsx                                                new (smoke)

README.md                                                                        modified (Task 8 sweep)
docs/implementation/FOOTGUNS.md                                                   modified (Task 8 additions)
```

### Modified files (existing)

- `backend/src/test/java/.../user/AuthControllerTest.java` — `new UserResponse(...)` call sites (1)
- `backend/src/test/java/.../user/UserControllerTest.java` — `new UserResponse(...)` call sites (2)

---

## Task 1 — Backend `updatedAt` + `lib/user/` foundation + FormData api.ts extension

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/dto/UserResponse.java`
- Modify: `backend/src/test/java/com/slparcelauctions/backend/user/AuthControllerTest.java` (1 call site ~line 155)
- Modify: `backend/src/test/java/com/slparcelauctions/backend/user/UserControllerTest.java` (2 call sites ~lines 54, 158)
- Modify: `frontend/src/lib/api.ts` (add FormData branch to `request<T>`)
- Modify: `frontend/src/lib/api.test.ts` (add FormData round-trip test)
- Create: `frontend/src/lib/user/api.ts`
- Create: `frontend/src/lib/user/hooks.ts`
- Create: `frontend/src/lib/user/index.ts`
- Create: `frontend/src/lib/user/hooks.test.tsx`
- Modify: `frontend/src/test/msw/fixtures.ts` (add profile fixtures)
- Modify: `frontend/src/test/msw/handlers.ts` (add userHandlers + verificationHandlers)

- [ ] **Step 1.1: Add `updatedAt` component to `UserResponse` record**

Open `backend/src/main/java/com/slparcelauctions/backend/user/dto/UserResponse.java`.

Add one new component at the end of the record header and one new line to `from(User)`:

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
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

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
                user.getCreatedAt(),
                user.getUpdatedAt());
    }
}
```

- [ ] **Step 1.2: Run backend tests to see the failures from the positional record change**

```bash
cd backend && ./mvnw test -q
```

Expected: `BUILD FAILURE` with compiler errors pointing at ~3 `new UserResponse(` call sites in test files. Note the exact file:line of each.

- [ ] **Step 1.3: Locate every positional UserResponse constructor call site in tests**

Use Grep (not the Bash tool):

Search the `backend/src/test` directory for the literal string `new UserResponse(`. Expected matches (from preflight exploration):
- `backend/src/test/java/com/slparcelauctions/backend/user/AuthControllerTest.java` around line 155
- `backend/src/test/java/com/slparcelauctions/backend/user/UserControllerTest.java` around lines 54 and 158

- [ ] **Step 1.4: Update each `new UserResponse(...)` call site — add `null` as the 18th positional arg**

For each site found in Step 1.3, add `null` (or the appropriate `OffsetDateTime.now()` value if the test needs a non-null timestamp) as the new last positional arg after `createdAt`. Example diff for a typical call site:

```java
// Before
new UserResponse(
    1L, "user@example.com", "Name", "bio", "url",
    null, null, null, null, null, null,
    true, null, true, Map.of(), Map.of(),
    OffsetDateTime.parse("2025-01-01T00:00:00Z")
)

// After
new UserResponse(
    1L, "user@example.com", "Name", "bio", "url",
    null, null, null, null, null, null,
    true, null, true, Map.of(), Map.of(),
    OffsetDateTime.parse("2025-01-01T00:00:00Z"),
    OffsetDateTime.parse("2025-01-01T00:00:00Z")
)
```

If the test uses `UserResponse.from(user)` instead of the positional constructor, no change is needed for that site.

- [ ] **Step 1.5: Run backend tests to verify green**

```bash
cd backend && ./mvnw test -q
```

Expected: `BUILD SUCCESS`, test count unchanged from baseline (~190 tests). The `updatedAt` field is now projected into every `UserResponse` serialization path.

- [ ] **Step 1.6: Extend `frontend/src/lib/api.ts` `request<T>` function with FormData detection**

Open `frontend/src/lib/api.ts`. Replace the `request<T>` function body around lines 114–148 so that the `Content-Type: application/json` header and `JSON.stringify` are both skipped when `body instanceof FormData`:

```typescript
async function request<T>(
  path: string,
  { body, headers, params, ...rest }: RequestOptions = {},
  isRetry = false
): Promise<T> {
  const token = getAccessToken();
  const isFormData = typeof FormData !== "undefined" && body instanceof FormData;
  const serializedBody =
    body === undefined ? undefined : isFormData ? (body as FormData) : JSON.stringify(body);

  const response = await fetch(`${BASE_URL}${buildPath(path, params)}`, {
    credentials: "include",
    ...rest,
    headers: {
      Accept: "application/json",
      ...(body !== undefined && !isFormData ? { "Content-Type": "application/json" } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: serializedBody,
  });

  if (response.status === 401 && !isRetry && !path.startsWith("/api/v1/auth/")) {
    return handleUnauthorized<T>(path, { body, headers, params, ...rest });
  }

  if (!response.ok) {
    let problem: ProblemDetail;
    try {
      problem = (await response.json()) as ProblemDetail;
    } catch {
      problem = { status: response.status, title: response.statusText };
    }
    throw new ApiError(problem);
  }

  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}
```

Note: `typeof FormData !== "undefined"` guards against SSR environments where `FormData` is not defined.

- [ ] **Step 1.7: Add FormData round-trip test to `frontend/src/lib/api.test.ts`**

Add a new test case to the existing `api.test.ts` describe block. The test verifies that a `FormData` body survives the serialization path unmodified and that no `Content-Type` header is set by the client (letting the browser set `multipart/form-data; boundary=...`).

```typescript
it("does not set Content-Type or stringify FormData bodies", async () => {
  const observed: { headers: Headers; body: BodyInit | null | undefined } = {
    headers: new Headers(),
    body: undefined,
  };
  const fetchMock = vi.fn(async (_url: string, init: RequestInit) => {
    observed.headers = new Headers(init.headers);
    observed.body = init.body as BodyInit;
    return new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });
  });
  vi.stubGlobal("fetch", fetchMock);

  const form = new FormData();
  form.append("file", new Blob(["hello"], { type: "image/png" }), "test.png");
  await api.post("/api/v1/users/me/avatar", form);

  expect(observed.headers.has("Content-Type")).toBe(false);
  expect(observed.body).toBeInstanceOf(FormData);
});
```

- [ ] **Step 1.8: Run the api.ts test suite and verify green**

```bash
cd frontend && npx vitest run src/lib/api.test.ts
```

Expected: all api.test.ts cases pass including the new FormData case.

- [ ] **Step 1.9: Create `frontend/src/lib/user/api.ts` — typed API wrappers**

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
  slBornDate: string | null;
  slPayinfo: number | null;
  verified: boolean;
  verifiedAt: string | null;
  emailVerified: boolean;
  notifyEmail: Record<string, unknown>;
  notifySlIm: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
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

export type ActiveCodeResponse = {
  code: string;
  expiresAt: string;
};

export type GenerateCodeResponse = {
  code: string;
  expiresAt: string;
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

export const verificationApi = {
  active: () => api.get<ActiveCodeResponse>("/api/v1/verification/active"),
  generate: () => api.post<GenerateCodeResponse>("/api/v1/verification/generate"),
};
```

- [ ] **Step 1.10: Create `frontend/src/lib/user/hooks.ts` — TanStack Query hooks**

```typescript
"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { useToast } from "@/components/ui/Toast";
import {
  userApi,
  verificationApi,
  type UpdateProfileRequest,
} from "./api";

export const CURRENT_USER_KEY = ["currentUser"] as const;
export const VERIFICATION_ACTIVE_KEY = ["verification", "active"] as const;

export function useCurrentUser(options?: { refetchInterval?: number | false }) {
  const session = useAuth();
  return useQuery({
    queryKey: CURRENT_USER_KEY,
    queryFn: () => userApi.me(),
    enabled: session.status === "authenticated",
    staleTime: 60_000,
    gcTime: Number.POSITIVE_INFINITY,
    refetchInterval: options?.refetchInterval ?? false,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: true,
    retry: false,
  });
}

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
      if (!(error instanceof ApiError && error.status === 400)) {
        toast.error("Failed to update profile");
      }
    },
  });
}

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

Note: this file imports from `@/components/ui/Toast`, which does not yet exist. Task 3 creates it. Until Task 3 lands, the import will fail TypeScript. Two options: (a) create a placeholder `Toast` module that throws "not yet implemented" from `useToast`, or (b) land Task 1 + Task 3 in a single commit. The plan chooses (a) — Task 1 creates a stub that Task 3 replaces. See Step 1.11.

- [ ] **Step 1.11: Create the `components/ui/Toast/` stub**

Create `frontend/src/components/ui/Toast/index.ts` with a minimal no-op stub so that `lib/user/hooks.ts` type-checks before Task 3:

```typescript
// STUB: real implementation lands in Task 3.
// This exists so that lib/user/hooks.ts can import { useToast } at Task 1 time.
export function useToast() {
  return {
    success: (_message: string) => {
      /* no-op until Task 3 wires the real provider */
    },
    error: (_message: string) => {
      /* no-op until Task 3 wires the real provider */
    },
  };
}

export function ToastProvider({ children }: { children: React.ReactNode }) {
  return <>{children}</>;
}
```

Rename to `.tsx` if JSX syntax trips TypeScript; otherwise leave as `.ts` and return children via `React.createElement`. Simplest approach — use `.tsx`:

```tsx
// STUB: real implementation lands in Task 3.
import type { ReactNode } from "react";

export function useToast() {
  return {
    success: (_message: string) => {},
    error: (_message: string) => {},
  };
}

export function ToastProvider({ children }: { children: ReactNode }) {
  return <>{children}</>;
}
```

Save as `frontend/src/components/ui/Toast/index.tsx`.

- [ ] **Step 1.12: Create `frontend/src/lib/user/index.ts` — barrel re-export**

```typescript
export * from "./api";
export * from "./hooks";
```

- [ ] **Step 1.13: Add profile fixtures to `frontend/src/test/msw/fixtures.ts`**

Append these fixtures to the existing file (preserve existing `mockUser` and `mockAuthResponse` exports):

```typescript
import type { CurrentUser, PublicUserProfile } from "@/lib/user/api";

export const mockUnverifiedCurrentUser: CurrentUser = {
  id: 42,
  email: "unverified@example.com",
  displayName: "Test User",
  bio: null,
  profilePicUrl: null,
  slAvatarUuid: null,
  slAvatarName: null,
  slUsername: null,
  slDisplayName: null,
  slBornDate: null,
  slPayinfo: null,
  verified: false,
  verifiedAt: null,
  emailVerified: true,
  notifyEmail: {},
  notifySlIm: {},
  createdAt: "2026-04-01T10:00:00Z",
  updatedAt: "2026-04-01T10:00:00Z",
};

export const mockVerifiedCurrentUser: CurrentUser = {
  ...mockUnverifiedCurrentUser,
  id: 42,
  displayName: "Verified Tester",
  bio: "Auction enthusiast",
  profilePicUrl: "/api/v1/users/42/avatar/large",
  slAvatarUuid: "11111111-1111-1111-1111-111111111111",
  slAvatarName: "TesterBot Resident",
  slUsername: "testerbot.resident",
  slDisplayName: "TesterBot",
  slBornDate: "2011-03-15",
  slPayinfo: 2,
  verified: true,
  verifiedAt: "2026-04-14T12:00:00Z",
  updatedAt: "2026-04-14T12:00:00Z",
};

export const mockPublicProfile: PublicUserProfile = {
  id: 42,
  displayName: "Verified Tester",
  bio: "Auction enthusiast",
  profilePicUrl: "/api/v1/users/42/avatar/large",
  slAvatarUuid: "11111111-1111-1111-1111-111111111111",
  slAvatarName: "TesterBot Resident",
  slUsername: "testerbot.resident",
  slDisplayName: "TesterBot",
  verified: true,
  avgSellerRating: 4.7,
  avgBuyerRating: null,
  totalSellerReviews: 12,
  totalBuyerReviews: 0,
  completedSales: 8,
  createdAt: "2026-04-01T10:00:00Z",
};

export const mockNewSellerPublicProfile: PublicUserProfile = {
  ...mockPublicProfile,
  id: 43,
  displayName: "New Seller",
  avgSellerRating: null,
  totalSellerReviews: 0,
  completedSales: 0,
};

export const mockUnverifiedPublicProfile: PublicUserProfile = {
  ...mockPublicProfile,
  id: 44,
  displayName: "Unverified Tester",
  verified: false,
  slAvatarUuid: null,
  slAvatarName: null,
  slUsername: null,
  slDisplayName: null,
};

export const mockValidationProblemDetail = {
  status: 400,
  title: "Bad Request",
  detail: "Validation failed",
  errors: { displayName: "must not be blank" },
};

export const mockUploadTooLargeProblemDetail = {
  status: 413,
  title: "Payload Too Large",
  detail: "Avatar must be 2MB or less",
};

export const mockUnsupportedFormatProblemDetail = {
  status: 400,
  title: "Bad Request",
  detail: "Upload must be a JPEG, PNG, or WebP image",
};

export const mockUserNotFoundProblemDetail = {
  status: 404,
  title: "Not Found",
  detail: "User not found",
};

export const mockVerificationNotFoundProblemDetail = {
  status: 404,
  title: "Not Found",
  detail: "No active verification code",
};

export const mockAlreadyVerifiedProblemDetail = {
  status: 409,
  title: "Conflict",
  detail: "User is already verified",
};
```

- [ ] **Step 1.14: Add `userHandlers` and `verificationHandlers` to `frontend/src/test/msw/handlers.ts`**

Append to the existing handlers module (preserve existing `authHandlers`):

```typescript
import { http, HttpResponse } from "msw";
import {
  mockUnverifiedCurrentUser,
  mockVerifiedCurrentUser,
  mockPublicProfile,
  mockValidationProblemDetail,
  mockUploadTooLargeProblemDetail,
  mockUnsupportedFormatProblemDetail,
  mockUserNotFoundProblemDetail,
  mockVerificationNotFoundProblemDetail,
  mockAlreadyVerifiedProblemDetail,
} from "./fixtures";
import type { CurrentUser, PublicUserProfile, UpdateProfileRequest } from "@/lib/user/api";

export const userHandlers = {
  meUnverified: (user: CurrentUser = mockUnverifiedCurrentUser) =>
    http.get("*/api/v1/users/me", () => HttpResponse.json(user)),

  meVerified: (user: CurrentUser = mockVerifiedCurrentUser) =>
    http.get("*/api/v1/users/me", () => HttpResponse.json(user)),

  meError: () =>
    http.get("*/api/v1/users/me", () =>
      HttpResponse.json(
        { status: 500, title: "Internal Server Error" },
        { status: 500 }
      )
    ),

  updateMeSuccess: (base: CurrentUser = mockVerifiedCurrentUser) =>
    http.put("*/api/v1/users/me", async ({ request }) => {
      const body = (await request.json()) as UpdateProfileRequest;
      return HttpResponse.json({
        ...base,
        displayName: body.displayName ?? base.displayName,
        bio: body.bio ?? base.bio,
        updatedAt: new Date().toISOString(),
      });
    }),

  updateMeValidationError: () =>
    http.put("*/api/v1/users/me", () =>
      HttpResponse.json(mockValidationProblemDetail, { status: 400 })
    ),

  uploadAvatarSuccess: (user: CurrentUser = mockVerifiedCurrentUser) =>
    http.post("*/api/v1/users/me/avatar", () =>
      HttpResponse.json({ ...user, updatedAt: new Date().toISOString() })
    ),

  uploadAvatarOversized: () =>
    http.post("*/api/v1/users/me/avatar", () =>
      HttpResponse.json(mockUploadTooLargeProblemDetail, { status: 413 })
    ),

  uploadAvatarUnsupportedFormat: () =>
    http.post("*/api/v1/users/me/avatar", () =>
      HttpResponse.json(mockUnsupportedFormatProblemDetail, { status: 400 })
    ),

  publicProfileSuccess: (profile: PublicUserProfile = mockPublicProfile) =>
    http.get("*/api/v1/users/:id", () => HttpResponse.json(profile)),

  publicProfileNotFound: () =>
    http.get("*/api/v1/users/:id", () =>
      HttpResponse.json(mockUserNotFoundProblemDetail, { status: 404 })
    ),
};

export const verificationHandlers = {
  activeNone: () =>
    http.get("*/api/v1/verification/active", () =>
      HttpResponse.json(mockVerificationNotFoundProblemDetail, { status: 404 })
    ),

  activeExists: (code = "123456", expiresAt = "2026-04-14T21:00:00Z") =>
    http.get("*/api/v1/verification/active", () =>
      HttpResponse.json({ code, expiresAt })
    ),

  generateSuccess: (code = "654321", expiresAt = "2026-04-14T21:15:00Z") =>
    http.post("*/api/v1/verification/generate", () =>
      HttpResponse.json({ code, expiresAt })
    ),

  generateAlreadyVerified: () =>
    http.post("*/api/v1/verification/generate", () =>
      HttpResponse.json(mockAlreadyVerifiedProblemDetail, { status: 409 })
    ),
};
```

- [ ] **Step 1.15: Extend `frontend/src/test/render.tsx` to support an `auth` option and export `makeWrapper`**

The existing `makeWrapper` is a non-exported closure that takes positional args (`theme`, `force`). Refactor it to accept an options object, export it, and add an `auth` knob that pre-seeds the TanStack Query cache with a mock session so `useAuth()` resolves to `{ status: "authenticated", user }` without hitting the bootstrap query. Replace the full file contents with:

```tsx
// frontend/src/test/render.tsx
import { render, type RenderOptions } from "@testing-library/react";
import { ThemeProvider } from "next-themes";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useRef, type ReactElement, type ReactNode } from "react";
import { ToastProvider } from "@/components/ui/Toast";
import type { AuthUser } from "@/lib/auth/session";
import { mockUser } from "./msw/fixtures";

type AuthState = "authenticated" | "anonymous";

type WrapperOptions = {
  theme?: "light" | "dark";
  forceTheme?: boolean;
  auth?: AuthState;
  authUser?: AuthUser;
};

type RenderWithProvidersOptions = Omit<RenderOptions, "wrapper"> & WrapperOptions;

const SESSION_QUERY_KEY = ["auth", "session"] as const;

export function makeWrapper({
  theme = "light",
  forceTheme = false,
  auth = "anonymous",
  authUser = mockUser,
}: WrapperOptions = {}) {
  return function Wrapper({ children }: { children: ReactNode }) {
    const queryClientRef = useRef<QueryClient | null>(null);
    if (!queryClientRef.current) {
      const client = new QueryClient({
        defaultOptions: {
          queries: { retry: false },
          mutations: { retry: false },
        },
      });
      if (auth === "authenticated") {
        client.setQueryData(SESSION_QUERY_KEY, authUser);
      } else {
        client.setQueryData(SESSION_QUERY_KEY, null);
      }
      queryClientRef.current = client;
    }

    return (
      <ThemeProvider
        attribute="class"
        defaultTheme={theme}
        enableSystem={false}
        forcedTheme={forceTheme ? theme : undefined}
      >
        <QueryClientProvider client={queryClientRef.current}>
          <ToastProvider>{children}</ToastProvider>
        </QueryClientProvider>
      </ThemeProvider>
    );
  };
}

export function renderWithProviders(
  ui: ReactElement,
  { theme, forceTheme, auth, authUser, ...options }: RenderWithProvidersOptions = {}
) {
  return render(ui, {
    wrapper: makeWrapper({ theme, forceTheme, auth, authUser }),
    ...options,
  });
}

export { screen, within, fireEvent, waitFor } from "@testing-library/react";
export { default as userEvent } from "@testing-library/user-event";
```

**Three things load-bearing in this rewrite:**
1. `setQueryData(["auth", "session"], authUser)` short-circuits the `useAuth` bootstrap query. `useAuth` is now a cache hit that resolves to `{ status: "authenticated", user }` without calling `/api/v1/auth/refresh`.
2. `ToastProvider` is wired into the tree so every test renders inside a working toast context. Matches production nesting (Task 3 updates `providers.tsx` to match).
3. `makeWrapper` is now exported so `renderHook` call sites can pass `wrapper: makeWrapper({ auth: "authenticated" })` directly.

**Note on import timing:** `makeWrapper` imports `ToastProvider` from `@/components/ui/Toast`. In Task 1 the Toast module is still the stub from Step 1.11, so this import resolves to the stub `ToastProvider` component. Task 3 replaces the stub with the real provider without changing the public export surface — no test-file changes are needed when Task 3 lands.

If `mockUser` does not already exist in `frontend/src/test/msw/fixtures.ts`, add a minimal shape that matches `AuthUser` (inspect `frontend/src/lib/auth/session.ts` for the type definition) alongside the fixtures added in Step 1.13.

- [ ] **Step 1.16: Create `frontend/src/lib/user/hooks.test.tsx`**

```tsx
import { beforeEach, describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { renderHook, waitFor, act } from "@testing-library/react";
import { server } from "@/test/msw/server";
import { userHandlers, verificationHandlers } from "@/test/msw/handlers";
import { mockVerifiedCurrentUser, mockUnverifiedCurrentUser } from "@/test/msw/fixtures";
import { makeWrapper } from "@/test/render";
import {
  useCurrentUser,
  useUpdateProfile,
  useUploadAvatar,
  useActiveVerificationCode,
  useGenerateVerificationCode,
} from "./hooks";

// These tests exercise the network layer through MSW. They rely on `useAuth`
// returning `{ status: "authenticated" }`, which is the default in the
// test wrapper's authenticated mode.
describe("user hooks", () => {
  describe("useCurrentUser", () => {
    it("fetches /me when session is authenticated", async () => {
      server.use(userHandlers.meVerified());
      const { result } = renderHook(() => useCurrentUser(), {
        wrapper: makeWrapper({ auth: "authenticated" }),
      });
      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.data?.id).toBe(mockVerifiedCurrentUser.id);
      expect(result.current.data?.updatedAt).toBe(mockVerifiedCurrentUser.updatedAt);
    });

    it("is disabled when session is not authenticated", async () => {
      server.use(userHandlers.meVerified());
      const { result } = renderHook(() => useCurrentUser(), {
        wrapper: makeWrapper({ auth: "anonymous" }),
      });
      await waitFor(() => expect(result.current.fetchStatus).toBe("idle"));
      expect(result.current.data).toBeUndefined();
    });
  });

  describe("useUpdateProfile", () => {
    it("invalidates currentUser on success", async () => {
      server.use(userHandlers.meVerified(), userHandlers.updateMeSuccess());
      const { result } = renderHook(
        () => ({
          me: useCurrentUser(),
          update: useUpdateProfile(),
        }),
        { wrapper: makeWrapper({ auth: "authenticated" }) }
      );
      await waitFor(() => expect(result.current.me.isSuccess).toBe(true));

      await act(async () => {
        await result.current.update.mutateAsync({
          displayName: "New Name",
          bio: "New bio",
        });
      });

      await waitFor(() => expect(result.current.me.data?.displayName).toBe("New Name"));
    });
  });

  describe("useUploadAvatar", () => {
    it("invalidates currentUser on success", async () => {
      const initial = { ...mockVerifiedCurrentUser, updatedAt: "2026-04-14T12:00:00Z" };
      const after = { ...initial, updatedAt: "2026-04-14T13:00:00Z" };
      server.use(userHandlers.meVerified(initial), userHandlers.uploadAvatarSuccess(after));

      const { result } = renderHook(
        () => ({
          me: useCurrentUser(),
          upload: useUploadAvatar(),
        }),
        { wrapper: makeWrapper({ auth: "authenticated" }) }
      );
      await waitFor(() => expect(result.current.me.isSuccess).toBe(true));

      const file = new File(["x"], "avatar.png", { type: "image/png" });
      await act(async () => {
        await result.current.upload.mutateAsync(file);
      });

      await waitFor(() =>
        expect(result.current.me.data?.updatedAt).not.toBe(initial.updatedAt)
      );
    });
  });

  describe("useActiveVerificationCode", () => {
    it("returns null on 404", async () => {
      server.use(verificationHandlers.activeNone());
      const { result } = renderHook(() => useActiveVerificationCode(), {
        wrapper: makeWrapper({ auth: "authenticated" }),
      });
      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.data).toBeNull();
    });

    it("returns the active code when present", async () => {
      server.use(verificationHandlers.activeExists("987654", "2026-04-14T21:30:00Z"));
      const { result } = renderHook(() => useActiveVerificationCode(), {
        wrapper: makeWrapper({ auth: "authenticated" }),
      });
      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.data?.code).toBe("987654");
    });
  });

  describe("useGenerateVerificationCode", () => {
    it("invalidates the active code query on success", async () => {
      server.use(
        verificationHandlers.activeNone(),
        verificationHandlers.generateSuccess("111222", "2026-04-14T21:30:00Z")
      );
      const { result } = renderHook(
        () => ({
          active: useActiveVerificationCode(),
          generate: useGenerateVerificationCode(),
        }),
        { wrapper: makeWrapper({ auth: "authenticated" }) }
      );
      await waitFor(() => expect(result.current.active.isSuccess).toBe(true));
      expect(result.current.active.data).toBeNull();

      // Swap the handler so the invalidated refetch returns the new code.
      server.use(verificationHandlers.activeExists("111222", "2026-04-14T21:30:00Z"));

      await act(async () => {
        await result.current.generate.mutateAsync();
      });

      await waitFor(() => expect(result.current.active.data?.code).toBe("111222"));
    });
  });
});
```

- [ ] **Step 1.17: Run the full frontend test suite**

```bash
cd frontend && npm test -- --run
```

Expected: `BUILD SUCCESS`, roughly 185 + 6 new hook tests + 1 new api test = ~192 tests passing.

- [ ] **Step 1.18: Commit Task 1**

```bash
cd C:/Users/heath/Repos/Personal/slpa
git add backend/src/main/java/com/slparcelauctions/backend/user/dto/UserResponse.java
git add backend/src/test/java/com/slparcelauctions/backend/user/AuthControllerTest.java
git add backend/src/test/java/com/slparcelauctions/backend/user/UserControllerTest.java
git add frontend/src/lib/api.ts
git add frontend/src/lib/api.test.ts
git add frontend/src/lib/user/
git add frontend/src/components/ui/Toast/index.tsx
git add frontend/src/test/msw/handlers.ts
git add frontend/src/test/msw/fixtures.ts
git add frontend/src/test/render.tsx
git commit -m "feat(user): add updatedAt to UserResponse and frontend user API layer

- Project updatedAt from User entity into UserResponse DTO
- Extend lib/api.ts request() to detect FormData and skip JSON serialization
- Add lib/user/ with typed api client and TanStack Query hooks
- Add userHandlers and verificationHandlers to MSW test harness
- Add mockVerifiedCurrentUser, mockPublicProfile, related fixtures
- Stub components/ui/Toast so hooks type-check before Task 3"
git push
```

---

## Task 2 — UI primitives + Avatar cacheBust extension

**Files:**
- Modify: `frontend/src/components/ui/icons.ts` (add Copy, CheckCircle2, AlertCircle, MessageSquare, Upload)
- Modify: `frontend/src/components/ui/Avatar.tsx` (add `cacheBust` prop)
- Modify: `frontend/src/components/ui/Avatar.test.tsx` (add cacheBust test)
- Create: `frontend/src/components/ui/LoadingSpinner.tsx`
- Create: `frontend/src/components/ui/LoadingSpinner.test.tsx`
- Create: `frontend/src/components/ui/Tabs.tsx`
- Create: `frontend/src/components/ui/Tabs.test.tsx`
- Create: `frontend/src/components/ui/CountdownTimer.tsx`
- Create: `frontend/src/components/ui/CountdownTimer.test.tsx`
- Create: `frontend/src/components/ui/CodeDisplay.tsx`
- Create: `frontend/src/components/ui/CodeDisplay.test.tsx`
- Create: `frontend/src/components/ui/EmptyState.tsx`
- Create: `frontend/src/components/ui/EmptyState.test.tsx`
- Modify: `frontend/src/components/ui/index.ts` (add new exports)

- [ ] **Step 2.1: Add missing lucide icons to `components/ui/icons.ts`**

Replace the file with:

```typescript
// src/components/ui/icons.ts
export {
  Sun,
  Moon,
  Bell,
  Search,
  Menu as MenuIcon,
  X,
  Check,
  ChevronDown,
  ChevronRight,
  ChevronLeft,
  ChevronUp,
  Eye,
  EyeOff,
  User,
  LogOut,
  Settings,
  Loader2,
} from "lucide-react";

// Task 01-10 landing page additions.
export {
  ShieldCheck,
  ListChecks,
  Gavel,
  CreditCard,
  Zap,
  Shield,
  Timer,
  BadgeCheck,
  Bot,
  Star,
} from "lucide-react";

// Sub-spec 2b additions — used by CodeDisplay, Toast, EmptyState, PublicProfileView.
export {
  Copy,
  CheckCircle2,
  AlertCircle,
  MessageSquare,
  Upload,
} from "lucide-react";
```

- [ ] **Step 2.2: Create `frontend/src/components/ui/LoadingSpinner.test.tsx`**

```tsx
import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { LoadingSpinner } from "./LoadingSpinner";

describe("LoadingSpinner", () => {
  it("renders with role=status for screen readers", () => {
    render(<LoadingSpinner />);
    expect(screen.getByRole("status")).toBeInTheDocument();
  });

  it("renders the label when provided", () => {
    render(<LoadingSpinner label="Loading profile..." />);
    expect(screen.getByText("Loading profile...")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2.3: Run the test to verify it fails**

```bash
cd frontend && npx vitest run src/components/ui/LoadingSpinner.test.tsx
```

Expected: FAIL with "Cannot find module './LoadingSpinner'".

- [ ] **Step 2.4: Create `frontend/src/components/ui/LoadingSpinner.tsx`**

```tsx
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

- [ ] **Step 2.5: Run LoadingSpinner tests to verify green**

```bash
cd frontend && npx vitest run src/components/ui/LoadingSpinner.test.tsx
```

Expected: PASS (2 cases).

- [ ] **Step 2.6: Create `frontend/src/components/ui/Tabs.test.tsx`**

```tsx
import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { Tabs } from "./Tabs";

vi.mock("next/navigation", () => ({
  usePathname: vi.fn(),
}));

import { usePathname } from "next/navigation";

const tabs = [
  { id: "overview", label: "Overview", href: "/dashboard/overview" },
  { id: "bids", label: "My Bids", href: "/dashboard/bids" },
  { id: "listings", label: "My Listings", href: "/dashboard/listings" },
];

describe("Tabs", () => {
  it("renders all tab labels", () => {
    vi.mocked(usePathname).mockReturnValue("/dashboard/overview");
    render(<Tabs tabs={tabs} />);
    for (const t of tabs) {
      expect(screen.getByRole("tab", { name: t.label })).toBeInTheDocument();
    }
  });

  it("marks the active tab with aria-selected based on pathname", () => {
    vi.mocked(usePathname).mockReturnValue("/dashboard/bids");
    render(<Tabs tabs={tabs} />);
    expect(screen.getByRole("tab", { name: "My Bids" })).toHaveAttribute(
      "aria-selected",
      "true"
    );
    expect(screen.getByRole("tab", { name: "Overview" })).toHaveAttribute(
      "aria-selected",
      "false"
    );
  });

  it("matches nested routes as active via prefix", () => {
    vi.mocked(usePathname).mockReturnValue("/dashboard/overview/settings");
    render(<Tabs tabs={tabs} />);
    expect(screen.getByRole("tab", { name: "Overview" })).toHaveAttribute(
      "aria-selected",
      "true"
    );
  });

  it("renders tabs inside a tablist nav with an aria-label", () => {
    vi.mocked(usePathname).mockReturnValue("/dashboard/overview");
    render(<Tabs tabs={tabs} />);
    expect(
      screen.getByRole("tablist", { name: /dashboard sections/i })
    ).toBeInTheDocument();
  });
});
```

- [ ] **Step 2.7: Run to verify failure**

```bash
cd frontend && npx vitest run src/components/ui/Tabs.test.tsx
```

Expected: FAIL ("Cannot find module './Tabs'").

- [ ] **Step 2.8: Create `frontend/src/components/ui/Tabs.tsx`**

```tsx
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
        const isActive =
          pathname === tab.href || pathname.startsWith(`${tab.href}/`);
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

- [ ] **Step 2.9: Run Tabs tests to verify green**

```bash
cd frontend && npx vitest run src/components/ui/Tabs.test.tsx
```

Expected: PASS (4 cases).

- [ ] **Step 2.10: Create `frontend/src/components/ui/CountdownTimer.test.tsx`**

```tsx
import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { render, screen, act } from "@testing-library/react";
import { CountdownTimer } from "./CountdownTimer";

describe("CountdownTimer", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-04-14T20:00:00Z"));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("renders the initial MM:SS countdown", () => {
    const expiresAt = new Date("2026-04-14T20:15:00Z");
    render(<CountdownTimer expiresAt={expiresAt} />);
    expect(screen.getByText("15:00")).toBeInTheDocument();
  });

  it("ticks forward on 1s intervals", () => {
    const expiresAt = new Date("2026-04-14T20:01:00Z");
    render(<CountdownTimer expiresAt={expiresAt} />);
    expect(screen.getByText("01:00")).toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(5000);
    });
    expect(screen.getByText("00:55")).toBeInTheDocument();
  });

  it("fires onExpire exactly once when remaining reaches zero", () => {
    const onExpire = vi.fn();
    const expiresAt = new Date("2026-04-14T20:00:03Z");
    render(<CountdownTimer expiresAt={expiresAt} onExpire={onExpire} />);
    act(() => {
      vi.advanceTimersByTime(3500);
    });
    expect(onExpire).toHaveBeenCalledTimes(1);
    act(() => {
      vi.advanceTimersByTime(5000);
    });
    expect(onExpire).toHaveBeenCalledTimes(1);
  });

  it("renders '--:--' after expiry in mm:ss format", () => {
    const expiresAt = new Date("2026-04-14T19:59:00Z");
    render(<CountdownTimer expiresAt={expiresAt} />);
    expect(screen.getByText("--:--")).toBeInTheDocument();
  });

  it("supports hh:mm:ss format", () => {
    const expiresAt = new Date("2026-04-14T22:30:45Z");
    render(<CountdownTimer expiresAt={expiresAt} format="hh:mm:ss" />);
    expect(screen.getByText("02:30:45")).toBeInTheDocument();
  });

  it("clears its interval on unmount", () => {
    const { unmount } = render(
      <CountdownTimer expiresAt={new Date("2026-04-14T21:00:00Z")} />
    );
    unmount();
    expect(vi.getTimerCount()).toBe(0);
  });
});
```

- [ ] **Step 2.11: Run to verify failure**

```bash
cd frontend && npx vitest run src/components/ui/CountdownTimer.test.tsx
```

Expected: FAIL ("Cannot find module './CountdownTimer'").

- [ ] **Step 2.12: Create `frontend/src/components/ui/CountdownTimer.tsx`**

```tsx
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
      ? `${hours.toString().padStart(2, "0")}:${minutes
          .toString()
          .padStart(2, "0")}:${seconds.toString().padStart(2, "0")}`
      : remaining === 0
        ? "--:--"
        : `${minutes.toString().padStart(2, "0")}:${seconds
            .toString()
            .padStart(2, "0")}`;

  return (
    <span
      className={cn("font-mono text-2xl", className)}
      aria-live="polite"
    >
      {display}
    </span>
  );
}
```

- [ ] **Step 2.13: Run tests to verify green**

```bash
cd frontend && npx vitest run src/components/ui/CountdownTimer.test.tsx
```

Expected: PASS (6 cases).

- [ ] **Step 2.14: Create `frontend/src/components/ui/CodeDisplay.test.tsx`**

```tsx
import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { CodeDisplay } from "./CodeDisplay";

describe("CodeDisplay", () => {
  let writeText: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    writeText = vi.fn(() => Promise.resolve());
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText },
      configurable: true,
    });
  });

  it("renders the code and label", () => {
    render(<CodeDisplay code="123456" label="Enter this code" />);
    expect(screen.getByText("123456")).toBeInTheDocument();
    expect(screen.getByText(/enter this code/i)).toBeInTheDocument();
  });

  it("calls navigator.clipboard.writeText on copy click", async () => {
    const user = userEvent.setup();
    render(<CodeDisplay code="987654" />);
    await user.click(screen.getByRole("button", { name: /copy code/i }));
    expect(writeText).toHaveBeenCalledWith("987654");
  });

  it("fires onCopySuccess after a successful clipboard write", async () => {
    const user = userEvent.setup();
    const onCopySuccess = vi.fn();
    render(<CodeDisplay code="555555" onCopySuccess={onCopySuccess} />);
    await user.click(screen.getByRole("button", { name: /copy code/i }));
    await vi.waitFor(() => expect(onCopySuccess).toHaveBeenCalled());
  });

  it("fires onCopyError when the clipboard rejects", async () => {
    writeText.mockRejectedValueOnce(new Error("denied"));
    const user = userEvent.setup();
    const onCopyError = vi.fn();
    render(<CodeDisplay code="555555" onCopyError={onCopyError} />);
    await user.click(screen.getByRole("button", { name: /copy code/i }));
    await vi.waitFor(() =>
      expect(onCopyError).toHaveBeenCalledWith(expect.any(Error))
    );
  });
});
```

- [ ] **Step 2.15: Run to verify failure, then create `CodeDisplay.tsx`**

```bash
cd frontend && npx vitest run src/components/ui/CodeDisplay.test.tsx
```

Expected: FAIL. Then create `frontend/src/components/ui/CodeDisplay.tsx`:

```tsx
"use client";

import { IconButton } from "@/components/ui/IconButton";
import { Copy } from "@/components/ui/icons";
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
        <IconButton onClick={handleCopy} aria-label="Copy code to clipboard">
          <Copy className="size-5" />
        </IconButton>
      </div>
    </div>
  );
}
```

Note: verify that the existing `IconButton` accepts children — if its signature expects an `icon` prop, inspect `frontend/src/components/ui/IconButton.tsx` and match the local API. Otherwise fall through to a plain `<button>` with the `Copy` icon as a child.

- [ ] **Step 2.16: Run CodeDisplay tests to verify green**

```bash
cd frontend && npx vitest run src/components/ui/CodeDisplay.test.tsx
```

Expected: PASS (4 cases).

- [ ] **Step 2.17: Create `frontend/src/components/ui/EmptyState.test.tsx`**

```tsx
import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { MessageSquare } from "@/components/ui/icons";
import { EmptyState } from "./EmptyState";

describe("EmptyState", () => {
  it("renders the icon, headline, and description", () => {
    render(
      <EmptyState
        icon={MessageSquare}
        headline="No reviews yet"
        description="Reviews will appear here after the first auction."
      />
    );
    expect(screen.getByRole("heading", { name: /no reviews yet/i })).toBeInTheDocument();
    expect(
      screen.getByText("Reviews will appear here after the first auction.")
    ).toBeInTheDocument();
  });

  it("renders without a description", () => {
    render(<EmptyState icon={MessageSquare} headline="Nothing here" />);
    expect(screen.getByRole("heading", { name: /nothing here/i })).toBeInTheDocument();
  });

  it("uses an h3 for the headline", () => {
    render(<EmptyState icon={MessageSquare} headline="Empty" />);
    const heading = screen.getByRole("heading", { name: /empty/i });
    expect(heading.tagName).toBe("H3");
  });
});
```

- [ ] **Step 2.18: Run to verify failure, then create `EmptyState.tsx`**

```bash
cd frontend && npx vitest run src/components/ui/EmptyState.test.tsx
```

Expected: FAIL. Then create `frontend/src/components/ui/EmptyState.tsx`:

```tsx
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

- [ ] **Step 2.19: Run EmptyState tests to verify green**

```bash
cd frontend && npx vitest run src/components/ui/EmptyState.test.tsx
```

Expected: PASS (3 cases).

- [ ] **Step 2.20: Extend `Avatar.tsx` with `cacheBust` prop**

Open `frontend/src/components/ui/Avatar.tsx` and replace with:

```tsx
import Image from "next/image";
import { cn } from "@/lib/cn";

type AvatarSize = "xs" | "sm" | "md" | "lg" | "xl";

type AvatarProps = {
  src?: string;
  alt: string;
  name?: string;
  size?: AvatarSize;
  className?: string;
  cacheBust?: string | number;
};

const sizeMap: Record<AvatarSize, { px: number; class: string }> = {
  xs: { px: 24, class: "size-6 text-label-sm" },
  sm: { px: 32, class: "size-8 text-label-md" },
  md: { px: 40, class: "size-10 text-label-lg" },
  lg: { px: 56, class: "size-14 text-title-md" },
  xl: { px: 80, class: "size-20 text-title-lg" },
};

function initialsFromName(name?: string): string {
  if (!name) return "?";
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  if (parts.length === 1) return parts[0][0].toUpperCase();
  return (parts[0][0] + parts[1][0]).toUpperCase();
}

function withCacheBust(src: string, cacheBust: string | number | undefined): string {
  if (cacheBust === undefined) return src;
  const sep = src.includes("?") ? "&" : "?";
  return `${src}${sep}v=${encodeURIComponent(String(cacheBust))}`;
}

export function Avatar({
  src,
  alt,
  name,
  size = "md",
  className,
  cacheBust,
}: AvatarProps) {
  const { px, class: sizeClass } = sizeMap[size];

  if (src) {
    return (
      <Image
        src={withCacheBust(src, cacheBust)}
        alt={alt}
        width={px}
        height={px}
        className={cn("rounded-full object-cover", className)}
      />
    );
  }

  return (
    <div
      role="img"
      aria-label={alt}
      className={cn(
        "rounded-full bg-tertiary-container text-on-tertiary-container font-semibold inline-flex items-center justify-center",
        sizeClass,
        className
      )}
    >
      <span aria-hidden="true">{initialsFromName(name)}</span>
    </div>
  );
}
```

- [ ] **Step 2.21: Add a `cacheBust` test case to `Avatar.test.tsx`**

Open the existing `frontend/src/components/ui/Avatar.test.tsx` and add:

```tsx
it("appends ?v=<cacheBust> to the src when cacheBust is provided", () => {
  render(
    <Avatar
      src="/api/v1/users/42/avatar/large"
      alt="User"
      cacheBust="2026-04-14T12:00:00Z"
    />
  );
  const img = screen.getByRole("img");
  expect(img).toHaveAttribute(
    "src",
    expect.stringContaining("v=2026-04-14T12%3A00%3A00Z")
  );
});

it("merges cacheBust into an existing query string with &", () => {
  render(
    <Avatar
      src="/api/v1/users/42/avatar/large?size=80"
      alt="User"
      cacheBust={12345}
    />
  );
  const img = screen.getByRole("img");
  expect(img).toHaveAttribute("src", expect.stringContaining("size=80&v=12345"));
});
```

Note: Next.js `<Image>` may rewrite the `src` at render time via the image optimizer — if so, the `toHaveAttribute("src", ...)` assertion may fail. If that happens, use `expect(img.getAttribute("src")).toMatch(/v=2026-04-14T12%3A00%3A00Z/)` instead, or pull the `src` off the `<Image>` via its `props.src` through an `vi.mock("next/image", ...)` that renders a plain `<img>`.

- [ ] **Step 2.22: Run Avatar tests to verify green**

```bash
cd frontend && npx vitest run src/components/ui/Avatar.test.tsx
```

Expected: PASS, existing + 2 new cases.

- [ ] **Step 2.23: Update `frontend/src/components/ui/index.ts` with new exports**

Add to the existing barrel:

```typescript
export { LoadingSpinner } from "./LoadingSpinner";
export { Tabs } from "./Tabs";
export type { TabItem } from "./Tabs";
export { CountdownTimer } from "./CountdownTimer";
export { CodeDisplay } from "./CodeDisplay";
export { EmptyState } from "./EmptyState";
```

- [ ] **Step 2.24: Run the full frontend test suite**

```bash
cd frontend && npm test -- --run
```

Expected: ~192 + 2 Avatar + 2 LoadingSpinner + 4 Tabs + 6 CountdownTimer + 4 CodeDisplay + 3 EmptyState = ~213 tests passing.

- [ ] **Step 2.25: Commit Task 2**

```bash
git add frontend/src/components/ui/icons.ts
git add frontend/src/components/ui/Avatar.tsx
git add frontend/src/components/ui/Avatar.test.tsx
git add frontend/src/components/ui/LoadingSpinner.tsx
git add frontend/src/components/ui/LoadingSpinner.test.tsx
git add frontend/src/components/ui/Tabs.tsx
git add frontend/src/components/ui/Tabs.test.tsx
git add frontend/src/components/ui/CountdownTimer.tsx
git add frontend/src/components/ui/CountdownTimer.test.tsx
git add frontend/src/components/ui/CodeDisplay.tsx
git add frontend/src/components/ui/CodeDisplay.test.tsx
git add frontend/src/components/ui/EmptyState.tsx
git add frontend/src/components/ui/EmptyState.test.tsx
git add frontend/src/components/ui/index.ts
git commit -m "feat(ui): add Tabs, CountdownTimer, CodeDisplay, EmptyState, LoadingSpinner primitives and Avatar cacheBust prop"
git push
```

---

## Task 3 — Toast primitive + provider wiring

**Files:**
- Replace: `frontend/src/components/ui/Toast/index.tsx` (stub → real export barrel)
- Create: `frontend/src/components/ui/Toast/ToastProvider.tsx`
- Create: `frontend/src/components/ui/Toast/useToast.ts`
- Create: `frontend/src/components/ui/Toast/Toast.test.tsx`
- Modify: `frontend/src/app/providers.tsx` (wire `<ToastProvider>`)
- Modify: `frontend/src/app/globals.css` (add `slide-in-from-top` keyframe) OR `tailwind.config` if the project uses `extend.animation`

- [ ] **Step 3.1: Inspect the existing animation setup**

Before writing the keyframe, check how the project currently defines animations:

```bash
cd frontend
```

Use Grep to find occurrences of `@keyframes` in `src/app/globals.css` and any `tailwind.config.*` files. Choose the location that matches the existing convention. If neither exists, add to `globals.css`.

- [ ] **Step 3.2: Create `frontend/src/components/ui/Toast/Toast.test.tsx`**

```tsx
import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { render, screen, act } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ToastProvider } from "./ToastProvider";
import { useToast } from "./useToast";

function Trigger({ kind, message }: { kind: "success" | "error"; message: string }) {
  const toast = useToast();
  return (
    <button
      onClick={() => (kind === "success" ? toast.success(message) : toast.error(message))}
    >
      Fire
    </button>
  );
}

describe("Toast", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("throws when useToast is called outside a ToastProvider", () => {
    // Suppress console.error from React's error boundary log
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    function Bad() {
      useToast();
      return null;
    }
    expect(() => render(<Bad />)).toThrow(/useToast must be used inside a ToastProvider/);
    spy.mockRestore();
  });

  it("renders a success toast with role=status", async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    render(
      <ToastProvider>
        <Trigger kind="success" message="Saved!" />
      </ToastProvider>
    );
    await user.click(screen.getByRole("button", { name: /fire/i }));
    expect(screen.getByRole("status")).toHaveTextContent("Saved!");
  });

  it("renders an error toast with role=alert", async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    render(
      <ToastProvider>
        <Trigger kind="error" message="Oops" />
      </ToastProvider>
    );
    await user.click(screen.getByRole("button", { name: /fire/i }));
    expect(screen.getByRole("alert")).toHaveTextContent("Oops");
  });

  it("auto-dismisses after 3 seconds", async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    render(
      <ToastProvider>
        <Trigger kind="success" message="Gone soon" />
      </ToastProvider>
    );
    await user.click(screen.getByRole("button", { name: /fire/i }));
    expect(screen.getByText("Gone soon")).toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(3100);
    });
    expect(screen.queryByText("Gone soon")).not.toBeInTheDocument();
  });

  it("caps visible toasts at 3 by dropping the oldest", async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    function MultiTrigger() {
      const toast = useToast();
      return (
        <button
          onClick={() => {
            toast.success("One");
            toast.success("Two");
            toast.success("Three");
            toast.success("Four");
          }}
        >
          Spam
        </button>
      );
    }
    render(
      <ToastProvider>
        <MultiTrigger />
      </ToastProvider>
    );
    await user.click(screen.getByRole("button", { name: /spam/i }));
    expect(screen.queryByText("One")).not.toBeInTheDocument();
    expect(screen.getByText("Two")).toBeInTheDocument();
    expect(screen.getByText("Three")).toBeInTheDocument();
    expect(screen.getByText("Four")).toBeInTheDocument();
  });

  it("stacks multiple toasts in order of arrival", async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    function DoubleTrigger() {
      const toast = useToast();
      return (
        <button
          onClick={() => {
            toast.success("First");
            toast.error("Second");
          }}
        >
          Two
        </button>
      );
    }
    render(
      <ToastProvider>
        <DoubleTrigger />
      </ToastProvider>
    );
    await user.click(screen.getByRole("button", { name: /two/i }));
    const toasts = screen.getAllByText(/first|second/i);
    expect(toasts[0]).toHaveTextContent("First");
    expect(toasts[1]).toHaveTextContent("Second");
  });
});
```

- [ ] **Step 3.3: Replace the stub `Toast/index.tsx` and create the real `ToastProvider.tsx` + `useToast.ts`**

Create `frontend/src/components/ui/Toast/ToastProvider.tsx`:

```tsx
"use client";

import { createContext, useCallback, useEffect, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { CheckCircle2, AlertCircle } from "@/components/ui/icons";
import { cn } from "@/lib/cn";

export type ToastKind = "success" | "error";
export type ToastItem = { id: string; kind: ToastKind; message: string };

export type ToastContextValue = {
  toasts: ToastItem[];
  push: (kind: ToastKind, message: string) => void;
  dismiss: (id: string) => void;
};

export const ToastContext = createContext<ToastContextValue | null>(null);

const MAX_VISIBLE = 3;
const AUTO_DISMISS_MS = 3000;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
  }, []);

  const dismiss = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const push = useCallback(
    (kind: ToastKind, message: string) => {
      const id =
        typeof crypto !== "undefined" && "randomUUID" in crypto
          ? crypto.randomUUID()
          : `${Date.now()}-${Math.random()}`;
      setToasts((prev) => {
        const next = [...prev, { id, kind, message }];
        return next.length > MAX_VISIBLE ? next.slice(-MAX_VISIBLE) : next;
      });
      setTimeout(() => dismiss(id), AUTO_DISMISS_MS);
    },
    [dismiss]
  );

  return (
    <ToastContext.Provider value={{ toasts, push, dismiss }}>
      {children}
      {mounted &&
        createPortal(
          <div
            className="fixed top-4 right-4 flex flex-col gap-2 z-50 pointer-events-none"
            data-testid="toast-stack"
          >
            {toasts.map((toast) => {
              const Icon = toast.kind === "success" ? CheckCircle2 : AlertCircle;
              return (
                <div
                  key={toast.id}
                  role={toast.kind === "error" ? "alert" : "status"}
                  className={cn(
                    "pointer-events-auto flex items-center gap-3 px-4 py-3 rounded-lg shadow-lg animate-slide-in-from-top",
                    toast.kind === "success"
                      ? "bg-primary text-on-primary"
                      : "bg-error text-on-error"
                  )}
                >
                  <Icon className="size-5" aria-hidden="true" />
                  <span className="text-body-md font-medium">{toast.message}</span>
                </div>
              );
            })}
          </div>,
          document.body
        )}
    </ToastContext.Provider>
  );
}
```

Create `frontend/src/components/ui/Toast/useToast.ts`:

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

Replace `frontend/src/components/ui/Toast/index.tsx` with a real barrel (delete the stub):

```typescript
export { ToastProvider, ToastContext } from "./ToastProvider";
export type { ToastKind, ToastItem, ToastContextValue } from "./ToastProvider";
export { useToast } from "./useToast";
```

- [ ] **Step 3.4: Add the `slide-in-from-top` animation**

Append to `frontend/src/app/globals.css` (or wherever keyframes currently live):

```css
@keyframes slide-in-from-top {
  from {
    transform: translateY(-1rem);
    opacity: 0;
  }
  to {
    transform: translateY(0);
    opacity: 1;
  }
}

.animate-slide-in-from-top {
  animation: slide-in-from-top 200ms ease-out;
}
```

If the project uses `tailwind.config.ts` with `extend.animation`, add the utility there instead and omit the raw CSS. Match the existing convention.

- [ ] **Step 3.5: Wire `<ToastProvider>` into `app/providers.tsx`**

Open `frontend/src/app/providers.tsx`. The existing file wraps `ThemeProvider` + `QueryClientProvider`. Add `ToastProvider` as the innermost wrapper:

```tsx
"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";
import { ThemeProvider } from "next-themes";
import { ToastProvider } from "@/components/ui/Toast";
import { configureApiClient } from "@/lib/api";

export function Providers({ children }: { children: React.ReactNode }) {
  const [queryClient] = useState(() => {
    const client = new QueryClient({
      defaultOptions: {
        queries: {
          staleTime: 60_000,
          refetchOnWindowFocus: false,
          retry: 1,
        },
      },
    });
    configureApiClient(client);
    return client;
  });

  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider attribute="class" defaultTheme="dark">
        <ToastProvider>{children}</ToastProvider>
      </ThemeProvider>
    </QueryClientProvider>
  );
}
```

(Preserve the exact shape of the existing `providers.tsx` — only add the `ToastProvider` import + wrapper.)

- [ ] **Step 3.6: Run the Toast test suite**

```bash
cd frontend && npx vitest run src/components/ui/Toast/Toast.test.tsx
```

Expected: PASS (6 cases).

- [ ] **Step 3.7: Run the full frontend test suite**

```bash
cd frontend && npm test -- --run
```

Expected: ~219 tests passing (213 + 6 Toast cases).

- [ ] **Step 3.8: Commit Task 3**

```bash
git add frontend/src/components/ui/Toast/
git add frontend/src/app/providers.tsx
git add frontend/src/app/globals.css
git commit -m "feat(ui): add Toast primitive with portal-rendered stack and useToast hook"
git push
```

---

## Task 4 — Verification domain components

**Files:**
- Create: `frontend/src/components/user/VerificationCodeDisplay.tsx`
- Create: `frontend/src/components/user/VerificationCodeDisplay.test.tsx`
- Create: `frontend/src/components/user/UnverifiedVerifyFlow.tsx`
- Create: `frontend/src/components/user/UnverifiedVerifyFlow.test.tsx`

- [ ] **Step 4.1: Write `VerificationCodeDisplay.test.tsx`**

```tsx
import { describe, expect, it, beforeEach, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/render";
import { server } from "@/test/msw/server";
import { verificationHandlers } from "@/test/msw/handlers";
import { VerificationCodeDisplay } from "./VerificationCodeDisplay";

describe("VerificationCodeDisplay", () => {
  beforeEach(() => {
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText: vi.fn(() => Promise.resolve()) },
      configurable: true,
    });
  });

  it("shows the generate button when no active code", async () => {
    server.use(verificationHandlers.activeNone());
    renderWithProviders(<VerificationCodeDisplay />, { auth: "authenticated" });
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: /generate verification code/i })
      ).toBeInTheDocument()
    );
  });

  it("clicking generate triggers the generate mutation and renders the code", async () => {
    server.use(
      verificationHandlers.activeNone(),
      verificationHandlers.generateSuccess("654321", "2026-04-14T21:15:00Z")
    );
    const user = userEvent.setup();
    renderWithProviders(<VerificationCodeDisplay />, { auth: "authenticated" });

    await user.click(
      await screen.findByRole("button", { name: /generate verification code/i })
    );
    server.use(verificationHandlers.activeExists("654321", "2026-04-14T21:15:00Z"));

    expect(await screen.findByText("654321")).toBeInTheDocument();
  });

  it("renders an existing active code from the initial fetch", async () => {
    server.use(verificationHandlers.activeExists("112233", "2026-04-14T21:30:00Z"));
    renderWithProviders(<VerificationCodeDisplay />, { auth: "authenticated" });
    expect(await screen.findByText("112233")).toBeInTheDocument();
  });

  it("shows a confirm dialog when regenerate is clicked", async () => {
    server.use(verificationHandlers.activeExists("112233", "2026-04-14T21:30:00Z"));
    const user = userEvent.setup();
    renderWithProviders(<VerificationCodeDisplay />, { auth: "authenticated" });
    await screen.findByText("112233");
    await user.click(screen.getByRole("button", { name: /regenerate code/i }));
    expect(screen.getByText(/this will invalidate the current code/i)).toBeInTheDocument();
  });

  it("cancel on confirm dialog returns to previous state", async () => {
    server.use(verificationHandlers.activeExists("112233", "2026-04-14T21:30:00Z"));
    const user = userEvent.setup();
    renderWithProviders(<VerificationCodeDisplay />, { auth: "authenticated" });
    await screen.findByText("112233");
    await user.click(screen.getByRole("button", { name: /regenerate code/i }));
    await user.click(screen.getByRole("button", { name: /cancel/i }));
    expect(
      screen.queryByText(/this will invalidate the current code/i)
    ).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /regenerate code/i })).toBeInTheDocument();
  });

  it("confirm dialog 'yes' triggers generate mutation", async () => {
    server.use(
      verificationHandlers.activeExists("112233", "2026-04-14T21:30:00Z"),
      verificationHandlers.generateSuccess("999999", "2026-04-14T21:45:00Z")
    );
    const user = userEvent.setup();
    renderWithProviders(<VerificationCodeDisplay />, { auth: "authenticated" });
    await screen.findByText("112233");
    await user.click(screen.getByRole("button", { name: /regenerate code/i }));
    await user.click(screen.getByRole("button", { name: /yes, regenerate/i }));
    server.use(verificationHandlers.activeExists("999999", "2026-04-14T21:45:00Z"));
    expect(await screen.findByText("999999")).toBeInTheDocument();
  });
});
```

- [ ] **Step 4.2: Create `frontend/src/components/user/VerificationCodeDisplay.tsx`**

```tsx
"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { CodeDisplay } from "@/components/ui/CodeDisplay";
import { CountdownTimer } from "@/components/ui/CountdownTimer";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { useToast } from "@/components/ui/Toast";
import {
  useActiveVerificationCode,
  useGenerateVerificationCode,
} from "@/lib/user";

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
        <CountdownTimer expiresAt={expiresAt} />
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

Note: verify the `Button` component's `variant` prop supports `"secondary"` and `"ghost"`. Inspect `frontend/src/components/ui/Button.tsx` and match the actual API; if only `primary`/`secondary`/`outline`/`text` exist, adjust accordingly.

- [ ] **Step 4.3: Run the VerificationCodeDisplay tests and verify green**

```bash
cd frontend && npx vitest run src/components/user/VerificationCodeDisplay.test.tsx
```

Expected: PASS (6 cases).

- [ ] **Step 4.4: Write `UnverifiedVerifyFlow.test.tsx`**

```tsx
import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { screen, waitFor, act } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/render";
import { server } from "@/test/msw/server";
import { userHandlers, verificationHandlers } from "@/test/msw/handlers";
import {
  mockUnverifiedCurrentUser,
  mockVerifiedCurrentUser,
} from "@/test/msw/fixtures";
import { UnverifiedVerifyFlow } from "./UnverifiedVerifyFlow";

const replace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn(), back: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/dashboard/verify",
}));

describe("UnverifiedVerifyFlow", () => {
  beforeEach(() => {
    replace.mockReset();
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("renders the explanatory copy and verification code display", async () => {
    server.use(
      userHandlers.meUnverified(),
      verificationHandlers.activeNone()
    );
    renderWithProviders(<UnverifiedVerifyFlow />, { auth: "authenticated" });
    expect(
      await screen.findByText(/to bid, list parcels/i)
    ).toBeInTheDocument();
    expect(
      await screen.findByRole("button", { name: /generate verification code/i })
    ).toBeInTheDocument();
  });

  it("transitions to /dashboard/overview when /me returns verified: true after polling", async () => {
    server.use(userHandlers.meUnverified(), verificationHandlers.activeNone());
    renderWithProviders(<UnverifiedVerifyFlow />, { auth: "authenticated" });

    await screen.findByText(/to bid, list parcels/i);

    server.use(userHandlers.meVerified());
    await act(async () => {
      vi.advanceTimersByTime(5100);
    });

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/dashboard/overview"));
  });

  it("manual refresh button triggers an immediate /me refetch", async () => {
    server.use(userHandlers.meUnverified(), verificationHandlers.activeNone());
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderWithProviders(<UnverifiedVerifyFlow />, { auth: "authenticated" });

    await screen.findByText(/to bid, list parcels/i);

    server.use(userHandlers.meVerified());
    await user.click(
      screen.getByRole("button", { name: /refresh my status/i })
    );

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/dashboard/overview"));
  });
});
```

- [ ] **Step 4.5: Create `frontend/src/components/user/UnverifiedVerifyFlow.tsx`**

```tsx
"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/Button";
import { VerificationCodeDisplay } from "@/components/user/VerificationCodeDisplay";
import { useCurrentUser } from "@/lib/user";

export function UnverifiedVerifyFlow() {
  const router = useRouter();
  const { data: user, refetch } = useCurrentUser({ refetchInterval: 5000 });

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

- [ ] **Step 4.6: Run UnverifiedVerifyFlow tests to verify green**

```bash
cd frontend && npx vitest run src/components/user/UnverifiedVerifyFlow.test.tsx
```

Expected: PASS (3 cases).

- [ ] **Step 4.7: Run full frontend suite**

```bash
cd frontend && npm test -- --run
```

Expected: ~228 tests passing (~219 + 6 VerificationCodeDisplay + 3 UnverifiedVerifyFlow).

- [ ] **Step 4.8: Commit Task 4**

```bash
git add frontend/src/components/user/VerificationCodeDisplay.tsx
git add frontend/src/components/user/VerificationCodeDisplay.test.tsx
git add frontend/src/components/user/UnverifiedVerifyFlow.tsx
git add frontend/src/components/user/UnverifiedVerifyFlow.test.tsx
git commit -m "feat(user): add VerificationCodeDisplay and UnverifiedVerifyFlow components"
git push
```

---

## Task 5 — Profile domain components

**Files:**
- Create: `frontend/src/components/user/VerifiedIdentityCard.tsx`
- Create: `frontend/src/components/user/VerifiedIdentityCard.test.tsx`
- Create: `frontend/src/components/user/ProfilePictureUploader.tsx`
- Create: `frontend/src/components/user/ProfilePictureUploader.test.tsx`
- Create: `frontend/src/components/user/ProfileEditForm.tsx`
- Create: `frontend/src/components/user/ProfileEditForm.test.tsx`
- Create: `frontend/src/components/user/VerifiedOverview.tsx`
- Create: `frontend/src/components/user/VerifiedOverview.test.tsx`

- [ ] **Step 5.1: Write `VerifiedIdentityCard.test.tsx`**

```tsx
import { describe, expect, it, beforeEach, afterEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { mockVerifiedCurrentUser, mockPublicProfile } from "@/test/msw/fixtures";
import { VerifiedIdentityCard } from "./VerifiedIdentityCard";

describe("VerifiedIdentityCard", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-04-15T00:00:00Z"));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("dashboard variant renders SL identity, account age, pay info, verifiedAt", () => {
    render(<VerifiedIdentityCard user={mockVerifiedCurrentUser} variant="dashboard" />);
    expect(screen.getByText("TesterBot Resident")).toBeInTheDocument();
    expect(screen.getByText(/sl member for 15 years/i)).toBeInTheDocument();
    expect(screen.getByText(/payment info used/i)).toBeInTheDocument();
    expect(screen.getByText(/verified on/i)).toBeInTheDocument();
  });

  it("public variant omits pay info and verifiedAt", () => {
    render(<VerifiedIdentityCard user={mockPublicProfile} variant="public" />);
    expect(screen.getByText("TesterBot Resident")).toBeInTheDocument();
    expect(screen.queryByText(/payment info used/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/verified on/i)).not.toBeInTheDocument();
  });

  it("renders '< 12 months' account age in months", () => {
    render(
      <VerifiedIdentityCard
        user={{ ...mockVerifiedCurrentUser, slBornDate: "2025-11-15" }}
        variant="dashboard"
      />
    );
    expect(screen.getByText(/sl member for 5 months/i)).toBeInTheDocument();
  });

  it("omits account age when slBornDate is null", () => {
    render(
      <VerifiedIdentityCard
        user={{ ...mockVerifiedCurrentUser, slBornDate: null }}
        variant="dashboard"
      />
    );
    expect(screen.queryByText(/sl member for/i)).not.toBeInTheDocument();
  });

  it("renders the slDisplayName subtitle when distinct from slAvatarName", () => {
    render(<VerifiedIdentityCard user={mockVerifiedCurrentUser} variant="dashboard" />);
    expect(screen.getByText(/display: testerbot/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 5.2: Create `frontend/src/components/user/VerifiedIdentityCard.tsx`**

```tsx
import { Card } from "@/components/ui/Card";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { BadgeCheck } from "@/components/ui/icons";
import type { CurrentUser, PublicUserProfile } from "@/lib/user";

type DashboardUser = Pick<
  CurrentUser,
  "slAvatarName" | "slUsername" | "slDisplayName" | "slBornDate" | "slPayinfo" | "verifiedAt"
>;

type PublicUser = Pick<
  PublicUserProfile,
  "slAvatarName" | "slUsername" | "slDisplayName"
>;

type VerifiedIdentityCardProps =
  | { user: DashboardUser; variant?: "dashboard" }
  | { user: PublicUser; variant: "public" };

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
  if (totalMonths < 12) {
    return `${totalMonths} month${totalMonths === 1 ? "" : "s"}`;
  }
  const yearStr = Math.floor(totalMonths / 12);
  return `${yearStr} year${yearStr === 1 ? "" : "s"}`;
}

export function VerifiedIdentityCard(props: VerifiedIdentityCardProps) {
  const { user, variant = "dashboard" } = props;

  return (
    <Card className="p-6">
      <div className="flex items-center gap-3 mb-4">
        <BadgeCheck className="size-5 text-primary" aria-label="Verified" />
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
          {"slPayinfo" in user && user.slPayinfo != null && (
            <StatusBadge tone="default">
              {PAY_INFO_LABELS[user.slPayinfo] ?? "Unknown"}
            </StatusBadge>
          )}
          {"verifiedAt" in user && user.verifiedAt && (
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

- [ ] **Step 5.3: Run VerifiedIdentityCard tests to verify green**

```bash
cd frontend && npx vitest run src/components/user/VerifiedIdentityCard.test.tsx
```

Expected: PASS (5 cases).

- [ ] **Step 5.4: Write `ProfilePictureUploader.test.tsx`**

```tsx
import { describe, expect, it, beforeEach, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/render";
import { server } from "@/test/msw/server";
import { userHandlers } from "@/test/msw/handlers";
import { mockVerifiedCurrentUser } from "@/test/msw/fixtures";
import { ProfilePictureUploader } from "./ProfilePictureUploader";

describe("ProfilePictureUploader", () => {
  beforeEach(() => {
    if (!URL.createObjectURL) {
      Object.defineProperty(URL, "createObjectURL", { value: vi.fn(() => "blob:mock"), configurable: true });
      Object.defineProperty(URL, "revokeObjectURL", { value: vi.fn(), configurable: true });
    }
  });

  it("renders the drop zone in idle state", () => {
    renderWithProviders(
      <ProfilePictureUploader user={mockVerifiedCurrentUser} />,
      { auth: "authenticated" }
    );
    expect(screen.getByText(/drop an image here or click to select/i)).toBeInTheDocument();
  });

  it("shows error for unsupported file type", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ProfilePictureUploader user={mockVerifiedCurrentUser} />,
      { auth: "authenticated" }
    );
    const file = new File(["x"], "x.bmp", { type: "image/bmp" });
    const input = screen.getByTestId("avatar-file-input") as HTMLInputElement;
    await user.upload(input, file);
    expect(
      screen.getByText(/file must be a jpeg, png, or webp image/i)
    ).toBeInTheDocument();
  });

  it("shows error for oversized file", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ProfilePictureUploader user={mockVerifiedCurrentUser} />,
      { auth: "authenticated" }
    );
    const big = new File([new Uint8Array(3 * 1024 * 1024)], "big.png", { type: "image/png" });
    const input = screen.getByTestId("avatar-file-input") as HTMLInputElement;
    await user.upload(input, big);
    expect(screen.getByText(/file must be 2mb or less/i)).toBeInTheDocument();
  });

  it("valid file selection transitions to preview state with Save button", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ProfilePictureUploader user={mockVerifiedCurrentUser} />,
      { auth: "authenticated" }
    );
    const file = new File(["x"], "good.png", { type: "image/png" });
    const input = screen.getByTestId("avatar-file-input") as HTMLInputElement;
    await user.upload(input, file);
    expect(screen.getByRole("button", { name: /save/i })).toBeInTheDocument();
    expect(screen.getByAltText(/avatar preview/i)).toBeInTheDocument();
  });

  it("cancel from preview state returns to idle", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ProfilePictureUploader user={mockVerifiedCurrentUser} />,
      { auth: "authenticated" }
    );
    const file = new File(["x"], "good.png", { type: "image/png" });
    await user.upload(screen.getByTestId("avatar-file-input") as HTMLInputElement, file);
    await user.click(screen.getByRole("button", { name: /cancel/i }));
    expect(screen.getByText(/drop an image here or click to select/i)).toBeInTheDocument();
  });

  it("save uploads via MSW and returns to idle on success", async () => {
    server.use(userHandlers.uploadAvatarSuccess());
    const user = userEvent.setup();
    renderWithProviders(
      <ProfilePictureUploader user={mockVerifiedCurrentUser} />,
      { auth: "authenticated" }
    );
    const file = new File(["x"], "good.png", { type: "image/png" });
    await user.upload(screen.getByTestId("avatar-file-input") as HTMLInputElement, file);
    await user.click(screen.getByRole("button", { name: /save/i }));
    await waitFor(() =>
      expect(screen.getByText(/drop an image here or click to select/i)).toBeInTheDocument()
    );
  });
});
```

- [ ] **Step 5.5: Create `frontend/src/components/user/ProfilePictureUploader.tsx`**

```tsx
"use client";

import { useRef, useState } from "react";
import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { cn } from "@/lib/cn";
import { useUploadAvatar } from "@/lib/user";
import type { CurrentUser } from "@/lib/user";

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
      setState({ status: "error", message: "File must be 2MB or less." });
      return;
    }
    const previewUrl = URL.createObjectURL(file);
    setState({ status: "file-selected", file, previewUrl });
  };

  const handleSave = () => {
    if (state.status !== "file-selected") return;
    const { file, previewUrl } = state;
    setState({ status: "uploading", file, previewUrl });
    uploadMutation.mutate(file, {
      onSuccess: () => {
        URL.revokeObjectURL(previewUrl);
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
      <Avatar
        src={user.profilePicUrl ?? undefined}
        alt="Your profile picture"
        size="xl"
        cacheBust={user.updatedAt}
      />

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
            data-testid="avatar-file-input"
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

- [ ] **Step 5.6: Run ProfilePictureUploader tests**

```bash
cd frontend && npx vitest run src/components/user/ProfilePictureUploader.test.tsx
```

Expected: PASS (6 cases).

- [ ] **Step 5.7: Write `ProfileEditForm.test.tsx`**

```tsx
import { describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/render";
import { server } from "@/test/msw/server";
import { userHandlers } from "@/test/msw/handlers";
import { mockVerifiedCurrentUser } from "@/test/msw/fixtures";
import { ProfileEditForm } from "./ProfileEditForm";

describe("ProfileEditForm", () => {
  it("is pre-filled with user's displayName and bio", () => {
    renderWithProviders(
      <ProfileEditForm user={mockVerifiedCurrentUser} />,
      { auth: "authenticated" }
    );
    expect(screen.getByLabelText(/display name/i)).toHaveValue("Verified Tester");
    expect(screen.getByLabelText(/bio/i)).toHaveValue("Auction enthusiast");
  });

  it("shows a validation error for empty displayName", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ProfileEditForm user={mockVerifiedCurrentUser} />,
      { auth: "authenticated" }
    );
    await user.clear(screen.getByLabelText(/display name/i));
    await user.click(screen.getByRole("button", { name: /save changes/i }));
    expect(await screen.findByText(/display name is required/i)).toBeInTheDocument();
  });

  it("shows a validation error for 51-character displayName", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ProfileEditForm user={mockVerifiedCurrentUser} />,
      { auth: "authenticated" }
    );
    const input = screen.getByLabelText(/display name/i);
    await user.clear(input);
    await user.type(input, "x".repeat(51));
    await user.click(screen.getByRole("button", { name: /save changes/i }));
    expect(await screen.findByText(/50 characters or less/i)).toBeInTheDocument();
  });

  it("shows a validation error for whitespace-only displayName", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ProfileEditForm user={mockVerifiedCurrentUser} />,
      { auth: "authenticated" }
    );
    const input = screen.getByLabelText(/display name/i);
    await user.clear(input);
    await user.type(input, "   ");
    await user.click(screen.getByRole("button", { name: /save changes/i }));
    expect(await screen.findByText(/cannot be only whitespace/i)).toBeInTheDocument();
  });

  it("submits valid input and resets dirty state on success", async () => {
    server.use(userHandlers.updateMeSuccess());
    const user = userEvent.setup();
    renderWithProviders(
      <ProfileEditForm user={mockVerifiedCurrentUser} />,
      { auth: "authenticated" }
    );
    const input = screen.getByLabelText(/display name/i);
    await user.clear(input);
    await user.type(input, "New Name");
    await user.click(screen.getByRole("button", { name: /save changes/i }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /save changes/i })).toBeDisabled()
    );
  });

  it("Save button is disabled when form is not dirty", () => {
    renderWithProviders(
      <ProfileEditForm user={mockVerifiedCurrentUser} />,
      { auth: "authenticated" }
    );
    expect(screen.getByRole("button", { name: /save changes/i })).toBeDisabled();
  });
});
```

- [ ] **Step 5.8: Create `frontend/src/components/user/ProfileEditForm.tsx`**

```tsx
"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { Button } from "@/components/ui/Button";
import { FormError } from "@/components/ui/FormError";
import { Input } from "@/components/ui/Input";
import { useUpdateProfile } from "@/lib/user";
import type { CurrentUser } from "@/lib/user";

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

- [ ] **Step 5.9: Run ProfileEditForm tests**

```bash
cd frontend && npx vitest run src/components/user/ProfileEditForm.test.tsx
```

Expected: PASS (6 cases).

- [ ] **Step 5.10: Write `VerifiedOverview.test.tsx`**

```tsx
import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders } from "@/test/render";
import { server } from "@/test/msw/server";
import { userHandlers } from "@/test/msw/handlers";
import { VerifiedOverview } from "./VerifiedOverview";

describe("VerifiedOverview", () => {
  it("renders identity card, uploader, and edit form when /me resolves", async () => {
    server.use(userHandlers.meVerified());
    renderWithProviders(<VerifiedOverview />, { auth: "authenticated" });
    await waitFor(() =>
      expect(screen.getByText(/verified second life identity/i)).toBeInTheDocument()
    );
    expect(screen.getByLabelText(/display name/i)).toBeInTheDocument();
    expect(screen.getByText(/drop an image here/i)).toBeInTheDocument();
  });

  it("shows loading spinner while /me is pending", () => {
    server.use(userHandlers.meVerified());
    renderWithProviders(<VerifiedOverview />, { auth: "authenticated" });
    expect(screen.getByRole("status")).toBeInTheDocument();
  });
});
```

- [ ] **Step 5.11: Create `frontend/src/components/user/VerifiedOverview.tsx`**

```tsx
"use client";

import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { ProfileEditForm } from "@/components/user/ProfileEditForm";
import { ProfilePictureUploader } from "@/components/user/ProfilePictureUploader";
import { VerifiedIdentityCard } from "@/components/user/VerifiedIdentityCard";
import { useCurrentUser } from "@/lib/user";

export function VerifiedOverview() {
  const { data: user, isPending } = useCurrentUser();

  if (isPending || !user) {
    return <LoadingSpinner label="Loading profile..." />;
  }

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

- [ ] **Step 5.12: Run VerifiedOverview tests**

```bash
cd frontend && npx vitest run src/components/user/VerifiedOverview.test.tsx
```

Expected: PASS (2 cases).

- [ ] **Step 5.13: Run full frontend suite**

```bash
cd frontend && npm test -- --run
```

Expected: ~247 tests passing (228 + 5 VerifiedIdentityCard + 6 ProfilePictureUploader + 6 ProfileEditForm + 2 VerifiedOverview).

- [ ] **Step 5.14: Commit Task 5**

```bash
git add frontend/src/components/user/VerifiedIdentityCard.tsx
git add frontend/src/components/user/VerifiedIdentityCard.test.tsx
git add frontend/src/components/user/ProfilePictureUploader.tsx
git add frontend/src/components/user/ProfilePictureUploader.test.tsx
git add frontend/src/components/user/ProfileEditForm.tsx
git add frontend/src/components/user/ProfileEditForm.test.tsx
git add frontend/src/components/user/VerifiedOverview.tsx
git add frontend/src/components/user/VerifiedOverview.test.tsx
git commit -m "feat(user): add ProfilePictureUploader, VerifiedIdentityCard, ProfileEditForm, VerifiedOverview"
git push
```

---

## Task 6 — Dashboard routes

**Files:**
- Replace: `frontend/src/app/dashboard/layout.tsx`
- Replace: `frontend/src/app/dashboard/page.tsx`
- Create: `frontend/src/app/dashboard/page.test.tsx`
- Create: `frontend/src/app/dashboard/verify/page.tsx`
- Create: `frontend/src/app/dashboard/(verified)/layout.tsx`
- Create: `frontend/src/app/dashboard/(verified)/layout.test.tsx`
- Create: `frontend/src/app/dashboard/(verified)/overview/page.tsx`
- Create: `frontend/src/app/dashboard/(verified)/bids/page.tsx`
- Create: `frontend/src/app/dashboard/(verified)/listings/page.tsx`

- [ ] **Step 6.1: Inspect the existing `dashboard/page.tsx` stub before replacing**

Read `frontend/src/app/dashboard/page.tsx` to understand what's currently there (likely a 36-line Epic 01 stub). Confirm it is a stub, not something that must be preserved.

- [ ] **Step 6.2: Create `dashboard/layout.tsx` with RequireAuth wrapper**

Create `frontend/src/app/dashboard/layout.tsx`:

```tsx
import { RequireAuth } from "@/components/auth/RequireAuth";

export default function DashboardLayout({ children }: { children: React.ReactNode }) {
  return <RequireAuth>{children}</RequireAuth>;
}
```

Note: verify `RequireAuth` exists at `frontend/src/components/auth/RequireAuth.tsx` and accepts `children`. If the existing component has a different shape (e.g., `fallback` prop), match it.

- [ ] **Step 6.3: Create `dashboard/page.tsx` — thin redirect based on verification status**

```tsx
"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { useCurrentUser } from "@/lib/user";
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

  return <LoadingSpinner label="Loading your dashboard..." />;
}
```

- [ ] **Step 6.4: Create `dashboard/page.test.tsx`**

```tsx
import { describe, expect, it, vi, beforeEach } from "vitest";
import { waitFor } from "@testing-library/react";
import { renderWithProviders } from "@/test/render";
import { server } from "@/test/msw/server";
import { userHandlers } from "@/test/msw/handlers";
import DashboardIndex from "./page";

const replace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn(), back: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/dashboard",
}));

describe("DashboardIndex", () => {
  beforeEach(() => {
    replace.mockReset();
  });

  it("redirects verified users to /dashboard/overview", async () => {
    server.use(userHandlers.meVerified());
    renderWithProviders(<DashboardIndex />, { auth: "authenticated" });
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/dashboard/overview"));
  });

  it("redirects unverified users to /dashboard/verify", async () => {
    server.use(userHandlers.meUnverified());
    renderWithProviders(<DashboardIndex />, { auth: "authenticated" });
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/dashboard/verify"));
  });
});
```

- [ ] **Step 6.5: Create `dashboard/verify/page.tsx`**

```tsx
"use client";

import { UnverifiedVerifyFlow } from "@/components/user/UnverifiedVerifyFlow";

export default function VerifyPage() {
  return (
    <div className="mx-auto max-w-5xl px-4 py-12">
      <h1 className="text-headline-md font-display font-bold text-center mb-8">
        Verify Your Second Life Avatar
      </h1>
      <UnverifiedVerifyFlow />
    </div>
  );
}
```

- [ ] **Step 6.6: Create `dashboard/(verified)/layout.tsx` — tab rail + gate redirect**

```tsx
"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { Tabs, type TabItem } from "@/components/ui/Tabs";
import { useCurrentUser } from "@/lib/user";

const TABS: TabItem[] = [
  { id: "overview", label: "Overview", href: "/dashboard/overview" },
  { id: "bids", label: "My Bids", href: "/dashboard/bids" },
  { id: "listings", label: "My Listings", href: "/dashboard/listings" },
];

export default function VerifiedDashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const router = useRouter();
  const { data: user, isPending, isError } = useCurrentUser();

  useEffect(() => {
    if (isPending || isError) return;
    if (!user?.verified) {
      router.replace("/dashboard/verify");
    }
  }, [isPending, isError, user?.verified, router]);

  if (isPending || !user) {
    return <LoadingSpinner label="Loading your dashboard..." />;
  }

  if (!user.verified) {
    return <LoadingSpinner label="Redirecting..." />;
  }

  return (
    <div className="mx-auto max-w-6xl px-4 py-8">
      <h1 className="text-headline-md font-display font-bold mb-6">Dashboard</h1>
      <Tabs tabs={TABS} className="mb-8" />
      {children}
    </div>
  );
}
```

- [ ] **Step 6.7: Create `dashboard/(verified)/layout.test.tsx`**

```tsx
import { describe, expect, it, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders } from "@/test/render";
import { server } from "@/test/msw/server";
import { userHandlers } from "@/test/msw/handlers";
import VerifiedDashboardLayout from "./layout";

const replace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn(), back: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/dashboard/overview",
}));

describe("(verified) layout gate", () => {
  beforeEach(() => {
    replace.mockReset();
  });

  it("renders children with tab rail when user is verified", async () => {
    server.use(userHandlers.meVerified());
    renderWithProviders(
      <VerifiedDashboardLayout>
        <div>child content</div>
      </VerifiedDashboardLayout>,
      { auth: "authenticated" }
    );
    await waitFor(() => expect(screen.getByText(/child content/i)).toBeInTheDocument());
    expect(screen.getByRole("tablist", { name: /dashboard sections/i })).toBeInTheDocument();
  });

  it("redirects unverified users to /dashboard/verify", async () => {
    server.use(userHandlers.meUnverified());
    renderWithProviders(
      <VerifiedDashboardLayout>
        <div>child content</div>
      </VerifiedDashboardLayout>,
      { auth: "authenticated" }
    );
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/dashboard/verify"));
  });
});
```

- [ ] **Step 6.8: Create `dashboard/(verified)/overview/page.tsx`**

```tsx
"use client";

import { VerifiedOverview } from "@/components/user/VerifiedOverview";

export default function OverviewPage() {
  return <VerifiedOverview />;
}
```

- [ ] **Step 6.9: Create `dashboard/(verified)/bids/page.tsx`**

```tsx
import { EmptyState } from "@/components/ui/EmptyState";
import { Gavel } from "@/components/ui/icons";

export default function BidsPage() {
  return (
    <EmptyState
      icon={Gavel}
      headline="No bids yet"
      description="Your active and historical bids will appear here once auctions go live."
    />
  );
}
```

- [ ] **Step 6.10: Create `dashboard/(verified)/listings/page.tsx`**

```tsx
import { EmptyState } from "@/components/ui/EmptyState";
import { ListChecks } from "@/components/ui/icons";

export default function ListingsPage() {
  return (
    <EmptyState
      icon={ListChecks}
      headline="No listings yet"
      description="Parcels you put up for auction will appear here."
    />
  );
}
```

- [ ] **Step 6.11: Run the new route tests**

```bash
cd frontend && npx vitest run src/app/dashboard
```

Expected: PASS (4 cases — 2 index + 2 layout).

- [ ] **Step 6.12: Run full frontend suite + lint + build**

```bash
cd frontend && npm test -- --run
```

Expected: ~251 tests passing (247 + 4 new route tests).

```bash
npm run lint
```

Expected: clean.

```bash
npm run build
```

Expected: Next.js 16 production build succeeds. Any type errors on the new route files will surface here.

- [ ] **Step 6.13: Commit Task 6**

```bash
git add frontend/src/app/dashboard/
git commit -m "feat(app): add dashboard routes with verification gate and tabs"
git push
```

---

## Task 7 — Public profile page + reputation components

**Files:**
- Create: `frontend/src/components/user/ReputationStars.tsx`
- Create: `frontend/src/components/user/ReputationStars.test.tsx`
- Create: `frontend/src/components/user/NewSellerBadge.tsx`
- Create: `frontend/src/components/user/NewSellerBadge.test.tsx`
- Create: `frontend/src/components/user/PublicProfileView.tsx`
- Create: `frontend/src/components/user/PublicProfileView.test.tsx`
- Create: `frontend/src/app/users/[id]/page.tsx`

- [ ] **Step 7.1: Write `ReputationStars.test.tsx`**

```tsx
import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { ReputationStars } from "./ReputationStars";

describe("ReputationStars", () => {
  it("renders 'No ratings yet' when rating is null", () => {
    render(<ReputationStars rating={null} reviewCount={0} label="Seller Rating" />);
    expect(screen.getByText(/no ratings yet/i)).toBeInTheDocument();
  });

  it("renders numeric rating and plural review count", () => {
    render(<ReputationStars rating={4.7} reviewCount={12} label="Seller" />);
    expect(screen.getByText("4.7")).toBeInTheDocument();
    expect(screen.getByText(/12 reviews/i)).toBeInTheDocument();
  });

  it("renders singular review count when reviewCount is 1", () => {
    render(<ReputationStars rating={5} reviewCount={1} />);
    expect(screen.getByText(/1 review/i)).toBeInTheDocument();
    expect(screen.queryByText(/reviews/i)).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 7.2: Create `frontend/src/components/user/ReputationStars.tsx`**

```tsx
import { Star } from "@/components/ui/icons";

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
          <Star className="size-5 fill-primary text-primary" strokeWidth={1.5} />
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

- [ ] **Step 7.3: Run ReputationStars tests**

```bash
cd frontend && npx vitest run src/components/user/ReputationStars.test.tsx
```

Expected: PASS (3 cases).

- [ ] **Step 7.4: Write `NewSellerBadge.test.tsx`**

```tsx
import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { NewSellerBadge } from "./NewSellerBadge";

describe("NewSellerBadge", () => {
  it("renders when completedSales < 3", () => {
    render(<NewSellerBadge completedSales={0} />);
    expect(screen.getByText(/new seller/i)).toBeInTheDocument();
  });

  it("renders null when completedSales >= 3", () => {
    const { container } = render(<NewSellerBadge completedSales={3} />);
    expect(container.firstChild).toBeNull();
  });
});
```

- [ ] **Step 7.5: Create `frontend/src/components/user/NewSellerBadge.tsx`**

```tsx
import { StatusBadge } from "@/components/ui/StatusBadge";

type NewSellerBadgeProps = { completedSales: number };

export function NewSellerBadge({ completedSales }: NewSellerBadgeProps) {
  if (completedSales >= 3) return null;
  return <StatusBadge tone="warning">New Seller</StatusBadge>;
}
```

- [ ] **Step 7.6: Run NewSellerBadge tests**

```bash
cd frontend && npx vitest run src/components/user/NewSellerBadge.test.tsx
```

Expected: PASS (2 cases).

- [ ] **Step 7.7: Write `PublicProfileView.test.tsx`**

```tsx
import { describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders } from "@/test/render";
import { server } from "@/test/msw/server";
import { userHandlers } from "@/test/msw/handlers";
import {
  mockPublicProfile,
  mockUnverifiedPublicProfile,
  mockNewSellerPublicProfile,
} from "@/test/msw/fixtures";
import { PublicProfileView } from "./PublicProfileView";

const notFoundSpy = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/users/42",
  notFound: () => notFoundSpy(),
}));

describe("PublicProfileView", () => {
  it("renders a verified user with the SL identity card", async () => {
    server.use(userHandlers.publicProfileSuccess(mockPublicProfile));
    renderWithProviders(<PublicProfileView userId={42} />);
    await waitFor(() => expect(screen.getByText(/verified tester/i)).toBeInTheDocument());
    expect(screen.getByText(/testerbot resident/i)).toBeInTheDocument();
    expect(screen.getByText(/verified/i)).toBeInTheDocument();
  });

  it("renders an unverified user with the 'Unverified' chip and no SL identity", async () => {
    server.use(userHandlers.publicProfileSuccess(mockUnverifiedPublicProfile));
    renderWithProviders(<PublicProfileView userId={44} />);
    await waitFor(() => expect(screen.getByText(/unverified tester/i)).toBeInTheDocument());
    expect(screen.getByText(/unverified/i)).toBeInTheDocument();
    expect(screen.queryByText(/testerbot resident/i)).not.toBeInTheDocument();
  });

  it("calls notFound() when the API returns 404", async () => {
    server.use(userHandlers.publicProfileNotFound());
    renderWithProviders(<PublicProfileView userId={999} />);
    await waitFor(() => expect(notFoundSpy).toHaveBeenCalled());
  });

  it("renders NewSellerBadge for a new seller", async () => {
    server.use(userHandlers.publicProfileSuccess(mockNewSellerPublicProfile));
    renderWithProviders(<PublicProfileView userId={43} />);
    await waitFor(() => expect(screen.getByText(/new seller/i)).toBeInTheDocument());
  });

  it("omits NewSellerBadge for an established seller", async () => {
    server.use(userHandlers.publicProfileSuccess(mockPublicProfile));
    renderWithProviders(<PublicProfileView userId={42} />);
    await waitFor(() => expect(screen.getByText(/verified tester/i)).toBeInTheDocument());
    expect(screen.queryByText(/new seller/i)).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 7.8: Create `frontend/src/components/user/PublicProfileView.tsx`**

```tsx
"use client";

import { notFound } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { Avatar } from "@/components/ui/Avatar";
import { Card } from "@/components/ui/Card";
import { EmptyState } from "@/components/ui/EmptyState";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { AlertCircle, BadgeCheck, Gavel, MessageSquare } from "@/components/ui/icons";
import { NewSellerBadge } from "@/components/user/NewSellerBadge";
import { ReputationStars } from "@/components/user/ReputationStars";
import { userApi } from "@/lib/user";
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
        icon={AlertCircle}
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
              <StatusBadge tone="success">
                <BadgeCheck className="size-4" aria-hidden="true" />
                Verified
              </StatusBadge>
            ) : (
              <StatusBadge tone="default">Unverified</StatusBadge>
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

      <Card className="p-6">
        <h2 className="text-title-md font-bold mb-4">Recent Reviews</h2>
        <EmptyState
          icon={MessageSquare}
          headline="No reviews yet"
          description="Reviews will appear here after this user completes their first auction."
        />
      </Card>

      <Card className="p-6">
        <h2 className="text-title-md font-bold mb-4">Active Listings</h2>
        <EmptyState
          icon={Gavel}
          headline="No active listings"
          description="Listings will appear here when this user puts a parcel up for auction."
        />
      </Card>
    </div>
  );
}
```

- [ ] **Step 7.9: Run PublicProfileView tests**

```bash
cd frontend && npx vitest run src/components/user/PublicProfileView.test.tsx
```

Expected: PASS (5 cases).

- [ ] **Step 7.10: Create `frontend/src/app/users/[id]/page.tsx`**

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

- [ ] **Step 7.11: Run the full frontend test suite + lint + build**

```bash
cd frontend && npm test -- --run
```

Expected: ~261 tests passing (251 + 3 ReputationStars + 2 NewSellerBadge + 5 PublicProfileView).

```bash
npm run lint && npm run build
```

Expected: clean lint, clean build.

- [ ] **Step 7.12: Commit Task 7**

```bash
git add frontend/src/components/user/ReputationStars.tsx
git add frontend/src/components/user/ReputationStars.test.tsx
git add frontend/src/components/user/NewSellerBadge.tsx
git add frontend/src/components/user/NewSellerBadge.test.tsx
git add frontend/src/components/user/PublicProfileView.tsx
git add frontend/src/components/user/PublicProfileView.test.tsx
git add frontend/src/app/users/
git commit -m "feat(app): add public profile page at /users/[id]"
git push
```

---

## Task 8 — Final polish + integration smoke + README + FOOTGUNS + PR

**Files:**
- Create: `frontend/src/test/integration/dashboard-verify-flow.test.tsx`
- Modify: `README.md`
- Modify: `docs/implementation/FOOTGUNS.md`

- [ ] **Step 8.1: Create the integration smoke test**

Create `frontend/src/test/integration/dashboard-verify-flow.test.tsx`:

```tsx
import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { screen, waitFor, act } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/render";
import { server } from "@/test/msw/server";
import { userHandlers, verificationHandlers } from "@/test/msw/handlers";
import { UnverifiedVerifyFlow } from "@/components/user/UnverifiedVerifyFlow";

const replace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn(), back: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/dashboard/verify",
}));

describe("Dashboard verify -> overview transition (integration smoke)", () => {
  beforeEach(() => {
    replace.mockReset();
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText: vi.fn(() => Promise.resolve()) },
      configurable: true,
    });
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("user generates a code, simulates verification, and transitions to overview", async () => {
    server.use(
      userHandlers.meUnverified(),
      verificationHandlers.activeNone()
    );
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderWithProviders(<UnverifiedVerifyFlow />, { auth: "authenticated" });

    await screen.findByText(/to bid, list parcels/i);
    await screen.findByRole("button", { name: /generate verification code/i });

    server.use(verificationHandlers.generateSuccess("654321", "2026-04-14T21:15:00Z"));
    await user.click(screen.getByRole("button", { name: /generate verification code/i }));

    server.use(verificationHandlers.activeExists("654321", "2026-04-14T21:15:00Z"));
    expect(await screen.findByText("654321")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /copy code/i }));

    server.use(userHandlers.meVerified());
    await act(async () => {
      vi.advanceTimersByTime(5100);
    });
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/dashboard/overview"));
  });
});
```

- [ ] **Step 8.2: Run the integration test**

```bash
cd frontend && npx vitest run src/test/integration/dashboard-verify-flow.test.tsx
```

Expected: PASS (1 case).

- [ ] **Step 8.3: Run the full verify chain**

```bash
cd frontend && npm test -- --run && npm run lint && npm run build
```

Expected: ~262 tests passing, clean lint, clean build.

```bash
cd ../backend && ./mvnw test -q
```

Expected: `BUILD SUCCESS`, ~190 tests.

- [ ] **Step 8.4: Manual browser smoke end-to-end**

Start all services:

```bash
cd C:/Users/heath/Repos/Personal/slpa
docker compose up -d
```

Run the frontend dev server:

```bash
cd frontend && npm run dev
```

Open http://localhost:3000 and walk the 13-step manual smoke from §16 of the spec:

1. Register a new user at `/register`
2. Land on `/dashboard` → redirects to `/dashboard/verify`
3. Click "Generate Verification Code" → 6-digit code + countdown
4. Click copy icon → toast slides in
5. Postman → `Dev/Simulate SL verify` with captured `verificationCode`
6. Wait ≤5s OR click manual refresh
7. Dashboard transitions to `/dashboard/overview`
8. `VerifiedIdentityCard` displays SL identity
9. Navigate `/dashboard/bids` → empty state
10. Navigate `/dashboard/listings` → empty state
11. Return to Overview → upload avatar via drag-drop → preview → Save → avatar updates (cache-busted via `updatedAt`)
12. Edit displayName + bio → Save → toast confirms → `/me` reflects
13. Incognito → `/users/{id}` → verified profile + reputation + empty-state sections

If any step fails, record it in a new FOOTGUNS entry in Step 8.5 before opening the PR.

- [ ] **Step 8.5: README sweep**

Open `README.md` at the repo root and update:
- Mention the new dashboard routes (`/dashboard/verify`, `/dashboard/overview`, `/dashboard/bids`, `/dashboard/listings`) and `/users/[id]`
- Mention the new domain components in `components/user/` (short list — don't enumerate every file)
- Mention the Toast primitive as a new reusable UI building block
- Bump any test-count references (backend ~190, frontend ~262)
- Update the Epic 02 status line: sub-specs 1, 2a, 2b all merged to `dev`

- [ ] **Step 8.6: FOOTGUNS entries**

Open `docs/implementation/FOOTGUNS.md` and append new entries as needed. Candidate footguns to write if they bit you during implementation:

- **F.xx: Next.js 16 route groups — `(verified)/` doesn't appear in URL, gate lives in its `layout.tsx`.** Describe how adding a new verified-only tab is a drop-in under `(verified)/`, and the critical gotcha that `router.replace` in a `useEffect` inside the group layout is the redirect point, not the page.
- **F.xx: TanStack Query `refetchInterval` + `refetchIntervalInBackground: false`.** Document that polling pauses when `document.visibilityState === "hidden"` and resumes on tab focus via `refetchOnWindowFocus: true`. Also document that the interval does NOT fire while the component is suspended (e.g., behind a route transition).
- **F.xx: `<Image cacheBust={user.updatedAt}>` needs the backend to project `updatedAt`.** Why the sub-spec 2b backend touch-up is load-bearing and cannot be skipped.
- **F.xx: MSW 2 multipart handler — do NOT try to parse `FormData` in Node-side MSW handlers** (undici's FormData parser has known edge cases). Accept the POST and return the expected response body; don't assert file content server-side.
- **F.xx: Toast portal hydration guard.** `createPortal(..., document.body)` needs the `mounted` state flip in a `useEffect` to avoid SSR errors — match the `ThemeToggle.tsx` pattern.
- **F.xx: JSDOM drag-drop in Vitest** — native DragEvent and DataTransfer do not round-trip through jsdom; prefer testing the file input change path directly for ProfilePictureUploader tests. Drag-drop integration belongs in the manual browser smoke.

Use the next available F.xx number (check the file for the current max).

- [ ] **Step 8.7: Commit Task 8 docs + integration test**

```bash
git add frontend/src/test/integration/
git add README.md
git add docs/implementation/FOOTGUNS.md
git commit -m "docs: README sweep and FOOTGUNS entries for Epic 02 sub-spec 2b"
git push
```

- [ ] **Step 8.8: Open the PR into `dev`**

```bash
gh pr create --base dev --title "Epic 02 sub-spec 2b: dashboard UI + public profile" --body "$(cat <<'EOF'
## Summary
- Ship the frontend half of Epic 02: tabbed dashboard with full-page verification takeover for unverified users, plus `/users/[id]` public profile.
- Consume the sub-spec 2a backend endpoints; add one backend touch-up (`updatedAt` on `UserResponse`) for avatar cache-busting.
- Add 6 new UI primitives (Tabs, CountdownTimer, CodeDisplay, EmptyState, LoadingSpinner, Toast), 9 domain composites under `components/user/`, and a new `lib/user/` data layer with TanStack Query hooks.

## Test plan
- [ ] `./mvnw test` BUILD SUCCESS (~190 backend tests)
- [ ] `npm test -- --run` BUILD SUCCESS (~262 frontend tests)
- [ ] `npm run lint` clean
- [ ] `npm run build` clean
- [ ] Manual browser smoke: register → verify → simulate → overview → upload → edit → public profile (13-step walkthrough from §16 of the spec)
- [ ] Incognito `/users/{id}` renders verified profile with reputation placeholders

Spec: `docs/superpowers/specs/2026-04-14-epic-02-sub-2b-dashboard-public-profile.md`
Plan: `docs/superpowers/plans/2026-04-14-epic-02-sub-2b-dashboard-public-profile.md`
EOF
)"
```

- [ ] **Step 8.9: Return to `dev` locally**

```bash
git checkout dev
git pull origin dev
```

The branch `task/02-sub-2b-dashboard-public-profile` remains on origin until the PR merges.

---

## Done definition

Sub-spec 2b is done when all of the following are true:

- [ ] All 8 tasks committed and pushed in order per §15 of the spec
- [ ] `./mvnw test` → BUILD SUCCESS at ~190 tests
- [ ] `cd frontend && npm test -- --run` → BUILD SUCCESS at ~262 tests
- [ ] `npm run lint` → clean
- [ ] `npm run build` → production build passes
- [ ] `docker compose up` stands up all 5 services healthy
- [ ] Manual browser smoke end-to-end passes (13-step walk from spec §16)
- [ ] `README.md` swept
- [ ] `docs/implementation/FOOTGUNS.md` updated with new entries
- [ ] PR into `dev` opened (not `main`)
- [ ] No AI/tool attribution anywhere (commits, PR title, PR body)
- [ ] Local branch is `dev` after the PR opens
