# Task 01-08 — Frontend Authentication Pages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the three auth pages (`/register`, `/login`, `/forgot-password`) plus the `/dashboard` placeholder, wire the frontend's `useAuth()` hook to the merged Task 01-07 backend, replace the Task 01-06 placeholder pages with real forms backed by `react-hook-form` + `zod`, add MSW test infrastructure for realistic HTTP mocking, and update `Header` to render logged-in vs logged-out states.

**Architecture:** Auth state lives in TanStack Query as a single `["auth", "session"]` query keyed off the result of `POST /api/auth/refresh`. The access token is a module-level mutable ref in `lib/auth/session.ts` (not a React ref — the API client interceptor runs outside React), written only inside `queryFn` and mutation `onSuccess` callbacks, and read by the API client when composing `Authorization: Bearer` headers. Forms are `react-hook-form` + `zod` compositions inside a compound `AuthCard` layout that all three pages share; submission flows through `useLogin` / `useRegister` / `useForgotPassword` mutations that update the session cache on success and map `ProblemDetail` responses to inline field errors via a shared `mapProblemDetailToForm` helper.

**Tech Stack:** Next.js 16.2 (App Router) / React 19 / TypeScript 5 / Tailwind CSS 4 / TanStack Query v5 (existing) / **react-hook-form 7** (new) / **@hookform/resolvers 3** (new) / **zod 3** (new) / **MSW 2** (new dev dep) / Vitest 4 + RTL 16 + jsdom 29 (existing)

**Spec:** [`docs/superpowers/specs/2026-04-12-task-01-08-frontend-auth-design.md`](../specs/2026-04-12-task-01-08-frontend-auth-design.md)

---

## File structure

### New files (~25 production + ~3 test infrastructure)

**Production (`frontend/src/`):**

| Path | Purpose |
|---|---|
| `lib/auth/session.ts` | Module-level access token ref, `AuthUser`/`AuthSession` types |
| `lib/auth/api.ts` | Typed wrappers for the 5 auth endpoints |
| `lib/auth/hooks.ts` | `useAuth`, `useLogin`, `useRegister`, `useLogout`, `useLogoutAll`, `useForgotPassword` |
| `lib/auth/schemas.ts` | Shared zod schemas: `emailSchema`, `passwordCreateSchema`, `passwordInputSchema`, `registerSchema`, `loginSchema`, `forgotPasswordSchema` |
| `lib/auth/errors.ts` | `mapProblemDetailToForm` helper |
| `lib/auth/passwordStrength.ts` | Hand-rolled strength algorithm |
| `lib/auth/redirects.ts` | `getSafeRedirect` open-redirect guard |
| `lib/auth/index.ts` | Public barrel — re-exports `useAuth`, `AuthSession`, `AuthUser`, hooks |
| `components/ui/Checkbox.tsx` | Styled native checkbox primitive |
| `components/ui/FormError.tsx` | Generic form-level error display |
| `components/ui/PasswordStrengthIndicator.tsx` | 4-segment bar visualization |
| `components/auth/AuthCard.tsx` | Compound layout: `.Title`, `.Subtitle`, `.Body`, `.Footer` |
| `components/auth/RequireAuth.tsx` | Client-side guard for protected pages |
| `components/auth/RegisterForm.tsx` | Register form |
| `components/auth/LoginForm.tsx` | Login form |
| `components/auth/ForgotPasswordForm.tsx` | Forgot-password form + success state |
| `components/auth/UserMenuDropdown.tsx` | Header's authenticated branch |

**Test infrastructure (`frontend/src/test/msw/`):**

| Path | Purpose |
|---|---|
| `test/msw/server.ts` | `setupServer()` instance |
| `test/msw/handlers.ts` | Named handlers for every auth endpoint |
| `test/msw/fixtures.ts` | `mockUser`, `mockAuthResponse` |

### Modified files (~9)

| Path | Change |
|---|---|
| `frontend/package.json` | Add `react-hook-form`, `@hookform/resolvers`, `zod`, `msw` |
| `frontend/vitest.setup.ts` | Add MSW lifecycle hooks (`beforeAll` / `afterEach` / `afterAll`) with `onUnhandledRequest: "error"` |
| `frontend/src/lib/api.ts` | Add `configureApiClient`, 401 interceptor, stampede protection |
| `frontend/src/lib/auth.ts` | **DELETED** — replaced by `lib/auth/` directory |
| `frontend/src/app/providers.tsx` | Call `configureApiClient(queryClient)` once on mount |
| `frontend/src/components/layout/Header.tsx` | Wire authenticated branch to `<UserMenuDropdown />` |
| `frontend/src/app/register/page.tsx` | Replace RSC placeholder with real form |
| `frontend/src/app/login/page.tsx` | Replace RSC placeholder with real form |
| `frontend/src/app/forgot-password/page.tsx` | Replace RSC placeholder with real form |
| `frontend/src/app/dashboard/page.tsx` | Replace RSC placeholder with `<RequireAuth>` + minimal content |
| `docs/implementation/FOOTGUNS.md` | Add `§5.9` stale-brief meta-lesson + `§F.1`–`§F.13` frontend section |
| `README.md` | Document frontend auth pages and the MSW test infrastructure |

---

## Phases overview

| Phase | Name | Tasks | What lands |
|---|---|---|---|
| A | Dependencies & MSW infrastructure | 1–4 | npm deps, MSW server + handlers + fixtures, vitest setup |
| B | Auth library — pure modules | 5–9 | session.ts (replaces auth.ts), passwordStrength, redirects, schemas, errors |
| C | API client wiring | 10–12 | `configureApiClient`, 401 interceptor + stampede protection, **canary test** |
| D | Auth hooks + barrel | 13–17 | `lib/auth/api.ts`, hooks, public barrel, providers.tsx wire |
| E | New UI primitives | 18–20 | `FormError`, `Checkbox`, `PasswordStrengthIndicator` |
| F | AuthCard layout | 21 | Compound component |
| G | Form components | 22–24 | `RegisterForm`, `LoginForm`, `ForgotPasswordForm` |
| H | Pages + RequireAuth | 25–29 | `RequireAuth`, four page rewrites |
| I | Header authenticated state | 30–31 | `UserMenuDropdown`, Header wire |
| J | Finalization | 32–35 | FOOTGUNS sweep, README sweep, final verify, PR |

**Convention recap** (applies to every task): single atomic commit, conventional-commits format with `feat(auth):` / `chore(auth):` / `test(auth):` / `docs(auth):` scope as appropriate, no AI attribution, no `--no-verify`, `npm run verify` passes before every commit (the existing four-rule chain from Task 01-06 + the new test suite). The worktree is at `C:\Users\heath\Repos\Personal\slpa-task-01-08` on branch `task/01-08-frontend-auth`.

**Dispatch model:** Task 01-08 follows the same B-mode pattern as Task 01-07 — substantive tasks (especially the canary test, hooks, and form components) get the spec compliance + code quality review pair; mechanical tasks (deps install, barrel files, page rewrites that are pure composition) skip the review pair.

---

## Phase A — Dependencies & MSW infrastructure

### Task 1: Install npm dependencies

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json` (auto)

- [ ] **Step 1: Install the four packages**

```bash
cd frontend
npm install react-hook-form@^7.53.0 @hookform/resolvers@^3.9.0 zod@^3.23.8
npm install --save-dev msw@^2.6.0
```

`react-hook-form`, `@hookform/resolvers`, and `zod` are runtime dependencies (they ship in the production bundle). `msw` is a dev dependency (test infrastructure only).

- [ ] **Step 2: Verify install succeeded and the lockfile updated**

```bash
cd frontend
npm ls react-hook-form @hookform/resolvers zod msw
```

Expected: all four packages listed at their installed versions, no `MISSING` or `extraneous` warnings.

- [ ] **Step 3: Run the existing test suite to confirm baseline**

```bash
cd frontend
npm run test
```

Expected: all 67 existing tests pass. The new dependencies are not yet imported anywhere, so this is purely a regression check that the install didn't break anything.

- [ ] **Step 4: Commit**

```bash
cd frontend
git add package.json package-lock.json
git commit -m "chore(auth): add react-hook-form, zod, and msw dependencies"
```

---

### Task 2: Create MSW fixtures module

**Files:**
- Create: `frontend/src/test/msw/fixtures.ts`

- [ ] **Step 1: Create the fixtures file**

```ts
// frontend/src/test/msw/fixtures.ts
import type { AuthUser } from "@/lib/auth/session";

/**
 * Default mock user used by handlers and tests. Override per-test by passing a
 * custom user to the handler factories in `handlers.ts`.
 */
export const mockUser: AuthUser = {
  id: 42,
  email: "test@example.com",
  displayName: null,
  slAvatarUuid: null,
  verified: false,
};

/**
 * Default mock authenticated response shape — matches the backend's `AuthResponse`
 * record (Task 01-07: `{ accessToken: string, user: UserResponse }`).
 */
export function mockAuthResponse(user: AuthUser = mockUser) {
  return {
    accessToken: "mock-access-token-jwt",
    user,
  };
}
```

This file imports from `@/lib/auth/session` which doesn't exist yet (it lands in Task 5). The TypeScript compiler will error until Task 5 is committed. That's intentional — the fixtures are used by `handlers.ts` (Task 3) and the canary tests (Task 12), all of which depend on the session module being in place. Phase A creates the test infrastructure scaffolding; Phase B fills in the dependencies that resolve the compile errors.

- [ ] **Step 2: Verify TypeScript compile-error is the expected one**

```bash
cd frontend
npx tsc --noEmit 2>&1 | head -20
```

Expected: error pointing to `Cannot find module '@/lib/auth/session'` in `fixtures.ts`. **No other errors.** If you see other errors, fix them before proceeding.

- [ ] **Step 3: Commit**

```bash
cd frontend
git add src/test/msw/fixtures.ts
git commit -m "test(auth): add MSW fixtures module (mockUser, mockAuthResponse)"
```

This commit ships in a temporarily-broken state (one TS error). Tasks 3 and 5 follow immediately. The verify chain is allowed to be red across these three commits because Phase A is scaffolding the test infra in dependency order; the chain becomes green again at Task 5's commit.

---

### Task 3: Create MSW server + handlers

**Files:**
- Create: `frontend/src/test/msw/server.ts`
- Create: `frontend/src/test/msw/handlers.ts`

- [ ] **Step 1: Create the server module**

```ts
// frontend/src/test/msw/server.ts
import { setupServer } from "msw/node";

/**
 * Shared MSW server instance. Started in `vitest.setup.ts` via `server.listen()`.
 *
 * Default handlers (registered at startup) live in `handlers.ts` and cover the
 * "fresh visit, no session" baseline. Per-test overrides use `server.use(...)`.
 */
export const server = setupServer();
```

- [ ] **Step 2: Create the handlers module**

```ts
// frontend/src/test/msw/handlers.ts
import { http, HttpResponse } from "msw";
import { mockUser, mockAuthResponse } from "./fixtures";

/**
 * Named handler factories for every auth endpoint, plus a `defaultHandlers`
 * export used by `vitest.setup.ts` to seed the server with the "logged out"
 * baseline.
 *
 * Per-test overrides:
 *   server.use(authHandlers.loginSuccess())
 *   server.use(authHandlers.registerEmailExists())
 *
 * Handler factories take optional fixture parameters (e.g., a custom user) so
 * tests can specialize without rebuilding the response from scratch.
 */
export const authHandlers = {
  // Default bootstrap state: no session cookie → 401 AUTH_TOKEN_MISSING.
  refreshUnauthenticated: () =>
    http.post("*/api/auth/refresh", () =>
      HttpResponse.json(
        {
          type: "https://slpa.example/problems/auth/token-missing",
          title: "Authentication required",
          status: 401,
          detail: "Authentication is required to access this resource.",
          code: "AUTH_TOKEN_MISSING",
        },
        { status: 401 }
      )
    ),

  refreshSuccess: (user = mockUser) =>
    http.post("*/api/auth/refresh", () =>
      HttpResponse.json(mockAuthResponse(user), {
        status: 200,
        headers: {
          "Set-Cookie": "refreshToken=fake-refresh; HttpOnly; Path=/api/auth; SameSite=Lax",
        },
      })
    ),

  registerSuccess: (user = mockUser) =>
    http.post("*/api/auth/register", () =>
      HttpResponse.json(mockAuthResponse(user), {
        status: 201,
        headers: {
          "Set-Cookie": "refreshToken=fake-refresh; HttpOnly; Path=/api/auth; SameSite=Lax",
        },
      })
    ),

  registerEmailExists: () =>
    http.post("*/api/auth/register", () =>
      HttpResponse.json(
        {
          type: "https://slpa.example/problems/auth/email-exists",
          title: "Email already registered",
          status: 409,
          detail: "An account with that email already exists.",
          code: "AUTH_EMAIL_EXISTS",
        },
        { status: 409 }
      )
    ),

  registerValidationError: (errors: { field: string; message: string }[]) =>
    http.post("*/api/auth/register", () =>
      HttpResponse.json(
        {
          type: "https://slpa.example/problems/validation",
          title: "Validation failed",
          status: 400,
          detail: `Request contains ${errors.length} invalid field(s).`,
          code: "VALIDATION_FAILED",
          errors,
        },
        { status: 400 }
      )
    ),

  loginSuccess: (user = mockUser) =>
    http.post("*/api/auth/login", () =>
      HttpResponse.json(mockAuthResponse(user), {
        status: 200,
        headers: {
          "Set-Cookie": "refreshToken=fake-refresh; HttpOnly; Path=/api/auth; SameSite=Lax",
        },
      })
    ),

  loginInvalidCredentials: () =>
    http.post("*/api/auth/login", () =>
      HttpResponse.json(
        {
          type: "https://slpa.example/problems/auth/invalid-credentials",
          title: "Invalid credentials",
          status: 401,
          detail: "Email or password is incorrect.",
          code: "AUTH_INVALID_CREDENTIALS",
        },
        { status: 401 }
      )
    ),

  logoutSuccess: () =>
    http.post("*/api/auth/logout", () =>
      new HttpResponse(null, { status: 204 })
    ),

  logoutAllSuccess: () =>
    http.post("*/api/auth/logout-all", () =>
      new HttpResponse(null, { status: 204 })
    ),
};

/**
 * Default handlers registered at server startup. Establishes the "no session"
 * baseline so tests that don't explicitly authenticate get the unauthenticated
 * bootstrap path automatically.
 */
export const defaultHandlers = [authHandlers.refreshUnauthenticated()];
```

- [ ] **Step 2: Verify TypeScript still has only the `lib/auth/session` import error**

```bash
cd frontend
npx tsc --noEmit 2>&1 | head -20
```

Expected: same single error in `fixtures.ts` referencing `@/lib/auth/session`. The handlers and server modules compile cleanly because they only import from `./fixtures` and `msw`.

- [ ] **Step 3: Commit**

```bash
cd frontend
git add src/test/msw/server.ts src/test/msw/handlers.ts
git commit -m "test(auth): add MSW server and handler factories for auth endpoints"
```

---

### Task 4: Wire MSW into vitest.setup.ts

**Files:**
- Modify: `frontend/vitest.setup.ts`

- [ ] **Step 1: Add MSW imports and lifecycle hooks**

Open `frontend/vitest.setup.ts` and append these blocks at the end of the file (after the existing `next/font/google`, `next/navigation`, and RTL cleanup blocks):

```ts
// MSW request mocking. The server is shared across all tests; per-test handler
// overrides use `server.use(...)` and are reset between tests.
//
// `onUnhandledRequest: "error"` is LOAD-BEARING. If a test makes a fetch to an
// endpoint without a matching handler, it fails loudly instead of silently
// passing real network calls. A future contributor who switches this to "warn"
// or "bypass" to make a flaky test pass has silently allowed real network
// requests from tests — the same failure mode as deleting a canary integration
// test. See FOOTGUNS §F.8.
import { server } from "@/test/msw/server";
import { defaultHandlers } from "@/test/msw/handlers";

beforeAll(() => {
  server.use(...defaultHandlers);
  server.listen({ onUnhandledRequest: "error" });
});

afterEach(() => {
  server.resetHandlers(...defaultHandlers);
});

afterAll(() => {
  server.close();
});
```

- [ ] **Step 2: Verify TypeScript still has only the expected `lib/auth/session` error**

```bash
cd frontend
npx tsc --noEmit 2>&1 | head -20
```

Expected: same single error in `fixtures.ts`. No new errors from the setup file changes.

- [ ] **Step 3: Commit**

```bash
cd frontend
git add vitest.setup.ts
git commit -m "test(auth): wire MSW lifecycle hooks into vitest setup"
```

---

## Phase B — Auth library: pure modules

### Task 5: Create lib/auth/session.ts and delete lib/auth.ts

**Files:**
- Create: `frontend/src/lib/auth/session.ts`
- Delete: `frontend/src/lib/auth.ts`
- Create: `frontend/src/lib/auth/session.test.ts`

This is the task that resolves the TypeScript error from Tasks 2-4. After this commit, `npm run test` and `npx tsc --noEmit` should both be clean again.

- [ ] **Step 1: Write the failing test**

```ts
// frontend/src/lib/auth/session.test.ts
import { describe, it, expect, beforeEach } from "vitest";
import { getAccessToken, setAccessToken } from "./session";

describe("session token ref", () => {
  beforeEach(() => {
    setAccessToken(null);
  });

  it("getAccessToken returns null by default", () => {
    expect(getAccessToken()).toBeNull();
  });

  it("setAccessToken stores the value", () => {
    setAccessToken("token-abc");
    expect(getAccessToken()).toBe("token-abc");
  });

  it("setAccessToken(null) clears the stored value", () => {
    setAccessToken("token-abc");
    setAccessToken(null);
    expect(getAccessToken()).toBeNull();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frontend
npx vitest run src/lib/auth/session.test.ts
```

Expected: compile failure — `Cannot find module './session'` because the file doesn't exist yet.

- [ ] **Step 3: Create `lib/auth/session.ts`**

```ts
// frontend/src/lib/auth/session.ts

/**
 * Module-level access token ref. Lives outside React because the API client
 * interceptor (`lib/api.ts`) runs outside React's lifecycle and needs
 * synchronous access to the token. React refs are per-component and die on
 * unmount; a module-level `let` is the correct container.
 *
 * Only `getAccessToken` and `setAccessToken` are exported. The `accessToken`
 * variable itself is module-private — a grep for `accessToken = ` outside this
 * file catches any mutation path that bypasses the setter.
 *
 * See FOOTGUNS §F.1.
 */
let accessToken: string | null = null;

export function getAccessToken(): string | null {
  return accessToken;
}

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

/**
 * Frontend-side projection of the backend's `UserResponse` (Task 01-07 §3).
 * Only the fields the frontend actually uses are projected here; the backend
 * returns more fields (bio, profilePicUrl, slUsername, etc.) which are
 * available on `response.user` if a future component needs them.
 */
export type AuthUser = {
  id: number;
  email: string;
  displayName: string | null;
  slAvatarUuid: string | null;
  verified: boolean;
};

/**
 * Discriminated union for the three states the auth layer can be in. The
 * `useAuth()` hook returns this shape; consumers branch on `status`.
 */
export type AuthSession =
  | { status: "loading"; user: null }
  | { status: "authenticated"; user: AuthUser }
  | { status: "unauthenticated"; user: null };
```

- [ ] **Step 4: Delete the old `lib/auth.ts` file**

```bash
cd frontend
rm src/lib/auth.ts
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
cd frontend
npx vitest run src/lib/auth/session.test.ts
```

Expected: 3 tests pass.

- [ ] **Step 6: Run the full test suite to confirm no regressions**

```bash
cd frontend
npm run test
```

Expected: 67 + 3 = 70 tests pass. Any test that imported from `@/lib/auth` should now resolve via Next.js path mapping to `@/lib/auth/index` (which doesn't exist yet) — but since the only consumer of the old `lib/auth.ts` was its own smoke test, there should be no breakage. If a test fails because it imported from `@/lib/auth`, the offending import needs updating to point at `@/lib/auth/session` for the types or `@/lib/auth/index` for the hooks (which lands in Task 17).

- [ ] **Step 7: Commit**

```bash
cd frontend
git add src/lib/auth/session.ts src/lib/auth/session.test.ts src/lib/auth.ts
git commit -m "feat(auth): add lib/auth/session.ts with token ref and types"
```

The `git add src/lib/auth.ts` stages the deletion (file is gone from disk; git records it as deleted).

---

### Task 6: Create lib/auth/passwordStrength.ts + tests

**Files:**
- Create: `frontend/src/lib/auth/passwordStrength.ts`
- Create: `frontend/src/lib/auth/passwordStrength.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
// frontend/src/lib/auth/passwordStrength.test.ts
import { describe, it, expect } from "vitest";
import {
  computePasswordStrength,
  strengthToBars,
  strengthToLabel,
} from "./passwordStrength";

describe("computePasswordStrength", () => {
  it("returns 'empty' for an empty string", () => {
    expect(computePasswordStrength("")).toBe("empty");
  });

  it("returns 'weak' for a short password with no class diversity", () => {
    expect(computePasswordStrength("abc")).toBe("weak");
  });

  it("returns 'fair' for a near-miss (8 chars with letter + digit)", () => {
    expect(computePasswordStrength("abcd1234")).toBe("fair");
  });

  it("returns 'good' for a password meeting the backend regex (10 chars + letter + digit)", () => {
    // Backend regex: ^(?=.*[A-Za-z])(?=.*[\d\W]).{10,}$
    expect(computePasswordStrength("hunter22ab")).toBe("good");
  });

  it("returns 'good' for a password meeting the backend regex with a symbol instead of digit", () => {
    expect(computePasswordStrength("hunter!!ab")).toBe("good");
  });

  it("returns 'strong' for a 14+ character password meeting the regex", () => {
    expect(computePasswordStrength("hunter22abcdef")).toBe("strong");
  });

  it("returns 'strong' for a 10-char password with 3+ character classes", () => {
    expect(computePasswordStrength("Hunter22!a")).toBe("strong");
  });

  it("never returns less than 'good' for any password satisfying the backend regex", () => {
    // Property-style check: take the simplest valid password and assert it's not weak/fair.
    const valid = "abcdefghi1";
    const result = computePasswordStrength(valid);
    expect(["good", "strong"]).toContain(result);
  });
});

describe("strengthToBars", () => {
  it("maps each strength level to its bar count", () => {
    expect(strengthToBars("empty")).toBe(0);
    expect(strengthToBars("weak")).toBe(1);
    expect(strengthToBars("fair")).toBe(2);
    expect(strengthToBars("good")).toBe(3);
    expect(strengthToBars("strong")).toBe(4);
  });
});

describe("strengthToLabel", () => {
  it("maps each strength level to its display label", () => {
    expect(strengthToLabel("empty")).toBe("");
    expect(strengthToLabel("weak")).toBe("Weak");
    expect(strengthToLabel("fair")).toBe("Fair");
    expect(strengthToLabel("good")).toBe("Good");
    expect(strengthToLabel("strong")).toBe("Strong");
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frontend
npx vitest run src/lib/auth/passwordStrength.test.ts
```

Expected: compile failure — `Cannot find module './passwordStrength'`.

- [ ] **Step 3: Create `lib/auth/passwordStrength.ts`**

```ts
// frontend/src/lib/auth/passwordStrength.ts

export type PasswordStrength = "empty" | "weak" | "fair" | "good" | "strong";

/**
 * Backend regex from Task 01-07's RegisterRequest validator. Mirror exactly:
 *   ^(?=.*[A-Za-z])(?=.*[\d\W]).{10,}$
 * 10+ chars, at least one letter, at least one digit OR symbol.
 */
const BACKEND_REGEX = /^(?=.*[A-Za-z])(?=.*[\d\W]).{10,}$/;

/**
 * Hand-rolled strength estimator aligned with the backend regex.
 *
 * Level mapping:
 *   - empty:  input length 0
 *   - weak:   doesn't meet backend regex (too short OR missing required class)
 *   - fair:   doesn't meet backend regex but is close (length ≥8 with progress)
 *   - good:   meets backend regex exactly
 *   - strong: meets backend regex AND goes beyond (≥14 chars OR 3+ char classes)
 *
 * A password that satisfies the backend regex MUST NEVER show less than "good".
 * A user who meets the real requirement and sees only 2 bars will feel punished
 * for complying. "Strong" is the bonus for going above and beyond.
 *
 * See spec §7.1.
 */
export function computePasswordStrength(password: string): PasswordStrength {
  if (password.length === 0) return "empty";

  if (BACKEND_REGEX.test(password)) {
    const characterClasses = countCharacterClasses(password);
    if (password.length >= 14 || characterClasses >= 3) return "strong";
    return "good";
  }

  // Below the backend requirement. Classify as weak or fair based on progress.
  if (password.length >= 8) return "fair";
  return "weak";
}

function countCharacterClasses(password: string): number {
  let count = 0;
  if (/[a-z]/.test(password)) count++;
  if (/[A-Z]/.test(password)) count++;
  if (/\d/.test(password)) count++;
  if (/[^A-Za-z\d]/.test(password)) count++;
  return count;
}

export function strengthToBars(strength: PasswordStrength): number {
  switch (strength) {
    case "empty": return 0;
    case "weak": return 1;
    case "fair": return 2;
    case "good": return 3;
    case "strong": return 4;
  }
}

export function strengthToLabel(strength: PasswordStrength): string {
  switch (strength) {
    case "empty": return "";
    case "weak": return "Weak";
    case "fair": return "Fair";
    case "good": return "Good";
    case "strong": return "Strong";
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd frontend
npx vitest run src/lib/auth/passwordStrength.test.ts
```

Expected: all 11 tests pass.

- [ ] **Step 5: Commit**

```bash
cd frontend
git add src/lib/auth/passwordStrength.ts src/lib/auth/passwordStrength.test.ts
git commit -m "feat(auth): add passwordStrength algorithm aligned with backend regex"
```

---

### Task 7: Create lib/auth/redirects.ts + tests

**Files:**
- Create: `frontend/src/lib/auth/redirects.ts`
- Create: `frontend/src/lib/auth/redirects.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
// frontend/src/lib/auth/redirects.test.ts
import { describe, it, expect } from "vitest";
import { getSafeRedirect } from "./redirects";

describe("getSafeRedirect", () => {
  it("returns /dashboard when next is null", () => {
    expect(getSafeRedirect(null)).toBe("/dashboard");
  });

  it("returns /dashboard when next is undefined", () => {
    expect(getSafeRedirect(undefined)).toBe("/dashboard");
  });

  it("returns /dashboard when next is empty string", () => {
    expect(getSafeRedirect("")).toBe("/dashboard");
  });

  it("returns the path when next is a relative URL", () => {
    expect(getSafeRedirect("/browse")).toBe("/browse");
    expect(getSafeRedirect("/auction/42")).toBe("/auction/42");
  });

  it("returns /dashboard when next does not start with /", () => {
    expect(getSafeRedirect("browse")).toBe("/dashboard");
    expect(getSafeRedirect("https://evil.example/phish")).toBe("/dashboard");
  });

  it("rejects protocol-relative URLs (open redirect attack)", () => {
    expect(getSafeRedirect("//evil.example/phish")).toBe("/dashboard");
  });

  it("rejects URLs containing newlines or control characters", () => {
    expect(getSafeRedirect("/browse\n")).toBe("/dashboard");
    expect(getSafeRedirect("/browse\r")).toBe("/dashboard");
    expect(getSafeRedirect("/browse\0")).toBe("/dashboard");
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frontend
npx vitest run src/lib/auth/redirects.test.ts
```

Expected: compile failure — `Cannot find module './redirects'`.

- [ ] **Step 3: Create `lib/auth/redirects.ts`**

```ts
// frontend/src/lib/auth/redirects.ts

const FALLBACK = "/dashboard";

/**
 * Parses a `?next=` query parameter and returns a safe internal URL.
 *
 * Security rules:
 *   - Must start with / (relative path)
 *   - Must NOT start with // (protocol-relative URL — open redirect attack)
 *   - Must NOT contain newlines or control characters (header-injection adjacent)
 *   - Falls back to /dashboard on any failure
 *
 * The `//evil.example/phish` check is the load-bearing one. Without it, a
 * malicious link like `/login?next=//evil.example/phish` would bounce the user
 * to evil.example after a successful login. This pattern is the standard
 * open-redirect attack vector for web auth flows.
 *
 * See spec §8.
 */
export function getSafeRedirect(next: string | null | undefined): string {
  if (!next) return FALLBACK;
  if (typeof next !== "string") return FALLBACK;
  if (!next.startsWith("/")) return FALLBACK;
  if (next.startsWith("//")) return FALLBACK;
  if (/[\r\n\0]/.test(next)) return FALLBACK;
  return next;
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd frontend
npx vitest run src/lib/auth/redirects.test.ts
```

Expected: all 7 tests pass.

- [ ] **Step 5: Commit**

```bash
cd frontend
git add src/lib/auth/redirects.ts src/lib/auth/redirects.test.ts
git commit -m "feat(auth): add getSafeRedirect open-redirect guard"
```

---

### Task 8: Create lib/auth/schemas.ts + tests

**Files:**
- Create: `frontend/src/lib/auth/schemas.ts`
- Create: `frontend/src/lib/auth/schemas.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
// frontend/src/lib/auth/schemas.test.ts
import { describe, it, expect } from "vitest";
import {
  emailSchema,
  passwordCreateSchema,
  passwordInputSchema,
  registerSchema,
  loginSchema,
  forgotPasswordSchema,
} from "./schemas";

describe("emailSchema", () => {
  it("accepts a valid email", () => {
    expect(emailSchema.safeParse("user@example.com").success).toBe(true);
  });

  it("rejects empty string", () => {
    expect(emailSchema.safeParse("").success).toBe(false);
  });

  it("rejects malformed email", () => {
    expect(emailSchema.safeParse("not-an-email").success).toBe(false);
  });
});

describe("passwordCreateSchema", () => {
  it("accepts a 10-char password with letter + digit", () => {
    expect(passwordCreateSchema.safeParse("hunter22ab").success).toBe(true);
  });

  it("rejects passwords shorter than 10 chars", () => {
    expect(passwordCreateSchema.safeParse("hunter1").success).toBe(false);
  });

  it("rejects passwords without a digit or symbol", () => {
    expect(passwordCreateSchema.safeParse("abcdefghijk").success).toBe(false);
  });

  it("accepts passwords with letter + symbol (no digit required)", () => {
    expect(passwordCreateSchema.safeParse("hunter!!ab").success).toBe(true);
  });
});

describe("passwordInputSchema", () => {
  it("accepts any non-empty password (login is checking credentials, not creating)", () => {
    expect(passwordInputSchema.safeParse("a").success).toBe(true);
    expect(passwordInputSchema.safeParse("legacy-short").success).toBe(true);
  });

  it("rejects empty password", () => {
    expect(passwordInputSchema.safeParse("").success).toBe(false);
  });
});

describe("registerSchema", () => {
  const valid = {
    email: "user@example.com",
    password: "hunter22ab",
    confirmPassword: "hunter22ab",
    terms: true as const,
  };

  it("accepts a valid register payload", () => {
    expect(registerSchema.safeParse(valid).success).toBe(true);
  });

  it("rejects when passwords don't match", () => {
    const result = registerSchema.safeParse({ ...valid, confirmPassword: "different" });
    expect(result.success).toBe(false);
    if (!result.success) {
      const messages = result.error.issues.map((i) => i.message);
      expect(messages).toContain("Passwords don't match");
    }
  });

  it("rejects when terms is not true", () => {
    const result = registerSchema.safeParse({ ...valid, terms: false });
    expect(result.success).toBe(false);
  });
});

describe("loginSchema", () => {
  it("accepts a valid login payload", () => {
    const result = loginSchema.safeParse({ email: "user@example.com", password: "anything" });
    expect(result.success).toBe(true);
  });

  it("rejects empty password", () => {
    const result = loginSchema.safeParse({ email: "user@example.com", password: "" });
    expect(result.success).toBe(false);
  });
});

describe("forgotPasswordSchema", () => {
  it("accepts an email-only payload", () => {
    expect(forgotPasswordSchema.safeParse({ email: "user@example.com" }).success).toBe(true);
  });

  it("rejects malformed email", () => {
    expect(forgotPasswordSchema.safeParse({ email: "not-an-email" }).success).toBe(false);
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frontend
npx vitest run src/lib/auth/schemas.test.ts
```

Expected: compile failure — `Cannot find module './schemas'`.

- [ ] **Step 3: Create `lib/auth/schemas.ts`**

```ts
// frontend/src/lib/auth/schemas.ts
import { z } from "zod";

/**
 * Email schema reused by register, login, and forgot-password forms.
 */
export const emailSchema = z
  .string()
  .min(1, "Email is required")
  .email("Enter a valid email")
  .max(255);

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
 * Register form schema. Composes email + passwordCreate + confirmPassword + terms.
 * Cross-field validation: passwords must match.
 */
export const registerSchema = z
  .object({
    email: emailSchema,
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
 * Login form schema. Email + non-empty password.
 */
export const loginSchema = z.object({
  email: emailSchema,
  password: passwordInputSchema,
});

/**
 * Forgot-password form schema. Email only.
 */
export const forgotPasswordSchema = z.object({
  email: emailSchema,
});

export type RegisterFormValues = z.infer<typeof registerSchema>;
export type LoginFormValues = z.infer<typeof loginSchema>;
export type ForgotPasswordFormValues = z.infer<typeof forgotPasswordSchema>;
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd frontend
npx vitest run src/lib/auth/schemas.test.ts
```

Expected: 13 tests pass.

- [ ] **Step 5: Commit**

```bash
cd frontend
git add src/lib/auth/schemas.ts src/lib/auth/schemas.test.ts
git commit -m "feat(auth): add zod schemas for register, login, and forgot-password forms"
```

---

### Task 9: Create lib/auth/errors.ts + tests

**Files:**
- Create: `frontend/src/lib/auth/errors.ts`
- Create: `frontend/src/lib/auth/errors.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
// frontend/src/lib/auth/errors.test.ts
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { useForm } from "react-hook-form";
import { renderHook, act } from "@testing-library/react";
import { mapProblemDetailToForm } from "./errors";
import { ApiError } from "@/lib/api";

type TestForm = {
  email: string;
  password: string;
  confirmPassword: string;
  terms: boolean;
};

const KNOWN_FIELDS = ["email", "password", "confirmPassword", "terms"] as const;

function setupForm() {
  return renderHook(() =>
    useForm<TestForm>({
      defaultValues: { email: "", password: "", confirmPassword: "", terms: false },
    })
  );
}

describe("mapProblemDetailToForm", () => {
  let consoleSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    consoleSpy = vi.spyOn(console, "warn").mockImplementation(() => {});
  });

  afterEach(() => {
    consoleSpy.mockRestore();
  });

  it("maps VALIDATION_FAILED with errors[] to per-field setError calls", () => {
    const { result } = setupForm();
    const error = new ApiError("Validation failed", 400, {
      type: "https://slpa.example/problems/validation",
      status: 400,
      code: "VALIDATION_FAILED",
      errors: [
        { field: "email", message: "must be a valid email" },
        { field: "password", message: "must be at least 10 characters" },
      ],
    });

    act(() => {
      mapProblemDetailToForm(error, result.current, KNOWN_FIELDS);
    });

    expect(result.current.formState.errors.email?.message).toBe("must be a valid email");
    expect(result.current.formState.errors.password?.message).toBe("must be at least 10 characters");
  });

  it("maps AUTH_EMAIL_EXISTS to a field-level error on email", () => {
    const { result } = setupForm();
    const error = new ApiError("Conflict", 409, {
      type: "https://slpa.example/problems/auth/email-exists",
      status: 409,
      code: "AUTH_EMAIL_EXISTS",
    });

    act(() => {
      mapProblemDetailToForm(error, result.current, KNOWN_FIELDS);
    });

    expect(result.current.formState.errors.email?.message).toMatch(/already exists/i);
  });

  it("maps AUTH_INVALID_CREDENTIALS to root.serverError (NOT a field-level error)", () => {
    const { result } = setupForm();
    const error = new ApiError("Unauthorized", 401, {
      type: "https://slpa.example/problems/auth/invalid-credentials",
      status: 401,
      code: "AUTH_INVALID_CREDENTIALS",
    });

    act(() => {
      mapProblemDetailToForm(error, result.current, KNOWN_FIELDS);
    });

    expect(result.current.formState.errors.email).toBeUndefined();
    expect(result.current.formState.errors.password).toBeUndefined();
    // root.serverError lives at errors.root?.serverError in RHF.
    const root = (result.current.formState.errors as any).root;
    expect(root?.serverError?.message).toMatch(/incorrect/i);
  });

  it("falls back to root.serverError for unknown fields and warns in dev", () => {
    const { result } = setupForm();
    const error = new ApiError("Validation failed", 400, {
      type: "https://slpa.example/problems/validation",
      status: 400,
      code: "VALIDATION_FAILED",
      errors: [
        { field: "unknownField", message: "some new validator" },
      ],
    });

    act(() => {
      mapProblemDetailToForm(error, result.current, KNOWN_FIELDS);
    });

    const root = (result.current.formState.errors as any).root;
    expect(root?.serverError?.message).toContain("unknownField");
    expect(consoleSpy).toHaveBeenCalled();
  });

  it("falls back to root.serverError for unknown error types", () => {
    const { result } = setupForm();
    const error = new ApiError("Internal", 500, {
      type: "https://slpa.example/problems/internal-server-error",
      status: 500,
      code: "INTERNAL_SERVER_ERROR",
      detail: "Something went wrong on our end.",
    });

    act(() => {
      mapProblemDetailToForm(error, result.current, KNOWN_FIELDS);
    });

    const root = (result.current.formState.errors as any).root;
    expect(root?.serverError?.message).toBe("Something went wrong on our end.");
  });

  it("falls back to a generic message when error is not an ApiError", () => {
    const { result } = setupForm();

    act(() => {
      mapProblemDetailToForm(new Error("network down"), result.current, KNOWN_FIELDS);
    });

    const root = (result.current.formState.errors as any).root;
    expect(root?.serverError?.message).toMatch(/something went wrong/i);
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frontend
npx vitest run src/lib/auth/errors.test.ts
```

Expected: compile failure — `Cannot find module './errors'`.

- [ ] **Step 3: Create `lib/auth/errors.ts`**

```ts
// frontend/src/lib/auth/errors.ts
import type { UseFormReturn, FieldValues, Path } from "react-hook-form";
import { ApiError, isApiError } from "@/lib/api";

type ProblemDetailValidationError = {
  field: string;
  message: string;
};

type ProblemDetail = {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  instance?: string;
  code?: string;
  errors?: ProblemDetailValidationError[];
};

/**
 * Maps a backend ProblemDetail error into react-hook-form field errors.
 *
 * Handles three paths:
 *   - VALIDATION_FAILED (400 with errors[] array) → per-field form.setError
 *   - AUTH_EMAIL_EXISTS (409) → field-level on `email`
 *   - AUTH_INVALID_CREDENTIALS (401) → form-level root.serverError
 *   - Everything else → form-level root.serverError with the detail or generic
 *
 * Unknown-field guard: if a VALIDATION_FAILED entry references a field that
 * doesn't exist on the form, fall back to root.serverError with the
 * concatenated message and `console.warn` in dev. This is a drift-detection
 * mechanism — the form tells you "something new is being validated that you
 * don't know about" rather than failing silently.
 *
 * See spec §5 and FOOTGUNS §F.6, §F.7.
 */
export function mapProblemDetailToForm<T extends FieldValues>(
  error: unknown,
  form: UseFormReturn<T>,
  knownFields: readonly string[]
): void {
  if (!isApiError(error) || !error.problem) {
    form.setError("root.serverError" as Path<T>, {
      type: "server",
      message: "Something went wrong. Please try again.",
    });
    return;
  }

  const problem = error.problem as ProblemDetail;
  const code = problem.code;

  if (code === "VALIDATION_FAILED" && Array.isArray(problem.errors)) {
    const unknownFields: string[] = [];
    for (const entry of problem.errors) {
      if (knownFields.includes(entry.field)) {
        form.setError(entry.field as Path<T>, {
          type: "server",
          message: entry.message,
        });
      } else {
        unknownFields.push(`${entry.field}: ${entry.message}`);
      }
    }
    if (unknownFields.length > 0) {
      if (process.env.NODE_ENV !== "production") {
        console.warn(
          "[mapProblemDetailToForm] Unknown fields in backend validation response:",
          unknownFields
        );
      }
      form.setError("root.serverError" as Path<T>, {
        type: "server",
        message: unknownFields.join("; "),
      });
    }
    return;
  }

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

  // Fallback: generic form-level error.
  form.setError("root.serverError" as Path<T>, {
    type: "server",
    message: problem.detail ?? "Something went wrong. Please try again.",
  });
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd frontend
npx vitest run src/lib/auth/errors.test.ts
```

Expected: 6 tests pass. **Note**: this test imports from `@/lib/api` which already exists from Task 01-06 and exports `ApiError` + `isApiError`. If `ApiError`'s constructor signature differs from what the test uses (`new ApiError(message, status, problem)`), adjust the test to match the actual constructor while preserving the test intent.

- [ ] **Step 5: Commit**

```bash
cd frontend
git add src/lib/auth/errors.ts src/lib/auth/errors.test.ts
git commit -m "feat(auth): add mapProblemDetailToForm helper with unknown-field guard"
```

---

## Phase C — API client wiring

### Task 10: Add `configureApiClient` and Authorization header injection to `lib/api.ts`

**Files:**
- Modify: `frontend/src/lib/api.ts`

This task adds the setup function and the bearer-token header injection. Task 11 adds the 401 interceptor on top of this scaffolding.

- [ ] **Step 1: Read the current `lib/api.ts` to understand its existing shape**

```bash
cd frontend
cat src/lib/api.ts
```

The Task 01-06 file exports an `api` object with `get`/`post`/`put`/`delete` methods, an `ApiError` class, and an `isApiError` type guard. All methods use `credentials: "include"` for the cookie flow. Reuse the existing structure — do not rewrite the whole file.

- [ ] **Step 2: Add module-level state and `configureApiClient`**

Add these top-level declarations near the existing `BASE_URL` constant:

```ts
import type { QueryClient } from "@tanstack/react-query";
import { getAccessToken, setAccessToken } from "@/lib/auth/session";

let queryClientRef: QueryClient | null = null;
let inFlightRefresh: Promise<void> | null = null;

/**
 * Wires the API client to the app's QueryClient. Called once at app mount from
 * `app/providers.tsx`. Storing the QueryClient reference at module scope lets
 * the 401 interceptor (Task 11) update the session query cache on failed
 * refresh without React lifecycle access.
 *
 * See FOOTGUNS §F.3.
 */
export function configureApiClient(queryClient: QueryClient): void {
  queryClientRef = queryClient;
}
```

- [ ] **Step 3: Inject `Authorization: Bearer <token>` into every non-auth request**

Find the existing fetch wrapper (probably named `request` or inlined in each method). Update its header composition to include the bearer token when present. Example shape:

```ts
async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const token = getAccessToken();
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };

  const response = await fetch(`${BASE_URL}${path}`, {
    method,
    credentials: "include",
    headers,
    body: body ? JSON.stringify(body) : undefined,
  });

  if (!response.ok) {
    // Existing ApiError construction from Task 01-06 — preserve exactly.
    // Task 11 adds the 401-interceptor branch above this throw.
    const problem = await response.json().catch(() => ({}));
    throw new ApiError(response.statusText, response.status, problem);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return response.json() as Promise<T>;
}
```

The exact preservation of the existing `ApiError` construction matters — don't change the error format. Just thread `Authorization` into the headers when a token exists.

- [ ] **Step 4: Run the existing test suite to verify nothing broke**

```bash
cd frontend
npm run test
```

Expected: all tests still pass. The Authorization header is only added when `getAccessToken()` returns non-null, which it never does in the existing test suite (no test calls `setAccessToken`), so existing behavior is unchanged.

- [ ] **Step 5: Commit**

```bash
cd frontend
git add src/lib/api.ts
git commit -m "feat(auth): add configureApiClient and bearer token injection to api client"
```

---

### Task 11: Add 401 interceptor + concurrent stampede protection

**Files:**
- Modify: `frontend/src/lib/api.ts`

- [ ] **Step 1: Wrap the existing 4xx/5xx handling with the 401 branch**

Find the `if (!response.ok)` block in the request wrapper. Add a 401 branch BEFORE the generic `throw new ApiError(...)` that calls a new `handleUnauthorized` helper:

```ts
async function request<T>(method: string, path: string, body?: unknown, isRetry = false): Promise<T> {
  const token = getAccessToken();
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };

  const response = await fetch(`${BASE_URL}${path}`, {
    method,
    credentials: "include",
    headers,
    body: body ? JSON.stringify(body) : undefined,
  });

  if (response.status === 401 && !isRetry && !path.startsWith("/api/auth/")) {
    // Auth-path 401s (login, refresh, etc.) are real failures the caller must
    // see. Non-auth-path 401s trigger the refresh-and-retry flow.
    return handleUnauthorized<T>(method, path, body);
  }

  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    throw new ApiError(response.statusText, response.status, problem);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return response.json() as Promise<T>;
}
```

The `isRetry` parameter prevents infinite loops — after the first refresh-and-retry, a subsequent 401 from the same path falls through to the normal error path.

The `!path.startsWith("/api/auth/")` exclusion ensures `/api/auth/login`, `/api/auth/register`, `/api/auth/refresh`, `/api/auth/logout`, `/api/auth/logout-all` propagate their 401s normally instead of triggering refresh-on-refresh recursion.

- [ ] **Step 2: Add the `handleUnauthorized` helper**

```ts
async function handleUnauthorized<T>(method: string, path: string, body?: unknown): Promise<T> {
  // Concurrent-stampede protection: multiple 401s share one refresh attempt.
  //
  // The cleanup (clearing inFlightRefresh) lives INSIDE the IIFE's try/finally
  // so only the CREATOR clears the ref, not every awaiter racing to null it
  // out. JS microtask ordering makes "every awaiter clears in its own finally"
  // safe in practice, but having one code path own the cleanup is structurally
  // correct and easier to reason about.
  //
  // See FOOTGUNS §F.4.
  if (!inFlightRefresh) {
    inFlightRefresh = (async () => {
      try {
        const refreshed = await fetch(`${BASE_URL}/api/auth/refresh`, {
          method: "POST",
          credentials: "include",
        });

        if (!refreshed.ok) {
          // Refresh failed — clear session and redirect to login.
          setAccessToken(null);
          if (queryClientRef) {
            queryClientRef.setQueryData(["auth", "session"], null);
            queryClientRef.removeQueries({ queryKey: ["auth", "session"] });
          }
          if (typeof window !== "undefined") {
            const next = encodeURIComponent(window.location.pathname + window.location.search);
            window.location.href = `/login?next=${next}`;
          }
          throw new ApiError("Session expired", 401, {});
        }

        const refreshBody = await refreshed.json();
        setAccessToken(refreshBody.accessToken);
        if (queryClientRef) {
          queryClientRef.setQueryData(["auth", "session"], refreshBody.user);
        }
      } finally {
        inFlightRefresh = null;
      }
    })();
  }

  await inFlightRefresh;

  // Retry the original request once with the new access token.
  // Pass isRetry=true to prevent infinite refresh loops on a second 401.
  return request<T>(method, path, body, /* isRetry */ true);
}
```

- [ ] **Step 3: Run the existing test suite**

```bash
cd frontend
npm run test
```

Expected: all tests still pass. The 401 interceptor only fires when (a) the response is 401, (b) the original request wasn't already a retry, and (c) the path is not an auth path. None of those conditions are met in existing tests.

- [ ] **Step 4: Commit**

```bash
cd frontend
git add src/lib/api.ts
git commit -m "feat(auth): add 401 interceptor with refresh-and-retry and stampede protection"
```

---

### Task 12: Add the 401-auto-refresh integration test (the security canary)

**Files:**
- Create: `frontend/src/lib/api.401-interceptor.test.tsx`

This is the **security canary**. Non-negotiable. Never delete. The test proves the API client interceptor's refresh-and-retry, stampede dedup, and refresh-failure-redirect paths all work end-to-end against realistic MSW mocks.

- [ ] **Step 1: Write the canary test**

```tsx
// frontend/src/lib/api.401-interceptor.test.tsx
//
// SECURITY CANARY — see FOOTGUNS §F.9.
//
// This test proves the API client's 401-interceptor refreshes and retries
// correctly. It is the frontend equivalent of Task 01-07's
// `refreshTokenReuseCascade` integration test. Never delete. Never quarantine.
// If a future contributor "optimizes" the interceptor by skipping refresh-and-
// retry, this test catches it; if they delete this test to ship the optimization,
// they've silently disabled the auth layer's self-healing behavior.

import { describe, it, expect, beforeEach, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { QueryClient } from "@tanstack/react-query";
import { server } from "@/test/msw/server";
import { api, configureApiClient } from "./api";
import { getAccessToken, setAccessToken } from "@/lib/auth/session";

describe("401 auto-refresh and retry (security canary)", () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    configureApiClient(queryClient);
    setAccessToken("initial-stale-token");
  });

  it("refreshes the access token and retries the original request on 401", async () => {
    let protectedCallCount = 0;
    let refreshCallCount = 0;

    server.use(
      http.get("*/api/users/me", () => {
        protectedCallCount++;
        if (protectedCallCount === 1) {
          return HttpResponse.json(
            { code: "AUTH_TOKEN_EXPIRED", status: 401 },
            { status: 401 }
          );
        }
        return HttpResponse.json({ id: 1, email: "test@example.com" });
      }),
      http.post("*/api/auth/refresh", () => {
        refreshCallCount++;
        return HttpResponse.json({
          accessToken: "fresh-access-token",
          user: { id: 1, email: "test@example.com", displayName: null, slAvatarUuid: null, verified: false },
        });
      })
    );

    const result = await api.get<{ id: number; email: string }>("/api/users/me");

    expect(protectedCallCount).toBe(2); // First call 401, second call (retry) 200
    expect(refreshCallCount).toBe(1); // Refresh called once
    expect(getAccessToken()).toBe("fresh-access-token");
    expect(result.id).toBe(1);
    expect(result.email).toBe("test@example.com");
  });

  it("dedupes concurrent 401s into a single refresh call (stampede protection)", async () => {
    let protectedCallCount = 0;
    let refreshCallCount = 0;

    server.use(
      http.get("*/api/users/me", () => {
        protectedCallCount++;
        // First three calls return 401; subsequent calls return 200.
        if (protectedCallCount <= 3) {
          return HttpResponse.json(
            { code: "AUTH_TOKEN_EXPIRED", status: 401 },
            { status: 401 }
          );
        }
        return HttpResponse.json({ id: 1, email: "test@example.com" });
      }),
      http.post("*/api/auth/refresh", () => {
        refreshCallCount++;
        return HttpResponse.json({
          accessToken: "fresh-access-token",
          user: { id: 1, email: "test@example.com", displayName: null, slAvatarUuid: null, verified: false },
        });
      })
    );

    // Fire three concurrent calls. All three should 401, share one refresh,
    // then all three retry (3 retries) → 6 total protected calls.
    const results = await Promise.all([
      api.get("/api/users/me"),
      api.get("/api/users/me"),
      api.get("/api/users/me"),
    ]);

    expect(refreshCallCount).toBe(1); // ONE refresh, not three
    expect(protectedCallCount).toBe(6); // Three initial 401s + three retries
    expect(results).toHaveLength(3);
    expect(getAccessToken()).toBe("fresh-access-token");
  });

  it("clears session and redirects to /login on failed refresh", async () => {
    server.use(
      http.get("*/api/users/me", () =>
        HttpResponse.json({ code: "AUTH_TOKEN_EXPIRED", status: 401 }, { status: 401 })
      ),
      http.post("*/api/auth/refresh", () =>
        HttpResponse.json({ code: "AUTH_TOKEN_MISSING", status: 401 }, { status: 401 })
      )
    );

    // Stub window.location to capture the redirect.
    const originalLocation = window.location;
    const mockLocation = { ...originalLocation, href: "" };
    Object.defineProperty(window, "location", {
      writable: true,
      value: mockLocation,
    });

    await expect(api.get("/api/users/me")).rejects.toThrow();

    expect(getAccessToken()).toBeNull();
    expect(queryClient.getQueryData(["auth", "session"])).toBeNull();
    expect(mockLocation.href).toContain("/login?next=");

    // Restore window.location.
    Object.defineProperty(window, "location", {
      writable: true,
      value: originalLocation,
    });
  });
});
```

- [ ] **Step 2: Run the canary test**

```bash
cd frontend
npx vitest run src/lib/api.401-interceptor.test.tsx
```

Expected: 3 tests pass. If any fail, the interceptor implementation has a bug — STOP and debug, do not proceed. This is the canary; it must be green.

- [ ] **Step 3: Run the full test suite to confirm no regressions**

```bash
cd frontend
npm run test
```

Expected: previous count + 3 new tests, all passing.

- [ ] **Step 4: Commit**

```bash
cd frontend
git add src/lib/api.401-interceptor.test.tsx
git commit -m "test(auth): add 401 auto-refresh canary integration test (FOOTGUNS §F.9)"
```

---

## Phase D — Auth hooks + barrel

### Task 13: Create lib/auth/api.ts (typed endpoint wrappers)

**Files:**
- Create: `frontend/src/lib/auth/api.ts`

Thin typed wrappers around the existing `api.post` for the five auth endpoints. Centralizes the request/response shapes so hooks don't reimplement them.

- [ ] **Step 1: Create the file**

```ts
// frontend/src/lib/auth/api.ts
import { api } from "@/lib/api";
import type { AuthUser } from "./session";

/**
 * Typed wrappers for the 5 backend auth endpoints from Task 01-07.
 *
 * Each wrapper is a thin call to the shared `api` client; centralizing the
 * request/response shapes here keeps the hooks file focused on TanStack Query
 * orchestration.
 */

export type AuthResponse = {
  accessToken: string;
  user: AuthUser;
};

export type RegisterRequest = {
  email: string;
  password: string;
  displayName: string | null;
};

export type LoginRequest = {
  email: string;
  password: string;
};

export const authApi = {
  register: (body: RegisterRequest) =>
    api.post<AuthResponse>("/api/auth/register", body),

  login: (body: LoginRequest) =>
    api.post<AuthResponse>("/api/auth/login", body),

  refresh: () =>
    api.post<AuthResponse>("/api/auth/refresh"),

  logout: () =>
    api.post<void>("/api/auth/logout"),

  logoutAll: () =>
    api.post<void>("/api/auth/logout-all"),
};
```

- [ ] **Step 2: Verify the file compiles**

```bash
cd frontend
npx tsc --noEmit
```

Expected: clean. The file imports from existing modules (`@/lib/api`, `./session`).

- [ ] **Step 3: Commit**

```bash
cd frontend
git add src/lib/auth/api.ts
git commit -m "feat(auth): add typed wrappers for the 5 auth endpoints"
```

---

### Task 14: Create lib/auth/hooks.ts with `useAuth` and `bootstrapSession`

**Files:**
- Create: `frontend/src/lib/auth/hooks.ts`
- Create: `frontend/src/lib/auth/hooks.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/lib/auth/hooks.test.tsx
import { describe, it, expect, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw/server";
import { authHandlers } from "@/test/msw/handlers";
import { useAuth } from "./hooks";
import { setAccessToken, getAccessToken } from "./session";

function wrapper({ children }: { children: React.ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

describe("useAuth", () => {
  beforeEach(() => {
    setAccessToken(null);
  });

  it("starts in loading state, then transitions to unauthenticated on 401", async () => {
    server.use(authHandlers.refreshUnauthenticated());

    const { result } = renderHook(() => useAuth(), { wrapper });

    expect(result.current.status).toBe("loading");

    await waitFor(() => {
      expect(result.current.status).toBe("unauthenticated");
    });
    expect(result.current.user).toBeNull();
    expect(getAccessToken()).toBeNull();
  });

  it("transitions to authenticated and sets access token on successful refresh", async () => {
    server.use(authHandlers.refreshSuccess());

    const { result } = renderHook(() => useAuth(), { wrapper });

    await waitFor(() => {
      expect(result.current.status).toBe("authenticated");
    });
    expect(result.current.user?.email).toBe("test@example.com");
    expect(getAccessToken()).toBe("mock-access-token-jwt");
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frontend
npx vitest run src/lib/auth/hooks.test.tsx
```

Expected: compile failure — `Cannot find module './hooks'`.

- [ ] **Step 3: Create `lib/auth/hooks.ts`**

```ts
// frontend/src/lib/auth/hooks.ts
"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { authApi, type LoginRequest, type RegisterRequest } from "./api";
import { setAccessToken } from "./session";
import type { AuthSession, AuthUser } from "./session";

const SESSION_QUERY_KEY = ["auth", "session"] as const;

/**
 * Bootstrap the session by calling POST /api/auth/refresh.
 *
 * The side effect (calling setAccessToken) lives INSIDE queryFn, NOT in
 * onSuccess. TanStack Query's first subscriber receives queryFn's return
 * value directly — onSuccess only fires on subsequent subscribers and
 * refetches. If we put setAccessToken in onSuccess, the first useAuth() call
 * would get the user but the access token ref would stay null until the next
 * refetch (which may never happen with staleTime: Infinity).
 *
 * See FOOTGUNS §F.2.
 */
async function bootstrapSession(): Promise<AuthUser> {
  const response = await authApi.refresh();
  setAccessToken(response.accessToken);
  return response.user;
}

/**
 * Returns the current auth session as a discriminated union.
 *
 * The three Query states map to the three AuthSession states:
 *   - isPending → { status: "loading", user: null }
 *   - isError   → { status: "unauthenticated", user: null }
 *   - success   → { status: "authenticated", user }
 *
 * The first call to useAuth() in the React tree triggers the bootstrap query
 * automatically — no dedicated AuthProvider is needed. Header's call to
 * useAuth() on every page mount is what kicks off the bootstrap.
 *
 * The query config uses three non-default flags, all load-bearing:
 *   - staleTime: Infinity — don't auto-refetch on every useAuth() call
 *   - gcTime: Infinity — never garbage-collect (Header may unmount briefly)
 *   - retry: false — a 401 on bootstrap is legitimate unauthenticated state
 *
 * See FOOTGUNS §F.5.
 */
export function useAuth(): AuthSession {
  const query = useQuery({
    queryKey: SESSION_QUERY_KEY,
    queryFn: bootstrapSession,
    staleTime: Infinity,
    gcTime: Infinity,
    retry: false,
    refetchOnWindowFocus: false,
  });

  if (query.isPending) return { status: "loading", user: null };
  if (query.isError) return { status: "unauthenticated", user: null };
  return { status: "authenticated", user: query.data };
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd frontend
npx vitest run src/lib/auth/hooks.test.tsx
```

Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
cd frontend
git add src/lib/auth/hooks.ts src/lib/auth/hooks.test.tsx
git commit -m "feat(auth): add useAuth hook with bootstrap session query"
```

---

### Task 15: Add `useLogin` and `useRegister` mutations

**Files:**
- Modify: `frontend/src/lib/auth/hooks.ts`
- Modify: `frontend/src/lib/auth/hooks.test.tsx`

- [ ] **Step 1: Append failing tests for `useLogin` and `useRegister`**

Add these tests to `hooks.test.tsx`:

```tsx
import { useLogin, useRegister } from "./hooks";

describe("useLogin", () => {
  beforeEach(() => {
    setAccessToken(null);
  });

  it("calls /api/auth/login, sets access token, and updates session cache on success", async () => {
    server.use(authHandlers.loginSuccess());

    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );

    const { result } = renderHook(() => useLogin(), { wrapper });

    result.current.mutate({ email: "test@example.com", password: "anything" });

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    expect(getAccessToken()).toBe("mock-access-token-jwt");
    expect(queryClient.getQueryData(["auth", "session"])).toMatchObject({
      email: "test@example.com",
    });
  });

  it("surfaces ApiError on 401 invalid credentials", async () => {
    server.use(authHandlers.loginInvalidCredentials());

    const { result } = renderHook(() => useLogin(), { wrapper });

    result.current.mutate({ email: "wrong@example.com", password: "wrong" });

    await waitFor(() => {
      expect(result.current.isError).toBe(true);
    });
    expect(result.current.error).toBeDefined();
  });
});

describe("useRegister", () => {
  beforeEach(() => {
    setAccessToken(null);
  });

  it("calls /api/auth/register, sets access token, and updates session cache on 201", async () => {
    server.use(authHandlers.registerSuccess());

    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );

    const { result } = renderHook(() => useRegister(), { wrapper });

    result.current.mutate({
      email: "new@example.com",
      password: "hunter22ab",
      displayName: null,
    });

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    expect(getAccessToken()).toBe("mock-access-token-jwt");
    expect(queryClient.getQueryData(["auth", "session"])).toBeDefined();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frontend
npx vitest run src/lib/auth/hooks.test.tsx
```

Expected: compile failure — `useLogin`, `useRegister` not exported.

- [ ] **Step 3: Add the mutations to `hooks.ts`**

Append to the file:

```ts
/**
 * Login mutation. On success, sets the access token and updates the session
 * query cache directly via setQueryData (NOT invalidateQueries — that would
 * trigger a wasted /refresh round-trip immediately after login).
 *
 * See FOOTGUNS §F.12.
 */
export function useLogin() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: LoginRequest) => authApi.login(body),
    onSuccess: (response) => {
      setAccessToken(response.accessToken);
      queryClient.setQueryData(SESSION_QUERY_KEY, response.user);
    },
  });
}

/**
 * Register mutation. Same orchestration as login — backend returns the same
 * AuthResponse shape on 201 Created.
 */
export function useRegister() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: RegisterRequest) => authApi.register(body),
    onSuccess: (response) => {
      setAccessToken(response.accessToken);
      queryClient.setQueryData(SESSION_QUERY_KEY, response.user);
    },
  });
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd frontend
npx vitest run src/lib/auth/hooks.test.tsx
```

Expected: 5 tests pass (2 from useAuth + 3 new).

- [ ] **Step 5: Commit**

```bash
cd frontend
git add src/lib/auth/hooks.ts src/lib/auth/hooks.test.tsx
git commit -m "feat(auth): add useLogin and useRegister mutations"
```

---

### Task 16: Add `useLogout`, `useLogoutAll`, `useForgotPassword`

**Files:**
- Modify: `frontend/src/lib/auth/hooks.ts`
- Modify: `frontend/src/lib/auth/hooks.test.tsx`

- [ ] **Step 1: Append failing tests**

Add to `hooks.test.tsx`:

```tsx
import { useLogout, useForgotPassword } from "./hooks";

describe("useLogout", () => {
  it("clears access token and session cache on settled (even if network fails)", async () => {
    server.use(
      http.post("*/api/auth/logout", () =>
        HttpResponse.json({ status: 500 }, { status: 500 })
      )
    );

    setAccessToken("some-token");
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    queryClient.setQueryData(["auth", "session"], { id: 1, email: "test@example.com" });

    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );

    const { result } = renderHook(() => useLogout(), { wrapper });

    result.current.mutate();

    await waitFor(() => {
      // onSettled clears state regardless of network outcome.
      expect(getAccessToken()).toBeNull();
      expect(queryClient.getQueryData(["auth", "session"])).toBeNull();
    });
  });
});

describe("useForgotPassword", () => {
  it("resolves successfully (UI stub — no real backend call)", async () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );

    const { result } = renderHook(() => useForgotPassword(), { wrapper });

    result.current.mutate("user@example.com");

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frontend
npx vitest run src/lib/auth/hooks.test.tsx
```

Expected: compile failure — hooks not exported.

- [ ] **Step 3: Add the three hooks to `hooks.ts`**

Append:

```ts
/**
 * Logout mutation. Uses onSettled (NOT onSuccess) — clears local state even
 * if the network call fails. Logout is idempotent and a user clicking "Sign
 * Out" expects to be logged out regardless of whether the backend acknowledged
 * the POST.
 *
 * See FOOTGUNS §F.11.
 */
export function useLogout() {
  const queryClient = useQueryClient();
  const router = useRouter();
  return useMutation({
    mutationFn: () => authApi.logout(),
    onSettled: () => {
      setAccessToken(null);
      queryClient.setQueryData(SESSION_QUERY_KEY, null);
      queryClient.removeQueries({ queryKey: SESSION_QUERY_KEY });
      router.push("/");
    },
  });
}

/**
 * LogoutAll mutation. Always refreshes BEFORE calling logout-all to handle
 * the "user's access token is already expired" edge case. Refresh is cheap
 * (~100ms); the alternative is failing on expired tokens and forcing the user
 * to re-login first.
 *
 * Per Task 01-07 §15 frontend handoff note: this hook ships now (for use in
 * a future account-settings page) but is NOT surfaced in the Header dropdown.
 *
 * See FOOTGUNS §F.10.
 */
export function useLogoutAll() {
  const queryClient = useQueryClient();
  const router = useRouter();
  return useMutation({
    mutationFn: async () => {
      const refreshed = await authApi.refresh();
      setAccessToken(refreshed.accessToken);
      return authApi.logoutAll();
    },
    onSettled: () => {
      setAccessToken(null);
      queryClient.setQueryData(SESSION_QUERY_KEY, null);
      queryClient.removeQueries({ queryKey: SESSION_QUERY_KEY });
      router.push("/");
    },
  });
}

/**
 * Forgot-password mutation.
 *
 * STUB: no backend password-reset endpoint exists yet. This mutationFn fakes
 * a ~300ms delay and always resolves successfully so the UI can display its
 * success state. NO EMAIL IS ACTUALLY SENT.
 *
 * When the real endpoint ships:
 *   1. Replace this body with `authApi.forgotPassword(email)` (and add the
 *      wrapper to lib/auth/api.ts).
 *   2. Remove the [STUB] indicator from ForgotPasswordForm's success state.
 *   3. Remove the inline comment in ForgotPasswordPage.
 *   4. Update the brief at docs/implementation/epic-01/task-08-frontend-auth.md
 *      to remove the "UI only" caveat.
 *
 * This four-step swap is the cost of shipping a UI without its backend.
 */
export function useForgotPassword() {
  return useMutation({
    mutationFn: async (_email: string) => {
      await new Promise((resolve) => setTimeout(resolve, 300));
      return { success: true };
    },
  });
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd frontend
npx vitest run src/lib/auth/hooks.test.tsx
```

Expected: 7 tests pass.

- [ ] **Step 5: Commit**

```bash
cd frontend
git add src/lib/auth/hooks.ts src/lib/auth/hooks.test.tsx
git commit -m "feat(auth): add useLogout, useLogoutAll, and useForgotPassword mutations"
```

---

### Task 17: Create `lib/auth/index.ts` barrel and wire `configureApiClient` in providers

**Files:**
- Create: `frontend/src/lib/auth/index.ts`
- Modify: `frontend/src/app/providers.tsx`

- [ ] **Step 1: Create the barrel**

```ts
// frontend/src/lib/auth/index.ts
export {
  useAuth,
  useLogin,
  useRegister,
  useLogout,
  useLogoutAll,
  useForgotPassword,
} from "./hooks";
export { getAccessToken, setAccessToken } from "./session";
export type { AuthUser, AuthSession } from "./session";
```

- [ ] **Step 2: Read the current `providers.tsx`**

```bash
cd frontend
cat src/app/providers.tsx
```

Expected: the existing Task 01-06 file wraps children in `<ThemeProvider>` and `<QueryClientProvider>`. The QueryClient is constructed via `useState(() => new QueryClient(...))`.

- [ ] **Step 3: Wire `configureApiClient` into the QueryClient construction**

Update `providers.tsx` to call `configureApiClient(client)` when the QueryClient is first created. The `useState` initializer is the right place — it runs exactly once on first render:

```tsx
"use client";

import { useState, type ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "next-themes";
import { configureApiClient } from "@/lib/api";

export function Providers({ children }: { children: ReactNode }) {
  const [queryClient] = useState(() => {
    const client = new QueryClient({
      defaultOptions: {
        queries: { staleTime: 60_000, refetchOnWindowFocus: false, retry: 1 },
      },
    });
    // Wire the API client to the QueryClient so the 401 interceptor can
    // update session cache state on failed refresh. See FOOTGUNS §F.3.
    configureApiClient(client);
    return client;
  });

  return (
    <ThemeProvider attribute="class" defaultTheme="dark">
      <QueryClientProvider client={queryClient}>
        {children}
      </QueryClientProvider>
    </ThemeProvider>
  );
}
```

If the existing `providers.tsx` differs in structure, preserve its existing shape and only insert the `configureApiClient(client)` call inside the `useState` initializer.

- [ ] **Step 4: Run the full test suite**

```bash
cd frontend
npm run test
```

Expected: all tests pass. The `providers.tsx` change is wiring-only and doesn't affect existing behavior beyond making the API client aware of the QueryClient.

- [ ] **Step 5: Commit**

```bash
cd frontend
git add src/lib/auth/index.ts src/app/providers.tsx
git commit -m "feat(auth): add lib/auth public barrel and wire configureApiClient in providers"
```

---

## Phase E — New UI primitives

### Task 18: `FormError` component + tests

**Files:**
- Create: `frontend/src/components/ui/FormError.tsx`
- Create: `frontend/src/components/ui/FormError.test.tsx`

`FormError` is a generic form-level error display. Lives in `components/ui/`, NOT `components/auth/` — every future form in the project reuses it. Two lines of CSS, one prop, one conditional.

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/ui/FormError.test.tsx
import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { FormError } from "./FormError";

describe("FormError", () => {
  it("renders nothing when message is undefined", () => {
    const { container } = renderWithProviders(<FormError />);
    expect(container.firstChild).toBeNull();
  });

  it("renders the message in an alert role with error styling", () => {
    renderWithProviders(<FormError message="Email or password is incorrect." />);
    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("Email or password is incorrect.");
    expect(alert.className).toContain("bg-error-container");
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frontend
npx vitest run src/components/ui/FormError.test.tsx
```

Expected: compile failure — `Cannot find module './FormError'`.

- [ ] **Step 3: Create `FormError.tsx`**

```tsx
// frontend/src/components/ui/FormError.tsx

type FormErrorProps = {
  message?: string;
};

/**
 * Generic form-level error display. Used by every form in the project to
 * surface server errors that don't map to a specific field (e.g., network
 * failures, "Email or password is incorrect" on login).
 *
 * Lives in components/ui/ (not components/auth/) because it's a generic
 * primitive — Epic 2's verification form, Epic 3's listing form, etc. all
 * reuse it.
 *
 * Two lines of CSS, one prop, one conditional. Don't let it grow.
 */
export function FormError({ message }: FormErrorProps) {
  if (!message) return null;
  return (
    <div
      role="alert"
      className="rounded-md bg-error-container px-4 py-3 text-label-md text-on-error-container"
    >
      {message}
    </div>
  );
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd frontend
npx vitest run src/components/ui/FormError.test.tsx
```

Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
cd frontend
git add src/components/ui/FormError.tsx src/components/ui/FormError.test.tsx
git commit -m "feat(ui): add FormError generic form-level error display"
```

---

### Task 19: `Checkbox` primitive + tests

**Files:**
- Create: `frontend/src/components/ui/Checkbox.tsx`
- Create: `frontend/src/components/ui/Checkbox.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/ui/Checkbox.test.tsx
import { describe, it, expect } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { Checkbox } from "./Checkbox";

describe("Checkbox", () => {
  it("renders with a label", () => {
    renderWithProviders(<Checkbox label="I agree to the terms" />);
    expect(screen.getByLabelText("I agree to the terms")).toBeInTheDocument();
  });

  it("toggles checked state when clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Checkbox label="Accept" />);
    const checkbox = screen.getByRole("checkbox") as HTMLInputElement;

    expect(checkbox.checked).toBe(false);
    await user.click(checkbox);
    expect(checkbox.checked).toBe(true);
  });

  it("displays an error message when error prop is set", () => {
    renderWithProviders(<Checkbox label="Accept" error="You must accept" />);
    expect(screen.getByText("You must accept")).toBeInTheDocument();
  });

  it("supports React node as label (e.g., embedded link)", () => {
    renderWithProviders(
      <Checkbox label={<>I agree to the <a href="/terms">Terms</a></>} />
    );
    expect(screen.getByText("Terms")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frontend
npx vitest run src/components/ui/Checkbox.test.tsx
```

Expected: compile failure — `Cannot find module './Checkbox'`.

- [ ] **Step 3: Create `Checkbox.tsx`**

```tsx
// frontend/src/components/ui/Checkbox.tsx
import { forwardRef, useId, type InputHTMLAttributes, type ReactNode } from "react";
import { Check } from "lucide-react";
import { cn } from "@/lib/cn";

type CheckboxProps = Omit<InputHTMLAttributes<HTMLInputElement>, "type"> & {
  label: ReactNode;
  error?: string;
};

/**
 * Styled checkbox primitive. Wraps a native <input type="checkbox"> with a
 * custom check icon overlay. API mirrors the existing Input primitive (label,
 * error, forwardRef so react-hook-form's register works).
 *
 * The label can be a React node so consumers can embed inline links:
 *   <Checkbox label={<>I agree to the <Link href="/terms">Terms</Link></>} />
 */
export const Checkbox = forwardRef<HTMLInputElement, CheckboxProps>(
  ({ label, error, className, id, ...props }, ref) => {
    const generatedId = useId();
    const checkboxId = id ?? generatedId;
    return (
      <div className={className}>
        <label
          htmlFor={checkboxId}
          className="flex items-start gap-3 cursor-pointer"
        >
          <div className="relative mt-0.5 shrink-0">
            <input
              ref={ref}
              id={checkboxId}
              type="checkbox"
              className={cn(
                "peer h-5 w-5 appearance-none rounded border-2 border-on-surface-variant/40",
                "bg-surface-container-lowest transition-colors",
                "checked:border-primary checked:bg-primary",
                "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary",
                error && "border-error"
              )}
              {...props}
            />
            <Check
              className="pointer-events-none absolute inset-0 hidden h-5 w-5 text-on-primary peer-checked:block"
              strokeWidth={3}
            />
          </div>
          <span className="text-body-sm text-on-surface">{label}</span>
        </label>
        {error && (
          <p className="ml-8 mt-1 text-label-sm text-error">{error}</p>
        )}
      </div>
    );
  }
);
Checkbox.displayName = "Checkbox";
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd frontend
npx vitest run src/components/ui/Checkbox.test.tsx
```

Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
cd frontend
git add src/components/ui/Checkbox.tsx src/components/ui/Checkbox.test.tsx
git commit -m "feat(ui): add Checkbox primitive with label, error, and forwardRef support"
```

---

### Task 20: `PasswordStrengthIndicator` component + tests

**Files:**
- Create: `frontend/src/components/ui/PasswordStrengthIndicator.tsx`
- Create: `frontend/src/components/ui/PasswordStrengthIndicator.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/ui/PasswordStrengthIndicator.test.tsx
import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { PasswordStrengthIndicator } from "./PasswordStrengthIndicator";

describe("PasswordStrengthIndicator", () => {
  it("renders nothing when password is empty", () => {
    const { container } = renderWithProviders(<PasswordStrengthIndicator password="" />);
    expect(container.firstChild).toBeNull();
  });

  it("shows 'Weak' label and 1 filled bar for a short password", () => {
    renderWithProviders(<PasswordStrengthIndicator password="abc" />);
    expect(screen.getByText("Weak")).toBeInTheDocument();
    const progressbar = screen.getByRole("progressbar");
    expect(progressbar).toHaveAttribute("aria-valuenow", "1");
  });

  it("shows 'Good' label and 3 filled bars for a password meeting the backend regex", () => {
    renderWithProviders(<PasswordStrengthIndicator password="hunter22ab" />);
    expect(screen.getByText("Good")).toBeInTheDocument();
    const progressbar = screen.getByRole("progressbar");
    expect(progressbar).toHaveAttribute("aria-valuenow", "3");
  });

  it("shows 'Strong' label and 4 filled bars for a 14+ character password meeting the regex", () => {
    renderWithProviders(<PasswordStrengthIndicator password="hunter22abcdef" />);
    expect(screen.getByText("Strong")).toBeInTheDocument();
    const progressbar = screen.getByRole("progressbar");
    expect(progressbar).toHaveAttribute("aria-valuenow", "4");
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frontend
npx vitest run src/components/ui/PasswordStrengthIndicator.test.tsx
```

Expected: compile failure — module doesn't exist.

- [ ] **Step 3: Create the component**

```tsx
// frontend/src/components/ui/PasswordStrengthIndicator.tsx
import {
  computePasswordStrength,
  strengthToBars,
  strengthToLabel,
} from "@/lib/auth/passwordStrength";

type PasswordStrengthIndicatorProps = {
  password: string;
};

/**
 * 4-segment password strength bar with a textual label. Computed live on every
 * render from the password value (no debounce — the function is O(n) over a
 * short string).
 *
 * Returns null when the password is empty so the field doesn't bounce up and
 * down as the user starts typing.
 *
 * Visual design matches the Stitch mockups: 4 equal-width segments, h-1,
 * gap-1, rounded-full. Filled segments use bg-primary; empty use bg-primary/20.
 *
 * See spec §7.2.
 */
export function PasswordStrengthIndicator({ password }: PasswordStrengthIndicatorProps) {
  const strength = computePasswordStrength(password);
  const bars = strengthToBars(strength);
  const label = strengthToLabel(strength);

  if (strength === "empty") return null;

  return (
    <div className="mt-2">
      <div
        className="flex gap-1"
        role="progressbar"
        aria-valuenow={bars}
        aria-valuemin={0}
        aria-valuemax={4}
        aria-label={`Password strength: ${label}`}
      >
        {[0, 1, 2, 3].map((i) => (
          <div
            key={i}
            className={`h-1 flex-1 rounded-full ${
              i < bars ? "bg-primary" : "bg-primary/20"
            }`}
          />
        ))}
      </div>
      <p className="mt-1 text-label-sm text-on-surface-variant">
        Strength: <span className="font-semibold text-on-surface">{label}</span>
      </p>
    </div>
  );
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd frontend
npx vitest run src/components/ui/PasswordStrengthIndicator.test.tsx
```

Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
cd frontend
git add src/components/ui/PasswordStrengthIndicator.tsx src/components/ui/PasswordStrengthIndicator.test.tsx
git commit -m "feat(ui): add PasswordStrengthIndicator with 4-segment bar and live label"
```

---

## Phase F — AuthCard layout

### Task 21: `AuthCard` compound component + tests

**Files:**
- Create: `frontend/src/components/auth/AuthCard.tsx`
- Create: `frontend/src/components/auth/AuthCard.test.tsx`

**IMPORTANT before starting:** Read `frontend/src/components/ui/Card.tsx` to find its actual padding value (likely `p-8` or `p-10`). The negative margins in `AuthCard.Footer` MUST match. If the Card uses `p-8`, the Footer uses `-mx-8 -mb-8 px-8`. If `p-10`, use `-mx-10 -mb-10 px-10`. **The values shown below assume `p-10` — verify against the actual file before committing.**

- [ ] **Step 1: Read Card.tsx to confirm its padding**

```bash
cd frontend
grep -E "p-[0-9]" src/components/ui/Card.tsx
```

Note the padding value found. Adjust the negative margins in Step 3 accordingly.

- [ ] **Step 2: Write the failing test**

```tsx
// frontend/src/components/auth/AuthCard.test.tsx
import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { AuthCard } from "./AuthCard";

describe("AuthCard", () => {
  it("always renders the SLPA brand header and tagline", () => {
    renderWithProviders(
      <AuthCard>
        <AuthCard.Body>content</AuthCard.Body>
      </AuthCard>
    );
    expect(screen.getByText("SLPA")).toBeInTheDocument();
    expect(screen.getByText("The Digital Curator")).toBeInTheDocument();
  });

  it("renders Title, Subtitle, Body, and Footer subcomponents", () => {
    renderWithProviders(
      <AuthCard>
        <AuthCard.Title>Create Account</AuthCard.Title>
        <AuthCard.Subtitle>Join the curator</AuthCard.Subtitle>
        <AuthCard.Body>form goes here</AuthCard.Body>
        <AuthCard.Footer>footer link</AuthCard.Footer>
      </AuthCard>
    );
    expect(screen.getByText("Create Account")).toBeInTheDocument();
    expect(screen.getByText("Join the curator")).toBeInTheDocument();
    expect(screen.getByText("form goes here")).toBeInTheDocument();
    expect(screen.getByText("footer link")).toBeInTheDocument();
  });
});
```

- [ ] **Step 3: Run the test to verify it fails**

```bash
cd frontend
npx vitest run src/components/auth/AuthCard.test.tsx
```

Expected: compile failure — module doesn't exist.

- [ ] **Step 4: Create `AuthCard.tsx`**

**REPLACE the `-mx-10 -mb-10 px-10` values below with the actual Card padding match found in Step 1.** If Card uses `p-8`, change to `-mx-8 -mb-8 px-8`. The values shown assume `p-10`.

```tsx
// frontend/src/components/auth/AuthCard.tsx
import type { ReactNode } from "react";
import { Card } from "@/components/ui/Card";

type AuthCardProps = { children: ReactNode };

/**
 * Compound layout component for the three auth pages (register, login,
 * forgot-password). Wraps the existing Card primitive in a centered container
 * with the SLPA brand header always rendered at the top.
 *
 * Compound subcomponents:
 *   <AuthCard.Title />     — h2 headline
 *   <AuthCard.Subtitle />  — p body text
 *   <AuthCard.Body />      — form container with space-y-6 rhythm
 *   <AuthCard.Footer />    — cross-link area with background-shift separator
 *
 * Brand header is hardcoded (not a slot). Forgot-password success state swap
 * is the page's responsibility — the page conditionally renders inside Body.
 *
 * See spec §6.
 */
export function AuthCard({ children }: AuthCardProps) {
  return (
    <div className="mx-auto max-w-md px-4 py-12">
      <div className="mb-8 text-center">
        <h1 className="text-display-sm font-black uppercase tracking-tight text-on-surface">
          SLPA
        </h1>
        <p className="mt-2 text-label-sm uppercase tracking-widest text-on-surface-variant">
          The Digital Curator
        </p>
      </div>
      <Card>{children}</Card>
    </div>
  );
}

function Title({ children }: { children: ReactNode }) {
  return (
    <h2 className="text-headline-sm font-semibold text-on-surface">{children}</h2>
  );
}

function Subtitle({ children }: { children: ReactNode }) {
  return (
    <p className="mt-1 text-body-md text-on-surface-variant">{children}</p>
  );
}

function Body({ children }: { children: ReactNode }) {
  return <div className="mt-6 space-y-6">{children}</div>;
}

function Footer({ children }: { children: ReactNode }) {
  // "No-Line Rule" (DESIGN.md §2): no border-t. Use a background shift instead.
  //
  // CRITICAL: The negative margins MUST match the underlying Card primitive's
  // padding exactly. If Card uses `p-10`, use `-mx-10 -mb-10 px-10`. If `p-8`,
  // use `-mx-8 -mb-8 px-8`. ONE WRONG VALUE and the footer either floats inward
  // or overflows the card horizontally. Verify against Card.tsx before committing.
  return (
    <div className="mt-8 bg-surface-container-low -mx-10 -mb-10 px-10 py-6 text-center text-body-sm text-on-surface-variant">
      {children}
    </div>
  );
}

AuthCard.Title = Title;
AuthCard.Subtitle = Subtitle;
AuthCard.Body = Body;
AuthCard.Footer = Footer;
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
cd frontend
npx vitest run src/components/auth/AuthCard.test.tsx
```

Expected: 2 tests pass.

- [ ] **Step 6: Commit**

```bash
cd frontend
git add src/components/auth/AuthCard.tsx src/components/auth/AuthCard.test.tsx
git commit -m "feat(auth): add AuthCard compound layout component"
```

---

## Phase G — Form components

### Task 22: `RegisterForm` component + tests

**Files:**
- Create: `frontend/src/components/auth/RegisterForm.tsx`
- Create: `frontend/src/components/auth/RegisterForm.test.tsx`

This is the most complex form (4 fields, password strength, terms checkbox, cross-field validation). Tests cover happy path + 3 error paths.

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/auth/RegisterForm.test.tsx
import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { authHandlers } from "@/test/msw/handlers";
import { RegisterForm } from "./RegisterForm";

const mockPush = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: mockPush }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/register",
}));

describe("RegisterForm", () => {
  beforeEach(() => {
    mockPush.mockReset();
  });

  it("submits valid input and redirects to /dashboard on success", async () => {
    const user = userEvent.setup();
    server.use(authHandlers.registerSuccess());

    renderWithProviders(<RegisterForm />);

    await user.type(screen.getByLabelText(/email/i), "new@example.com");
    await user.type(screen.getByLabelText(/^password$/i), "hunter22ab");
    await user.type(screen.getByLabelText(/confirm password/i), "hunter22ab");
    await user.click(screen.getByLabelText(/terms/i));
    await user.click(screen.getByRole("button", { name: /create account/i }));

    await waitFor(() => {
      expect(mockPush).toHaveBeenCalledWith("/dashboard");
    });
  });

  it("shows email-exists error inline when backend returns 409", async () => {
    const user = userEvent.setup();
    server.use(authHandlers.registerEmailExists());

    renderWithProviders(<RegisterForm />);

    await user.type(screen.getByLabelText(/email/i), "taken@example.com");
    await user.type(screen.getByLabelText(/^password$/i), "hunter22ab");
    await user.type(screen.getByLabelText(/confirm password/i), "hunter22ab");
    await user.click(screen.getByLabelText(/terms/i));
    await user.click(screen.getByRole("button", { name: /create account/i }));

    expect(await screen.findByText(/already exists/i)).toBeInTheDocument();
    expect(mockPush).not.toHaveBeenCalled();
  });

  it("blocks submission when passwords don't match", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RegisterForm />);

    await user.type(screen.getByLabelText(/email/i), "test@example.com");
    await user.type(screen.getByLabelText(/^password$/i), "hunter22ab");
    await user.type(screen.getByLabelText(/confirm password/i), "different22ab");
    await user.click(screen.getByLabelText(/terms/i));
    await user.click(screen.getByRole("button", { name: /create account/i }));

    expect(await screen.findByText(/passwords don't match/i)).toBeInTheDocument();
    expect(mockPush).not.toHaveBeenCalled();
  });

  it("blocks submission when terms checkbox is not checked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RegisterForm />);

    await user.type(screen.getByLabelText(/email/i), "test@example.com");
    await user.type(screen.getByLabelText(/^password$/i), "hunter22ab");
    await user.type(screen.getByLabelText(/confirm password/i), "hunter22ab");
    // Skip the terms checkbox
    await user.click(screen.getByRole("button", { name: /create account/i }));

    expect(await screen.findByText(/must accept the terms/i)).toBeInTheDocument();
    expect(mockPush).not.toHaveBeenCalled();
  });

  it("shows the password strength indicator as the user types", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RegisterForm />);

    await user.type(screen.getByLabelText(/^password$/i), "hunter22ab");

    expect(await screen.findByText("Good")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frontend
npx vitest run src/components/auth/RegisterForm.test.tsx
```

Expected: compile failure — module doesn't exist.

- [ ] **Step 3: Create `RegisterForm.tsx`**

```tsx
// frontend/src/components/auth/RegisterForm.tsx
"use client";

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { Input } from "@/components/ui/Input";
import { Button } from "@/components/ui/Button";
import { Checkbox } from "@/components/ui/Checkbox";
import { FormError } from "@/components/ui/FormError";
import { PasswordStrengthIndicator } from "@/components/ui/PasswordStrengthIndicator";
import { useRegister } from "@/lib/auth";
import { registerSchema, type RegisterFormValues } from "@/lib/auth/schemas";
import { mapProblemDetailToForm } from "@/lib/auth/errors";
import { getSafeRedirect } from "@/lib/auth/redirects";

const KNOWN_FIELDS = ["email", "password", "confirmPassword", "terms"] as const;

export function RegisterForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const register = useRegister();

  const form = useForm<RegisterFormValues>({
    resolver: zodResolver(registerSchema),
    mode: "onBlur",
    reValidateMode: "onChange",
    defaultValues: {
      email: "",
      password: "",
      confirmPassword: "",
      terms: false,
    },
  });

  // Watch the password field so the strength indicator updates live.
  const passwordValue = form.watch("password");

  const onSubmit = form.handleSubmit((values) => {
    register.mutate(
      {
        email: values.email,
        password: values.password,
        displayName: null,
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

  const rootError = (form.formState.errors as { root?: { serverError?: { message?: string } } })
    .root?.serverError?.message;

  return (
    <form onSubmit={onSubmit} className="space-y-6" noValidate>
      <FormError message={rootError} />

      <Input
        label="Email"
        type="email"
        autoComplete="email"
        {...form.register("email")}
        error={form.formState.errors.email?.message}
      />

      <div>
        <Input
          label="Password"
          type="password"
          autoComplete="new-password"
          {...form.register("password")}
          error={form.formState.errors.password?.message}
        />
        <PasswordStrengthIndicator password={passwordValue ?? ""} />
      </div>

      <Input
        label="Confirm Password"
        type="password"
        autoComplete="new-password"
        {...form.register("confirmPassword")}
        error={form.formState.errors.confirmPassword?.message}
      />

      <Checkbox
        label={
          <>
            I agree to the{" "}
            <Link href="/terms" className="font-semibold text-primary hover:underline">
              Terms
            </Link>
          </>
        }
        {...form.register("terms")}
        error={form.formState.errors.terms?.message}
      />

      <Button
        type="submit"
        disabled={form.formState.isSubmitting || register.isPending}
        loading={register.isPending}
        fullWidth
      >
        Create Account
      </Button>
    </form>
  );
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd frontend
npx vitest run src/components/auth/RegisterForm.test.tsx
```

Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
cd frontend
git add src/components/auth/RegisterForm.tsx src/components/auth/RegisterForm.test.tsx
git commit -m "feat(auth): add RegisterForm with validation, strength indicator, and ProblemDetail mapping"
```

---

### Task 23: `LoginForm` component + tests

**Files:**
- Create: `frontend/src/components/auth/LoginForm.tsx`
- Create: `frontend/src/components/auth/LoginForm.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/auth/LoginForm.test.tsx
import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { authHandlers } from "@/test/msw/handlers";
import { LoginForm } from "./LoginForm";

const mockPush = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: mockPush }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/login",
}));

describe("LoginForm", () => {
  beforeEach(() => {
    mockPush.mockReset();
  });

  it("submits valid credentials and redirects to /dashboard on success", async () => {
    const user = userEvent.setup();
    server.use(authHandlers.loginSuccess());

    renderWithProviders(<LoginForm />);

    await user.type(screen.getByLabelText(/email/i), "user@example.com");
    await user.type(screen.getByLabelText(/password/i), "anything");
    await user.click(screen.getByRole("button", { name: /sign in/i }));

    await waitFor(() => {
      expect(mockPush).toHaveBeenCalledWith("/dashboard");
    });
  });

  it("shows form-level error on invalid credentials (NOT field-level)", async () => {
    const user = userEvent.setup();
    server.use(authHandlers.loginInvalidCredentials());

    renderWithProviders(<LoginForm />);

    await user.type(screen.getByLabelText(/email/i), "wrong@example.com");
    await user.type(screen.getByLabelText(/password/i), "wrong");
    await user.click(screen.getByRole("button", { name: /sign in/i }));

    // Form-level error appears in the alert region, not under a specific field.
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/incorrect/i);
    expect(mockPush).not.toHaveBeenCalled();
  });

  it("blocks submission with empty fields", async () => {
    const user = userEvent.setup();
    renderWithProviders(<LoginForm />);

    await user.click(screen.getByRole("button", { name: /sign in/i }));

    expect(await screen.findByText(/email is required/i)).toBeInTheDocument();
  });

  it("renders the 'Signed in for 7 days on this device' helper text", () => {
    renderWithProviders(<LoginForm />);
    expect(screen.getByText(/signed in for 7 days on this device/i)).toBeInTheDocument();
  });

  it("redirects to next param after login when present", async () => {
    // Override the default mock to provide a next param.
    vi.doMock("next/navigation", () => ({
      useRouter: () => ({ push: mockPush }),
      useSearchParams: () => new URLSearchParams("next=/auction/42"),
      usePathname: () => "/login",
    }));

    const user = userEvent.setup();
    server.use(authHandlers.loginSuccess());

    // Re-import the component after the doMock so it picks up the new searchParams.
    const { LoginForm: LoginFormReimported } = await import("./LoginForm");

    renderWithProviders(<LoginFormReimported />);
    await user.type(screen.getByLabelText(/email/i), "user@example.com");
    await user.type(screen.getByLabelText(/password/i), "anything");
    await user.click(screen.getByRole("button", { name: /sign in/i }));

    await waitFor(() => {
      expect(mockPush).toHaveBeenCalledWith("/auction/42");
    });

    vi.doUnmock("next/navigation");
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frontend
npx vitest run src/components/auth/LoginForm.test.tsx
```

Expected: compile failure.

- [ ] **Step 3: Create `LoginForm.tsx`**

```tsx
// frontend/src/components/auth/LoginForm.tsx
"use client";

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter, useSearchParams } from "next/navigation";
import { Input } from "@/components/ui/Input";
import { Button } from "@/components/ui/Button";
import { FormError } from "@/components/ui/FormError";
import { useLogin } from "@/lib/auth";
import { loginSchema, type LoginFormValues } from "@/lib/auth/schemas";
import { mapProblemDetailToForm } from "@/lib/auth/errors";
import { getSafeRedirect } from "@/lib/auth/redirects";

const KNOWN_FIELDS = ["email", "password"] as const;

export function LoginForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const login = useLogin();

  const form = useForm<LoginFormValues>({
    resolver: zodResolver(loginSchema),
    mode: "onBlur",
    reValidateMode: "onChange",
    defaultValues: { email: "", password: "" },
  });

  const onSubmit = form.handleSubmit((values) => {
    login.mutate(values, {
      onSuccess: () => {
        const next = getSafeRedirect(searchParams.get("next"));
        router.push(next);
      },
      onError: (error) => {
        mapProblemDetailToForm(error, form, KNOWN_FIELDS);
      },
    });
  });

  const rootError = (form.formState.errors as { root?: { serverError?: { message?: string } } })
    .root?.serverError?.message;

  return (
    <form onSubmit={onSubmit} className="space-y-6" noValidate>
      <FormError message={rootError} />

      <Input
        label="Email"
        type="email"
        autoComplete="email"
        {...form.register("email")}
        error={form.formState.errors.email?.message}
      />

      <Input
        label="Password"
        type="password"
        autoComplete="current-password"
        {...form.register("password")}
        error={form.formState.errors.password?.message}
      />

      <p className="text-label-sm text-on-surface-variant">
        Signed in for 7 days on this device
      </p>

      <Button
        type="submit"
        disabled={form.formState.isSubmitting || login.isPending}
        loading={login.isPending}
        fullWidth
      >
        Sign In
      </Button>
    </form>
  );
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd frontend
npx vitest run src/components/auth/LoginForm.test.tsx
```

Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
cd frontend
git add src/components/auth/LoginForm.tsx src/components/auth/LoginForm.test.tsx
git commit -m "feat(auth): add LoginForm with form-level error display and next-redirect"
```

---

### Task 24: `ForgotPasswordForm` component + tests

**Files:**
- Create: `frontend/src/components/auth/ForgotPasswordForm.tsx`
- Create: `frontend/src/components/auth/ForgotPasswordForm.test.tsx`

The forgot-password form has a success state swap inside the same component. The success state renders a `[STUB]` indicator banner because no real backend endpoint exists yet.

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/auth/ForgotPasswordForm.test.tsx
import { describe, it, expect } from "vitest";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { ForgotPasswordForm } from "./ForgotPasswordForm";

describe("ForgotPasswordForm", () => {
  it("submits an email and shows the success state on resolution", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ForgotPasswordForm />);

    await user.type(screen.getByLabelText(/email/i), "user@example.com");
    await user.click(screen.getByRole("button", { name: /send reset link/i }));

    expect(await screen.findByText(/check your email/i)).toBeInTheDocument();
  });

  it("renders the [STUB] indicator banner in the success state", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ForgotPasswordForm />);

    await user.type(screen.getByLabelText(/email/i), "user@example.com");
    await user.click(screen.getByRole("button", { name: /send reset link/i }));

    await waitFor(() => {
      expect(screen.getByText(/\[STUB\]/i)).toBeInTheDocument();
    });
  });

  it("blocks submission for invalid email", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ForgotPasswordForm />);

    await user.type(screen.getByLabelText(/email/i), "not-an-email");
    await user.click(screen.getByRole("button", { name: /send reset link/i }));

    expect(await screen.findByText(/valid email/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frontend
npx vitest run src/components/auth/ForgotPasswordForm.test.tsx
```

Expected: compile failure.

- [ ] **Step 3: Create `ForgotPasswordForm.tsx`**

```tsx
// frontend/src/components/auth/ForgotPasswordForm.tsx
"use client";

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Input } from "@/components/ui/Input";
import { Button } from "@/components/ui/Button";
import { FormError } from "@/components/ui/FormError";
import { useForgotPassword } from "@/lib/auth";
import {
  forgotPasswordSchema,
  type ForgotPasswordFormValues,
} from "@/lib/auth/schemas";

// STUB: no backend password-reset endpoint exists yet. The success state below
// is UI-only — NO EMAIL IS ACTUALLY SENT. When the real endpoint ships, follow
// the four-step swap documented on `useForgotPassword` in lib/auth/hooks.ts.

export function ForgotPasswordForm() {
  const forgotPassword = useForgotPassword();

  const form = useForm<ForgotPasswordFormValues>({
    resolver: zodResolver(forgotPasswordSchema),
    mode: "onBlur",
    reValidateMode: "onChange",
    defaultValues: { email: "" },
  });

  const onSubmit = form.handleSubmit((values) => {
    forgotPassword.mutate(values.email);
  });

  const rootError = (form.formState.errors as { root?: { serverError?: { message?: string } } })
    .root?.serverError?.message;

  if (forgotPassword.isSuccess) {
    return (
      <div className="space-y-4 text-center">
        <div className="mb-4 rounded-md bg-tertiary-container px-3 py-2 text-label-sm text-on-tertiary-container">
          <strong>[STUB]</strong> Backend password-reset endpoint not yet
          implemented. No email will arrive. This success state is UI-only for
          the current task.
        </div>
        <h3 className="text-headline-sm font-semibold text-on-surface">
          Check your email
        </h3>
        <p className="text-body-md text-on-surface-variant">
          If an account exists, we've sent a password reset link to your inbox.
        </p>
      </div>
    );
  }

  return (
    <form onSubmit={onSubmit} className="space-y-6" noValidate>
      <FormError message={rootError} />

      <Input
        label="Email"
        type="email"
        autoComplete="email"
        {...form.register("email")}
        error={form.formState.errors.email?.message}
      />

      <Button
        type="submit"
        disabled={form.formState.isSubmitting || forgotPassword.isPending}
        loading={forgotPassword.isPending}
        fullWidth
      >
        Send Reset Link
      </Button>
    </form>
  );
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd frontend
npx vitest run src/components/auth/ForgotPasswordForm.test.tsx
```

Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
cd frontend
git add src/components/auth/ForgotPasswordForm.tsx src/components/auth/ForgotPasswordForm.test.tsx
git commit -m "feat(auth): add ForgotPasswordForm with [STUB] success state indicator"
```

---

## Phase H — Pages + RequireAuth

### Task 25: `RequireAuth` wrapper component + tests

**Files:**
- Create: `frontend/src/components/auth/RequireAuth.tsx`
- Create: `frontend/src/components/auth/RequireAuth.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/auth/RequireAuth.test.tsx
import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { authHandlers } from "@/test/msw/handlers";
import { RequireAuth } from "./RequireAuth";

const mockPush = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: mockPush }),
  usePathname: () => "/dashboard",
}));

describe("RequireAuth", () => {
  beforeEach(() => {
    mockPush.mockReset();
  });

  it("renders a loading spinner while session is loading", () => {
    server.use(authHandlers.refreshUnauthenticated());
    const { container } = renderWithProviders(
      <RequireAuth>
        <div>protected content</div>
      </RequireAuth>
    );
    // The loading state renders the spinner div before the bootstrap resolves.
    expect(container.querySelector(".animate-spin")).toBeInTheDocument();
  });

  it("redirects to /login?next=... when unauthenticated", async () => {
    server.use(authHandlers.refreshUnauthenticated());
    renderWithProviders(
      <RequireAuth>
        <div>protected content</div>
      </RequireAuth>
    );

    await waitFor(() => {
      expect(mockPush).toHaveBeenCalledWith("/login?next=%2Fdashboard");
    });
    expect(screen.queryByText("protected content")).not.toBeInTheDocument();
  });

  it("renders children when authenticated", async () => {
    server.use(authHandlers.refreshSuccess());
    renderWithProviders(
      <RequireAuth>
        <div>protected content</div>
      </RequireAuth>
    );

    expect(await screen.findByText("protected content")).toBeInTheDocument();
    expect(mockPush).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frontend
npx vitest run src/components/auth/RequireAuth.test.tsx
```

Expected: compile failure.

- [ ] **Step 3: Create `RequireAuth.tsx`**

```tsx
// frontend/src/components/auth/RequireAuth.tsx
"use client";

import { useEffect, type ReactNode } from "react";
import { useRouter, usePathname } from "next/navigation";
import { useAuth } from "@/lib/auth";

type RequireAuthProps = {
  children: ReactNode;
};

/**
 * Client-side guard for protected pages. Wraps children in a session check
 * and either renders them, redirects to /login, or shows a loading spinner.
 *
 * Three states from useAuth():
 *   - loading → centered spinner placeholder
 *   - unauthenticated → redirect via useEffect, render null in the meantime
 *   - authenticated → render children
 *
 * The redirect preserves the current pathname as a `next` query param so the
 * user lands back where they were after a successful login. The login form
 * uses `getSafeRedirect` to validate the param against open-redirect attacks.
 *
 * See spec §8 and FOOTGUNS §F.X (open-redirect guard).
 */
export function RequireAuth({ children }: RequireAuthProps) {
  const session = useAuth();
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    if (session.status === "unauthenticated") {
      const next = encodeURIComponent(pathname);
      router.push(`/login?next=${next}`);
    }
  }, [session.status, pathname, router]);

  if (session.status === "loading") {
    return (
      <div className="flex min-h-[60vh] items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent" />
      </div>
    );
  }

  if (session.status === "unauthenticated") {
    // Redirect is in-flight via useEffect. Render null to avoid flashing the
    // protected content before the navigation resolves.
    return null;
  }

  return <>{children}</>;
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd frontend
npx vitest run src/components/auth/RequireAuth.test.tsx
```

Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
cd frontend
git add src/components/auth/RequireAuth.tsx src/components/auth/RequireAuth.test.tsx
git commit -m "feat(auth): add RequireAuth client-side guard for protected pages"
```

---

### Task 26: Rewrite `/register` page

**Files:**
- Modify: `frontend/src/app/register/page.tsx`

- [ ] **Step 1: Replace the placeholder with the real composition**

```tsx
// frontend/src/app/register/page.tsx
"use client";

import Link from "next/link";
import { AuthCard } from "@/components/auth/AuthCard";
import { RegisterForm } from "@/components/auth/RegisterForm";

export default function RegisterPage() {
  return (
    <AuthCard>
      <AuthCard.Title>Create Your Account</AuthCard.Title>
      <AuthCard.Subtitle>Join the digital curator.</AuthCard.Subtitle>
      <AuthCard.Body>
        <RegisterForm />
      </AuthCard.Body>
      <AuthCard.Footer>
        Already an esteemed member?{" "}
        <Link href="/login" className="font-semibold text-primary hover:underline">
          Sign In
        </Link>
      </AuthCard.Footer>
    </AuthCard>
  );
}
```

This page is `"use client"` because `RegisterForm` uses hooks and the page composes it directly. The Task 01-06 RSC placeholder (rendering `<PageHeader />`) is replaced entirely.

- [ ] **Step 2: Run the existing test suite**

```bash
cd frontend
npm run test
```

Expected: all tests still pass. The page change doesn't affect any existing test.

- [ ] **Step 3: Commit**

```bash
cd frontend
git add src/app/register/page.tsx
git commit -m "feat(auth): replace /register placeholder with real form composition"
```

---

### Task 27: Rewrite `/login` page

**Files:**
- Modify: `frontend/src/app/login/page.tsx`

- [ ] **Step 1: Replace the placeholder**

```tsx
// frontend/src/app/login/page.tsx
"use client";

import Link from "next/link";
import { AuthCard } from "@/components/auth/AuthCard";
import { LoginForm } from "@/components/auth/LoginForm";

export default function LoginPage() {
  return (
    <AuthCard>
      <AuthCard.Title>Welcome Back</AuthCard.Title>
      <AuthCard.Subtitle>Sign in to your SLPA account.</AuthCard.Subtitle>
      <AuthCard.Body>
        <LoginForm />
        <div className="text-center">
          <Link
            href="/forgot-password"
            className="text-label-md text-primary hover:underline"
          >
            Forgot your password?
          </Link>
        </div>
      </AuthCard.Body>
      <AuthCard.Footer>
        New to the curator?{" "}
        <Link href="/register" className="font-semibold text-primary hover:underline">
          Request Membership
        </Link>
      </AuthCard.Footer>
    </AuthCard>
  );
}
```

The "Forgot your password?" link sits inside the body (below the form) per the Stitch design. The `AuthCard.Footer` carries the cross-link to register.

- [ ] **Step 2: Run the test suite**

```bash
cd frontend
npm run test
```

Expected: all tests pass.

- [ ] **Step 3: Commit**

```bash
cd frontend
git add src/app/login/page.tsx
git commit -m "feat(auth): replace /login placeholder with real form composition"
```

---

### Task 28: Rewrite `/forgot-password` page

**Files:**
- Modify: `frontend/src/app/forgot-password/page.tsx`

- [ ] **Step 1: Replace the placeholder**

```tsx
// frontend/src/app/forgot-password/page.tsx
"use client";

import Link from "next/link";
import { AuthCard } from "@/components/auth/AuthCard";
import { ForgotPasswordForm } from "@/components/auth/ForgotPasswordForm";

// STUB: ForgotPasswordForm renders a [STUB] indicator banner in its success
// state because no backend password-reset endpoint exists yet. See the four-
// step swap documented on `useForgotPassword` in lib/auth/hooks.ts.

export default function ForgotPasswordPage() {
  return (
    <AuthCard>
      <AuthCard.Title>Forgot Your Password?</AuthCard.Title>
      <AuthCard.Subtitle>
        Enter your email and we'll send you a reset link.
      </AuthCard.Subtitle>
      <AuthCard.Body>
        <ForgotPasswordForm />
      </AuthCard.Body>
      <AuthCard.Footer>
        Remember it after all?{" "}
        <Link href="/login" className="font-semibold text-primary hover:underline">
          Back to Sign In
        </Link>
      </AuthCard.Footer>
    </AuthCard>
  );
}
```

- [ ] **Step 2: Run the test suite**

```bash
cd frontend
npm run test
```

Expected: all tests pass.

- [ ] **Step 3: Commit**

```bash
cd frontend
git add src/app/forgot-password/page.tsx
git commit -m "feat(auth): replace /forgot-password placeholder with real form composition"
```

---

### Task 29: Rewrite `/dashboard` page with RequireAuth

**Files:**
- Modify: `frontend/src/app/dashboard/page.tsx`

- [ ] **Step 1: Replace the placeholder**

```tsx
// frontend/src/app/dashboard/page.tsx
"use client";

import { RequireAuth } from "@/components/auth/RequireAuth";
import { useAuth } from "@/lib/auth";
import { PageHeader } from "@/components/layout/PageHeader";

export default function DashboardPage() {
  return (
    <RequireAuth>
      <DashboardContent />
    </RequireAuth>
  );
}

function DashboardContent() {
  const session = useAuth();
  // RequireAuth guarantees this component only renders when authenticated,
  // but TypeScript doesn't know that — narrow the union explicitly.
  if (session.status !== "authenticated") return null;

  return (
    <>
      <PageHeader
        title="Dashboard"
        subtitle={`Signed in as ${session.user.email}`}
      />
      <div className="mx-auto max-w-4xl px-4 py-8">
        <p className="text-body-md text-on-surface-variant">
          Your bids, listings, and sales will appear here. Real dashboard
          content lands in a future task.
        </p>
      </div>
    </>
  );
}
```

Minimal placeholder content — real dashboard widgets are out of scope for this task. The `RequireAuth` wrapper is the load-bearing piece; the placeholder content exists only so the page renders something.

- [ ] **Step 2: Run the test suite**

```bash
cd frontend
npm run test
```

Expected: all tests pass.

- [ ] **Step 3: Commit**

```bash
cd frontend
git add src/app/dashboard/page.tsx
git commit -m "feat(auth): wire /dashboard with RequireAuth and minimal authenticated content"
```

---

## Phase I — Header authenticated state

### Task 30: `UserMenuDropdown` component + tests

**Files:**
- Create: `frontend/src/components/auth/UserMenuDropdown.tsx`
- Create: `frontend/src/components/auth/UserMenuDropdown.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/auth/UserMenuDropdown.test.tsx
import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { authHandlers } from "@/test/msw/handlers";
import { UserMenuDropdown } from "./UserMenuDropdown";
import type { AuthUser } from "@/lib/auth";

const mockPush = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: mockPush }),
}));

const mockUser: AuthUser = {
  id: 42,
  email: "alice@example.com",
  displayName: null,
  slAvatarUuid: null,
  verified: false,
};

describe("UserMenuDropdown", () => {
  beforeEach(() => {
    mockPush.mockReset();
  });

  it("renders a trigger button with the user's display label", () => {
    renderWithProviders(<UserMenuDropdown user={mockUser} />);
    // displayName is null → falls back to email local-part.
    expect(screen.getByText("alice")).toBeInTheDocument();
  });

  it("opens the dropdown menu and shows Sign Out", async () => {
    const user = userEvent.setup();
    renderWithProviders(<UserMenuDropdown user={mockUser} />);

    await user.click(screen.getByRole("button", { name: /alice/i }));

    expect(await screen.findByText(/sign out/i)).toBeInTheDocument();
  });

  it("triggers logout mutation and redirects to / on Sign Out click", async () => {
    const user = userEvent.setup();
    server.use(authHandlers.logoutSuccess());

    renderWithProviders(<UserMenuDropdown user={mockUser} />);

    await user.click(screen.getByRole("button", { name: /alice/i }));
    await user.click(await screen.findByText(/sign out/i));

    await waitFor(() => {
      expect(mockPush).toHaveBeenCalledWith("/");
    });
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frontend
npx vitest run src/components/auth/UserMenuDropdown.test.tsx
```

Expected: compile failure.

- [ ] **Step 3: Create `UserMenuDropdown.tsx`**

```tsx
// frontend/src/components/auth/UserMenuDropdown.tsx
"use client";

import { Dropdown } from "@/components/ui/Dropdown";
import { Avatar } from "@/components/ui/Avatar";
import { useLogout } from "@/lib/auth";
import type { AuthUser } from "@/lib/auth";

type UserMenuDropdownProps = {
  user: AuthUser;
};

/**
 * Header's authenticated branch. Renders an avatar + display label trigger
 * that opens a dropdown menu with Dashboard, Profile, and Sign Out items.
 *
 * `displayName` is nullable on register (Task 01-08 sends null because the
 * register form has no displayName field). Fall back to the email local-part
 * when displayName is null. Profile-edit task fills in the real displayName
 * later.
 *
 * Sign out all sessions is NOT in the dropdown (scope creep — useLogoutAll
 * exists for a future account-settings page).
 */
export function UserMenuDropdown({ user }: UserMenuDropdownProps) {
  const logout = useLogout();
  const displayLabel = user.displayName ?? user.email.split("@")[0];

  return (
    <Dropdown
      trigger={
        <button
          type="button"
          className="flex items-center gap-2 rounded-full p-1 hover:bg-surface-container-low"
          aria-label={`User menu for ${displayLabel}`}
        >
          <Avatar name={displayLabel} />
          <span className="hidden text-body-sm text-on-surface sm:inline">
            {displayLabel}
          </span>
        </button>
      }
    >
      <Dropdown.Item href="/dashboard">Dashboard</Dropdown.Item>
      <Dropdown.Item href="/profile">Profile</Dropdown.Item>
      <Dropdown.Separator />
      <Dropdown.Item
        onSelect={() => logout.mutate()}
        disabled={logout.isPending}
      >
        Sign Out
      </Dropdown.Item>
    </Dropdown>
  );
}
```

**IMPORTANT:** the `Dropdown` and `Avatar` primitive APIs may differ slightly from what's shown above (the trigger prop, `Dropdown.Item`'s `href` and `onSelect` props, `Avatar`'s `name` prop). Read `frontend/src/components/ui/Dropdown.tsx` and `Avatar.tsx` from Task 01-06 and adjust the JSX to match the actual API. The intent is unchanged: a trigger button that opens a menu with three items (Dashboard, Profile, Sign Out) plus a separator.

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd frontend
npx vitest run src/components/auth/UserMenuDropdown.test.tsx
```

Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
cd frontend
git add src/components/auth/UserMenuDropdown.tsx src/components/auth/UserMenuDropdown.test.tsx
git commit -m "feat(auth): add UserMenuDropdown for the Header authenticated branch"
```

---

### Task 31: Wire `Header` to render `UserMenuDropdown` for authenticated users

**Files:**
- Modify: `frontend/src/components/layout/Header.tsx`
- Modify: `frontend/src/components/layout/Header.test.tsx`

The Task 01-06 `Header` already has three auth branches stubbed: loading (renders null for the auth cluster), authenticated (placeholder), and unauthenticated (Sign In + Create Account buttons). Task 01-08 wires the authenticated branch to `<UserMenuDropdown />`.

- [ ] **Step 1: Read the current Header to find the authenticated branch**

```bash
cd frontend
grep -n "status === " src/components/layout/Header.tsx
```

Expected: matches on the three branches (`"loading"`, `"authenticated"`, `"unauthenticated"`).

- [ ] **Step 2: Replace the authenticated branch placeholder**

Find the block in `Header.tsx` that renders when `session.status === "authenticated"` and replace its content with `<UserMenuDropdown user={session.user} />`. Add the import:

```tsx
import { UserMenuDropdown } from "@/components/auth/UserMenuDropdown";
```

The exact diff depends on the current placeholder shape. The intent is: when the Header is rendering the authenticated cluster, instead of (probably) `<button>Account</button>` or similar, render `<UserMenuDropdown user={session.user} />`. The unauthenticated branch (Sign In / Create Account buttons) and the loading branch (null) are unchanged.

- [ ] **Step 3: Update `Header.test.tsx` to assert the new authenticated branch**

Find the existing Header tests for the three auth branches. Update the authenticated test to assert that `UserMenuDropdown` (or its visible output — the email local-part as a button label) is rendered instead of the placeholder text. The unauthenticated and loading test cases stay unchanged.

Specifically, update the authenticated test:

```tsx
it("renders the user menu dropdown when authenticated", async () => {
  // Override the default MSW handler to return an authenticated session.
  server.use(authHandlers.refreshSuccess());

  renderWithProviders(<Header />);

  // Wait for the bootstrap to resolve and the authenticated branch to render.
  expect(await screen.findByRole("button", { name: /user menu/i })).toBeInTheDocument();
});
```

- [ ] **Step 4: Run the Header tests**

```bash
cd frontend
npx vitest run src/components/layout/Header.test.tsx
```

Expected: all Header tests pass, including the updated authenticated branch.

- [ ] **Step 5: Run the full test suite**

```bash
cd frontend
npm run test
```

Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
cd frontend
git add src/components/layout/Header.tsx src/components/layout/Header.test.tsx
git commit -m "feat(layout): wire Header authenticated branch to UserMenuDropdown"
```

---

## Phase J — Finalization

### Task 32: Add FOOTGUNS §5.9 meta-lesson and §F frontend section

**Files:**
- Modify: `docs/implementation/FOOTGUNS.md`

This is the equivalent of Task 01-07's Task 35 — adds the meta-lesson and the new top-level section in one commit.

- [ ] **Step 1: Read the current FOOTGUNS.md to find §5.8 (the last §5 entry)**

```bash
cd /c/Users/heath/Repos/Personal/slpa-task-01-08
grep -n "^### 5\." docs/implementation/FOOTGUNS.md
```

Expected: matches `### 5.1` through `### 5.8`.

- [ ] **Step 2: Append `### 5.9` after the last §5 entry**

```markdown
### 5.9 Stale briefs are a drift source — patch N+1 briefs when Task N lands contradicting decisions

**Why:** When Task N's spec or plan locks a decision that contradicts the original brief for Task N+1, patch the N+1 brief in the same PR. Otherwise N+1's implementer walks into a pre-resolved conflict and wastes a brainstorm round re-discovering the answer.

**Caught at brainstorm time in Task 01-08.** Task 01-07 locked in-memory access tokens + HttpOnly refresh cookies three days before Task 01-08's brainstorm, but Task 01-08's brief still said "localStorage or httpOnly cookie" and "remember me checkbox controls localStorage vs sessionStorage." The brainstorm spent the entire Q1 round re-litigating a resolved decision before reaching the same conclusion Task 01-07 had already documented.

**How to apply:**
- When shipping a spec that contradicts an upstream brief for a dependent task, grep for the dependent task's brief in the same PR and patch any statements that no longer apply.
- Leave a one-line `> **Brief updated post-Task N-1:** <description>` note so the N+1 reader knows a correction happened.
- This is a controller-side discipline rule, not a per-task footgun. It applies to every cross-task dependency.

This is filed under §5 (project conventions) because it's a process rule that applies to every future task handoff, not a domain-specific gotcha.
```

- [ ] **Step 3: Find the existing top-level section divider after §6 (or wherever the file ends) and append the §F frontend section**

Look for the last `## §` heading in the file. After it (and after any closing `---` divider), append the new top-level section:

```markdown
---

## §F. Frontend / React / Next.js / Auth

Frontend-domain footguns. Numbered §F.1, §F.2, etc. to keep the namespace separate from the existing frontend domain-shaped sections (§1–§6) and the backend section (§B). When the frontend ledger grows larger, sub-sections may mirror the backend pattern (§F.1, §F.2, ...) or use a domain-flat list — both work.

### F.1 Access token lives in a module-level `let`, not a React `useRef`

**Why:** The API client interceptor in `lib/api.ts` runs outside React's lifecycle. React refs (`useRef`) are per-component and die on unmount; they can't be read from a non-React context. A module-level `let` in `lib/auth/session.ts` is the correct container for state that the interceptor reads synchronously and mutations write synchronously.

**How to apply:**
- Only `getAccessToken` and `setAccessToken` are exported. The `accessToken` variable itself is module-private.
- A grep for `accessToken = ` outside `session.ts` catches any mutation path that bypasses the setter — code review enforces the rule until a lint check ships.

### F.2 The session query's side effect (setting the access token) lives inside `queryFn`, not `onSuccess`

**Why:** TanStack Query's first subscriber receives `queryFn`'s return value directly — `onSuccess` only fires on subsequent subscribers and refetches. If `setAccessToken(response.accessToken)` lives in `onSuccess`, the first component to call `useAuth()` gets the user but the access token ref stays null until the next refetch, which may never happen with `staleTime: Infinity`.

**How to apply:** Place the side effect inside `queryFn` BEFORE returning the user value. The first-subscribe path will then update the token synchronously with the query cache. `bootstrapSession()` in `lib/auth/hooks.ts` is the canonical example.

### F.3 `configureApiClient(queryClient)` — pass the QueryClient via setup function, not module global

**Why:** The 401 interceptor in `lib/api.ts` needs to update the session query cache on failed refresh. Storing the QueryClient as a module global creates import-order footguns — the API client module might initialize before the QueryClient exists. A setup function called once at app mount makes the dependency explicit and testable.

**How to apply:** `app/providers.tsx` calls `configureApiClient(client)` inside the `useState` initializer when the QueryClient is constructed. The API client stores it at module scope. Tests construct their own QueryClient and call `configureApiClient` in `beforeEach`.

### F.4 Concurrent 401 stampede — share one in-flight refresh promise

**Why:** If three requests return 401 simultaneously, the naive interceptor fires three `/refresh` calls. Deduplicate via a single `inFlightRefresh: Promise<void> | null` at module scope. The first 401 starts the refresh and stores the promise; subsequent 401s await the same promise; all retry after it resolves.

**How to apply:** The cleanup (`inFlightRefresh = null`) lives INSIDE the IIFE's `try/finally` so only the creator clears the ref, not every awaiter racing to null it out. JS microtask ordering makes the per-awaiter pattern safe in practice, but having one code path own the cleanup is structurally correct and easier to reason about. See `handleUnauthorized` in `lib/api.ts`.

### F.5 `staleTime: Infinity` + `gcTime: Infinity` + `retry: false` on the session query — all three are load-bearing

**Why:**
- `staleTime: Infinity` prevents auto-refetching the session on every `useAuth()` call. Without it, TanStack Query considers the query stale after 5 minutes and re-fetches.
- `gcTime: Infinity` prevents garbage collection if `Header` unmounts temporarily.
- `retry: false` prevents three `/refresh` calls on a fresh visit — a 401 on bootstrap is a legitimate unauthenticated state, not a transient error.

**How to apply:** A contributor "tuning" these based on standard React Query advice would break the auth layer. A comment block above the `useQuery` call in `lib/auth/hooks.ts` explains each flag.

### F.6 `mapProblemDetailToForm` — `errors[]` is an array of `{field, message}`, not an object

**Why:** The backend's `AuthExceptionHandler` and `GlobalExceptionHandler` (Task 01-07 §9) emit `errors` as an array of `{field, message}` objects, not an object map. The helper must iterate the array.

**How to apply:** Check `Array.isArray(problem.errors)` before iterating. Don't assume the object shape from a single example. The helper in `lib/auth/errors.ts` is the canonical implementation.

### F.7 `mapProblemDetailToForm` — unknown-field guard with console warn in dev

**Why:** If the backend returns a `VALIDATION_FAILED` with a field that doesn't exist on the form, silent dropping hides drift between backend validators and frontend form fields.

**How to apply:** Fall back to `root.serverError` with the concatenated message and `console.warn` in dev (`process.env.NODE_ENV !== "production"`). The warn is a drift-detection mechanism — the form tells you "something new is being validated that you don't know about" rather than failing silently. See `mapProblemDetailToForm` in `lib/auth/errors.ts`.

### F.8 `onUnhandledRequest: "error"` in MSW setup is load-bearing

**Why:** A future contributor who switches this to `"warn"` or `"bypass"` to make a flaky test pass has silently allowed real network requests from tests. That's the same failure mode as deleting a canary integration test — it masks a real integration gap.

**How to apply:** Do not relax it. If a test is flaky because a handler is missing, add the handler; don't widen the escape hatch. `vitest.setup.ts` calls `server.listen({ onUnhandledRequest: "error" })` and this must not change.

### F.9 The 401-auto-refresh canary is the frontend security model

**Why:** The integration test in `lib/api.401-interceptor.test.tsx` verifies "401 on protected endpoint → auto-refresh → retry → success." It also covers stampede protection and refresh-failure-redirect. These three tests together prove the API client's self-healing behavior works.

**How to apply:** **Never delete. Never quarantine.** If the canary fails, debug the interceptor — don't disable the test. The frontend equivalent of Task 01-07's `refreshTokenReuseCascade` integration test.

### F.10 `useLogoutAll` must refresh before calling the endpoint

**Why:** `POST /api/auth/logout-all` requires a valid access token (it's protected). If the user's access token is already expired when they click "Sign out all sessions," the call fails with 401 and the interceptor triggers a refresh anyway — but the user sees a flicker. Cleaner: the `useLogoutAll` hook always calls `/refresh` first, gets a fresh access token, then calls `/logout-all`.

**How to apply:** Refresh is cheap (~100ms). The hook in `lib/auth/hooks.ts` does both calls in the `mutationFn`.

### F.11 `onSettled` for logout, not `onSuccess`

**Why:** Logout should clear local state even if the network call fails. A user clicking "Sign Out" expects to be logged out regardless of whether the backend acknowledged the POST.

**How to apply:** Place `setAccessToken(null)`, `setQueryData(null)`, `removeQueries`, and `router.push("/")` in `onSettled` (not `onSuccess`). All four operations are idempotent.

### F.12 `onSuccess` on login/register uses `setQueryData`, not `invalidateQueries`

**Why:** `invalidateQueries` would trigger a wasted `/refresh` round-trip immediately after login. `setQueryData(["auth", "session"], response.user)` directly seats the cached value, so `Header` re-renders instantly without a network call.

**How to apply:** Use `invalidateQueries` only when you actually want to refetch from the server. For login/register, the response body already contains the user — there's nothing to refetch.

### F.13 DESIGN.md §2 "No-Line Rule" wins over Stitch HTML

**Why:** The Stitch design HTML often contains `border-t` or `border-b` classes that violate DESIGN.md §2. When a section separator is needed, use a background-color shift (e.g., `bg-surface-container-low`) or vertical spacing (`pt-6`) instead of a border.

**How to apply:** The Stitch HTML is a mockup; DESIGN.md is the rulebook. Example: `AuthCard.Footer` must not use `border-t border-outline-variant/10` even though the Stitch HTML shows it. Use the `bg-surface-container-low` strip with negative margins matching the underlying Card's padding (verify against `Card.tsx` before committing — wrong values cause horizontal overflow).
```

- [ ] **Step 3: Verify the file parses as markdown**

```bash
cd /c/Users/heath/Repos/Personal/slpa-task-01-08
head -5 docs/implementation/FOOTGUNS.md
grep -n "^### 5\.9\|^## §F\|^### F\." docs/implementation/FOOTGUNS.md
```

Expected:
- One match for `### 5.9`
- One match for `## §F`
- 13 matches for `### F.1` through `### F.13`

Spot-check the file: no broken markdown fences, no half-inserted blocks.

- [ ] **Step 4: Commit**

```bash
cd /c/Users/heath/Repos/Personal/slpa-task-01-08
git add docs/implementation/FOOTGUNS.md
git commit -m "docs(footguns): add §5.9 stale-brief meta-lesson and §F frontend section"
```

---

### Task 33: Sweep root README.md for frontend auth updates

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Find the frontend section**

```bash
cd /c/Users/heath/Repos/Personal/slpa-task-01-08
grep -n "frontend\|Frontend" README.md | head -10
```

- [ ] **Step 2: Update the test count and add an auth-pages mention**

Find the existing line documenting frontend test count (Task 01-06 said "~67 cases") and update it. Find the existing frontend description block and append a sentence about auth pages.

The exact diff depends on what's in the README now. The two updates:

1. Update the test count line in the "Running tests" section:

```markdown
cd frontend && npm run test           # vitest unit + integration tests (~114 cases — primitives, forms, auth flows)
```

2. Add a sentence about auth pages somewhere in the frontend section:

```markdown
The frontend has three auth pages (`/register`, `/login`, `/forgot-password`) wired to the backend JWT auth endpoints from Task 01-07. Forms use react-hook-form + zod with backend ProblemDetail error mapping. Tests run against MSW mocks; the canary `lib/api.401-interceptor.test.tsx` proves the API client's auto-refresh-and-retry behavior. See [`docs/superpowers/specs/2026-04-12-task-01-08-frontend-auth-design.md`](docs/superpowers/specs/2026-04-12-task-01-08-frontend-auth-design.md) for the full design.
```

- [ ] **Step 3: Commit**

```bash
cd /c/Users/heath/Repos/Personal/slpa-task-01-08
git add README.md
git commit -m "docs(readme): document frontend auth pages and MSW test infrastructure"
```

---

### Task 34: Final verification

**Files:**
- None (verification only)

- [ ] **Step 1: Run the full test suite**

```bash
cd /c/Users/heath/Repos/Personal/slpa-task-01-08/frontend
npm run test
```

Expected: ~114 tests passing (67 baseline + ~47 new from Task 01-08). Test count breakdown:
- Unit (~15): schemas, errors, passwordStrength, redirects, session
- Component (~12): FormError, Checkbox, PasswordStrengthIndicator, AuthCard, RequireAuth
- Integration (~20): RegisterForm, LoginForm, ForgotPasswordForm, useAuth/useLogin/useRegister/useLogout/useForgotPassword, Header, **401 canary (3)**

- [ ] **Step 2: Run lint**

```bash
cd /c/Users/heath/Repos/Personal/slpa-task-01-08/frontend
npm run lint
```

Expected: clean. Any warnings/errors must be resolved before committing the verification task.

- [ ] **Step 3: Run the verify chain (the four grep rules from Task 01-06)**

```bash
cd /c/Users/heath/Repos/Personal/slpa-task-01-08/frontend
npm run verify
```

Expected: all four grep rules pass (no `dark:` variants, no hex colors in components, no inline styles, every primitive has a sibling test). The new primitives (`Checkbox`, `FormError`, `PasswordStrengthIndicator`) all have sibling tests, so the coverage rule passes.

- [ ] **Step 4: Run the build**

```bash
cd /c/Users/heath/Repos/Personal/slpa-task-01-08/frontend
npm run build
```

Expected: clean compile. All routes generated. The dynamic `/auction/[id]` route should still work (Task 01-06's placeholder is unchanged).

- [ ] **Step 5: Spot-check the dev server smoke test**

```bash
cd /c/Users/heath/Repos/Personal/slpa-task-01-08/frontend
npm run dev
```

In a browser, visit:
- `http://localhost:3000/register` — register form renders
- `http://localhost:3000/login` — login form renders
- `http://localhost:3000/forgot-password` — forgot password form renders
- `http://localhost:3000/dashboard` — should redirect to `/login?next=/dashboard` because no session exists

Stop the server with Ctrl+C.

- [ ] **Step 6: Confirm clean working tree**

```bash
cd /c/Users/heath/Repos/Personal/slpa-task-01-08
git status
```

Expected: `nothing to commit, working tree clean`.

- [ ] **Step 7: No commit** — this task is verification only.

---

### Task 35: Open the pull request

**Files:**
- None (PR creation via `gh`)

Per FOOTGUNS §5.7, the final ship step is `gh pr create`, never a direct push to main.

- [ ] **Step 1: Push the branch**

```bash
cd /c/Users/heath/Repos/Personal/slpa-task-01-08
git push -u origin task/01-08-frontend-auth
```

- [ ] **Step 2: Open the PR**

```bash
cd /c/Users/heath/Repos/Personal/slpa-task-01-08
gh pr create \
  --base main \
  --head task/01-08-frontend-auth \
  --title "feat(auth): frontend authentication pages with MSW test infrastructure" \
  --body "$(cat <<'EOF'
## Summary

Ships the frontend auth vertical: three auth pages (`/register`, `/login`, `/forgot-password`), the `/dashboard` placeholder behind `<RequireAuth>`, the auth state layer (`lib/auth/`) wired to the merged Task 01-07 backend, the `Header` authenticated branch, and MSW test infrastructure that every subsequent frontend task inherits.

Cross-slice touches (expected scope, documented in spec §1):
- `lib/api.ts` gains `configureApiClient`, 401 interceptor, stampede protection
- `lib/auth.ts` (Task 01-06 stub) deleted; `lib/auth/` directory replaces it
- `Header.tsx` authenticated branch wired to `<UserMenuDropdown>`
- `vitest.setup.ts` gains MSW lifecycle hooks
- `providers.tsx` calls `configureApiClient` once on mount

## Automated gates

- [x] `npm run test` — ~114 tests passing (67 from Task 01-06 + ~47 new)
- [x] `npm run lint` — clean
- [x] `npm run verify` — all four grep rules pass
- [x] `npm run build` — clean compile, all routes generated
- [x] Manual dev-server smoke test passed (forms render, dashboard redirects to login when unauthenticated)

## Security-critical behavior verified

- [x] **`api.401-interceptor.test.tsx`** — the canary. Three tests prove (a) 401 triggers refresh and retry, (b) concurrent 401s share one refresh call (stampede protection), (c) refresh failure clears session and redirects to /login. **FOOTGUNS §F.9** — never delete this test.
- [x] `RegisterForm` test verifies inline `AUTH_EMAIL_EXISTS` field error
- [x] `LoginForm` test verifies form-level `AUTH_INVALID_CREDENTIALS` error (NOT field-level — would leak which of email/password was wrong)
- [x] `ForgotPasswordForm` test verifies the `[STUB]` indicator banner renders in the success state
- [x] `mapProblemDetailToForm` tests cover the four code paths (validation, email-exists, invalid-credentials, unknown-error fallback) plus the unknown-field guard
- [x] `getSafeRedirect` open-redirect guard tests cover all 7 attack/edge cases
- [x] Token storage verified: access token lives in module-level `let`, refresh token never touched by JavaScript (HttpOnly cookie)

## Stale-brief reconciliation

The original Task 01-08 brief said "store tokens in localStorage or httpOnly cookie" and "remember me controls localStorage vs sessionStorage" — both stale per the merged Task 01-07 backend (HttpOnly-only, in-memory access token). The brainstorm Q1 round resolved this and locked the correct architecture. The "Remember me" checkbox is dropped; helper text "Signed in for 7 days on this device" replaces it. Spec §2 documents the full reconciliation. FOOTGUNS §5.9 captures the meta-lesson.

## References

- **Spec**: [`docs/superpowers/specs/2026-04-12-task-01-08-frontend-auth-design.md`](docs/superpowers/specs/2026-04-12-task-01-08-frontend-auth-design.md)
- **Plan**: [`docs/superpowers/plans/2026-04-12-task-01-08-frontend-auth.md`](docs/superpowers/plans/2026-04-12-task-01-08-frontend-auth.md)
- **Brief**: [`docs/implementation/epic-01/task-08-frontend-auth.md`](docs/implementation/epic-01/task-08-frontend-auth.md) (with §2 caveats)
- **FOOTGUNS additions**: §5.9 (stale-brief meta-lesson) + §F.1–§F.13 (frontend section)

## Manual smoke test

Run on your dev box before merging:

\`\`\`
[ ] cd frontend && npm run dev
[ ] Open http://localhost:3000/register — form renders, can type into all fields
[ ] Submit register form with backend running — successful registration redirects to /dashboard
[ ] Visit /dashboard while logged out — redirects to /login?next=/dashboard
[ ] Sign in via /login — redirects to /dashboard, header shows user menu dropdown
[ ] Click user menu dropdown → Sign Out — redirects to /, header shows Sign In + Create Account buttons
[ ] Visit /forgot-password — form renders, submit shows [STUB] banner + "Check your email" message
[ ] Enter invalid credentials on /login — form-level error "Email or password is incorrect."
[ ] Try to register an existing email — inline error on email field "An account with this email already exists."
\`\`\`

## Merge

Merge with **"Merge commit"** (not squash, not rebase) to preserve the per-task atomic commits. Per FOOTGUNS §5.7, the final ship step is this PR, not a direct push.
EOF
)"
```

- [ ] **Step 3: Return the PR URL**

Paste the URL from `gh pr create` output into the chat so the user can review on GitHub, walk through the smoke test, and merge.

---

## Self-review

Applied inline before saving the plan:

**1. Spec coverage:** every section of the spec maps to one or more tasks:
- Spec §1 (architecture) → Tasks 5–17 build the layer
- Spec §2 (brief corrections) → captured in plan header + Task 33 (README sweep)
- Spec §3 (auth module structure) → Tasks 5–17 create every file in the directory
- Spec §4 (token storage + session state) → Tasks 5, 10, 11, 14
- Spec §5 (form architecture) → Tasks 8 (schemas), 9 (errors), 18 (FormError), 22–24 (forms)
- Spec §6 (AuthCard layout) → Task 21
- Spec §7 (form content — strength, checkbox, displayName) → Tasks 6, 19, 20, 22
- Spec §8 (protected routes + redirects) → Tasks 7, 25, 29
- Spec §9 (Header authenticated state) → Tasks 30, 31
- Spec §10 (testing strategy) → Tasks 2, 3, 4 (MSW setup), Task 12 (canary)
- Spec §11 (dependencies) → Task 1
- Spec §12 (file inventory) → covered by every Phase A–I task
- Spec §13 (FOOTGUNS additions) → Task 32
- Spec §14 (out of scope) → respected (no profile-edit, no email verification, no password reset, no real dashboard content)

**2. Placeholder scan:** No `TBD`, `TODO`, or incomplete sections in the plan body. The two deliberate "implementer adjusts" notes are flagged with verification steps:
- Task 21 `AuthCard.Footer` margins must match `Card.tsx` padding — Step 1 reads `Card.tsx` and adjusts the values
- Task 30 `UserMenuDropdown` JSX may need adjustment to match the actual `Dropdown` and `Avatar` primitive APIs — comment in Step 3 documents this

**3. Type consistency:**
- `AuthUser`/`AuthSession` types defined in Task 5 (`lib/auth/session.ts`), consumed consistently in Tasks 14, 17, 25, 29, 30
- `AuthResponse`/`RegisterRequest`/`LoginRequest` types defined in Task 13 (`lib/auth/api.ts`), consumed in Tasks 14–16
- Form value types (`RegisterFormValues`, `LoginFormValues`, `ForgotPasswordFormValues`) defined in Task 8 (`lib/auth/schemas.ts`), consumed in Tasks 22–24
- `mapProblemDetailToForm` signature consistent across Tasks 9, 22, 23, 24
- `KNOWN_FIELDS` constant used consistently across the three forms (different per form, all `as const`)
- `getSafeRedirect` signature consistent in Tasks 7, 22, 23
- `setAccessToken`/`getAccessToken` signature consistent across all consumers

**4. Phase ordering:**
- MSW infrastructure (Phase A) ships before any test that uses it
- `lib/auth/session.ts` ships in Task 5 (resolves the temporary TS errors from Tasks 2–4)
- `lib/auth/schemas.ts`, `errors.ts`, `passwordStrength.ts`, `redirects.ts` ship before any form that imports them
- `FormError`, `Checkbox`, `PasswordStrengthIndicator` ship before the form components that import them
- `AuthCard` ships before the page rewrites that compose it
- `RequireAuth` ships before `/dashboard` page rewrite
- `UserMenuDropdown` ships before the Header wire

---

## Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-12-task-01-08-frontend-auth.md` on branch `task/01-08-frontend-auth` in worktree `C:\Users\heath\Repos\Personal\slpa-task-01-08`.

**Two execution options:**

1. **Subagent-Driven (recommended, B mode)** — dispatch a fresh subagent per task with the established B-mode prompt pattern (FOOTGUNS pre-flight, byte-exact rule, spec references, single-commit convention). Spec compliance + code quality review pair after substantive tasks. Fast iteration. Matches the Task 01-06 and Task 01-07 patterns.

2. **Inline Execution** — execute tasks in this session via `superpowers:executing-plans`. Batch execution with checkpoints for review. Slower than subagent-driven but all context stays in one place.

**Which approach?**

