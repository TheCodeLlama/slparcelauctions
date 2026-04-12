# Task 01-08 — Frontend Authentication Pages Design

**Date:** 2026-04-12
**Branch:** `task/01-08-frontend-auth`
**Brief:** [`docs/implementation/epic-01/task-08-frontend-auth.md`](../../implementation/epic-01/task-08-frontend-auth.md) (note: stale in places — see spec §2)
**Conventions:** [`docs/implementation/CONVENTIONS.md`](../../implementation/CONVENTIONS.md)
**Depends on:** [Task 01-06 frontend foundation](2026-04-10-task-01-06-frontend-foundation-design.md), [Task 01-07 JWT auth backend](2026-04-11-task-01-07-jwt-auth-backend-design.md)

## Goal

Build the three auth pages (`/register`, `/login`, `/forgot-password`) as composable React components backed by the merged Task 01-07 backend. Wire the frontend's `useAuth()` hook (a Task 01-06 stub) to the real JWT flow: in-memory access token, HttpOnly refresh cookie managed by the browser, TanStack Query as the session cache, automatic 401-refresh-retry in the API client. Replace the three RSC placeholder pages with real forms that share an `AuthCard` layout component, validate via `react-hook-form` + `zod`, map backend ProblemDetail errors to inline field errors, and redirect to `/dashboard` (or the `?next=` URL) on success. Update `Header` to render logged-in vs logged-out states. Add MSW to the frontend test infrastructure so every subsequent frontend task inherits realistic HTTP mocking.

## Architecture in three sentences

Auth state lives in TanStack Query as a single `["auth", "session"]` query keyed off the result of `POST /api/auth/refresh`. The access token is a module-level mutable ref in `lib/auth/session.ts` (not a React ref — the API client interceptor runs outside React), written only inside `queryFn` and mutation `onSuccess` callbacks, and read by the API client when composing `Authorization: Bearer` headers. Forms are `react-hook-form` + `zod` compositions inside a compound `AuthCard` layout that all three pages share; submission flows through `useLogin` / `useRegister` / `useForgotPassword` mutations that update the session cache on success and map `ProblemDetail` responses to inline field errors via a shared `mapProblemDetailToForm` helper.

## Tech stack

- **Next.js 16.2** (existing from Task 01-06, App Router, React 19)
- **TypeScript 5** (existing)
- **Tailwind CSS 4** with M3 tokens (existing)
- **TanStack Query v5** (existing from Task 01-06 via `QueryClientProvider`)
- **react-hook-form 7.x** (new dependency)
- **@hookform/resolvers 3.x** (new dependency — zod resolver)
- **zod 3.x** (new dependency)
- **MSW 2.x** (new dev dependency — HTTP mocking for tests)
- **Vitest 4 + RTL 16 + jsdom 29** (existing from Task 01-06)

---

## §1. Architecture overview

Build the frontend auth vertical on top of the existing Task 01-06 primitives and Task 01-07 backend contract. The slice touches three concerns:

1. **Auth state layer** (`lib/auth/`) — session query, mutations, access token ref, API client wiring.
2. **Presentation layer** (`components/ui/` + `components/auth/` + `components/layout/`) — new primitives (`Checkbox`, `FormError`, `PasswordStrengthIndicator`), new auth-specific components (`AuthCard`, `RequireAuth`, three form components), `Header` auth-state update.
3. **Pages** (`app/register/`, `app/login/`, `app/forgot-password/`, `app/dashboard/`) — replace the four RSC placeholders with real implementations.

The task also adds MSW test infrastructure under `frontend/src/test/msw/` that the next 15+ frontend tasks inherit.

**Cross-slice touches documented as expected scope** (not drift):

- `frontend/src/lib/auth.ts` (Task 01-06 stub) is replaced. The `useAuth()` signature + `AuthSession` / `AuthUser` types are preserved; only the implementation changes.
- `frontend/src/lib/api.ts` (Task 01-06) gains a `configureApiClient(queryClient)` setup function and a 401-interceptor path. The existing `credentials: "include"` behavior is preserved.
- `frontend/src/components/layout/Header.tsx` (Task 01-06) updates to render authenticated vs unauthenticated branches. The existing glassmorphism + scroll-aware shadow + sticky positioning are preserved.
- `frontend/vitest.setup.ts` gains MSW lifecycle hooks (`beforeAll` / `afterEach` / `afterAll`).
- `frontend/package.json` gains four new dependencies.

**Out of scope** (§15):

- Password reset email flow — backend password-reset endpoint doesn't exist yet. Forgot password page is UI-only with a fake success state.
- Email verification flow — separate future task.
- Profile editing (PUT/DELETE `/me`) — the 501 stubs stay 501.
- Protected dashboard content — this task adds the `RequireAuth` wrapper and a minimal "You're signed in as X" placeholder; real dashboard content is future work.
- Avatar image upload, real user photos — the `Header` dropdown shows initials or a fallback icon.

---

## §2. Brief corrections (stale guidance reconciled with merged backend)

The original Task 01-08 brief was written before Task 01-07 locked the HttpOnly-cookie auth architecture. Several brief statements are stale and must not be followed. Captured here so the implementer doesn't re-litigate them.

| Brief says | Reality (per merged Task 01-07) |
|---|---|
| "Store access token and refresh token (localStorage or httpOnly cookie)" | Access token in **memory only** (module ref in `lib/auth/session.ts`). Refresh token is an HttpOnly cookie the frontend **never reads or writes**; the browser manages it automatically via `credentials: "include"`. |
| "Remember me checkbox controls localStorage vs sessionStorage" | **Drop the checkbox.** Backend enforces 7-day sliding refresh cookies unconditionally — every successful refresh extends the session. Replace with helper text: `"Signed in for 7 days on this device"`. |
| "Store JWT on success" | JWT (access token) is held in memory by `lib/auth/session.ts`. The refresh token is the HttpOnly cookie set by the backend's `Set-Cookie` header and is never touched by JavaScript. |
| "Redirect to login on 401 responses" | Correct, but via the API client's 401 interceptor after attempting `/refresh` first. Only a failed refresh triggers the redirect. |
| "Sign up page: email, password, confirm password fields" | Correct. **No `displayName` field** in the form even though the backend accepts optional `displayName`. Stitch design + brief agree. Register request sends `displayName: null`. |
| "Password strength indicator" | Correct. Hand-rolled algorithm (not zxcvbn). A password meeting the backend regex shows **Good (3 bars)**, never Fair. Strong (4 bars) is for going above. |

The Stitch HTML mockups also show a `Remember me` toggle (sign-in) and a border under the footer link (all three pages) that conflict with `DESIGN.md` §2 "No-Line Rule" and the backend's unconditional session model. **DESIGN.md wins over Stitch HTML.** The Stitch asset updates for removing the checkbox and replacing the border ship in the same commit as the page rewrite — not a follow-on ticket.

**FOOTGUNS §5.9 (meta-lesson, added in this task's finalization phase):** Stale briefs are a drift source. When a Task N spec/plan locks a decision that contradicts the original Task N+1 brief, patch the N+1 brief in the same PR. Otherwise N+1's implementer walks into a pre-resolved conflict and wastes a brainstorm round re-discovering the answer.

---

## §3. Auth module structure

### `frontend/src/lib/auth/` (replaces `lib/auth.ts` from Task 01-06)

```
frontend/src/lib/auth/
├── session.ts           // Module-level access token ref + getter/setter
├── api.ts               // Typed wrappers for the 5 auth endpoints
├── hooks.ts             // useAuth, useLogin, useRegister, useLogout, useLogoutAll, useForgotPassword
├── schemas.ts           // zod schemas: emailSchema, passwordCreateSchema, passwordInputSchema, registerSchema, loginSchema, forgotPasswordSchema
├── errors.ts            // mapProblemDetailToForm helper + ProblemDetail parsing
├── passwordStrength.ts  // Strength algorithm (hand-rolled)
├── redirects.ts         // getSafeRedirect helper for ?next= param
└── index.ts             // Public barrel — re-exports useAuth, AuthSession, AuthUser, hooks
```

The Task 01-06 `lib/auth.ts` file is deleted; `lib/auth/` (directory) replaces it. The `AuthUser` and `AuthSession` types move to `lib/auth/session.ts` or `lib/auth/types.ts` (implementer chooses — single module if < 60 lines). The public API of `useAuth()` and the `AuthSession` discriminated union is preserved so existing consumers (nothing yet, but Task 01-06 exported them from the stub) don't need changes.

### `frontend/src/lib/api.ts` (modified)

Task 01-06's module gains:

- `configureApiClient(queryClient: QueryClient)` — called once at app mount from `Providers`, stores the QueryClient reference at module scope for the 401 interceptor
- 401-interceptor path: on a 401 from any non-auth endpoint, call `/api/auth/refresh`, update the access token, retry the original request once. On refresh failure OR retry failure, clear the session query data and redirect to `/login`
- **Concurrent 401 stampede protection** via a single in-flight `Promise<void>` held at module scope — first 401 starts the refresh, subsequent 401s await the same promise, all retry after it resolves
- `Authorization: Bearer <token>` header composition from `getAccessToken()` on every non-auth request

The existing `credentials: "include"` behavior is preserved. `ApiError` class (from Task 01-06) is reused for the 401 interceptor's retry logic.

### `frontend/src/components/ui/` (new primitives)

```
frontend/src/components/ui/
├── Checkbox.tsx              // NEW — styled native checkbox with label, error, forwardRef
├── FormError.tsx             // NEW — form-level error display (single prop: message)
├── PasswordStrengthIndicator.tsx  // NEW — 4-segment bar + label, computed live from password
├── ... (existing 8 primitives from Task 01-06 unchanged)
```

### `frontend/src/components/auth/` (new directory)

```
frontend/src/components/auth/
├── AuthCard.tsx              // Compound layout: .Title, .Subtitle, .Body, .Footer — brand header hardcoded
├── RequireAuth.tsx           // Client-side guard wrapper for protected pages
├── RegisterForm.tsx          // Form component (email, password, confirmPassword, terms)
├── LoginForm.tsx             // Form component (email, password)
├── ForgotPasswordForm.tsx    // Form component (email only) + success state
└── UserMenuDropdown.tsx      // Header's authenticated branch — avatar + dropdown with logout
```

### `frontend/src/app/` (rewrites)

```
frontend/src/app/
├── register/page.tsx         // Replaces Task 01-06 RSC placeholder
├── login/page.tsx            // Replaces placeholder
├── forgot-password/page.tsx  // Replaces placeholder
├── dashboard/page.tsx        // Replaces placeholder — wrapped in <RequireAuth>, minimal placeholder content
```

Pages are client components (`"use client"`) because they compose `<AuthCard>` + form components that use hooks. The server-component shell from Task 01-06 (RSC placeholder rendering `<PageHeader />`) is replaced entirely.

### `frontend/src/test/msw/` (new)

```
frontend/src/test/msw/
├── server.ts                 // setupServer() instance
├── handlers.ts               // Default + named handlers for every auth endpoint
└── fixtures.ts               // Test fixtures: mockUser, mockAuthResponse, etc.
```

---

## §4. Token storage and session state (Q1 + Q2 decisions)

### Access token lives in a module-level ref

`frontend/src/lib/auth/session.ts`:

```ts
let accessToken: string | null = null;

export function getAccessToken(): string | null {
  return accessToken;
}

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

export type AuthUser = {
  id: number;
  email: string;
  displayName: string | null;
  slAvatarUuid: string | null;
  verified: boolean;
};

export type AuthSession =
  | { status: "loading"; user: null }
  | { status: "authenticated"; user: AuthUser }
  | { status: "unauthenticated"; user: null };
```

**Export discipline:** only `getAccessToken` and `setAccessToken` are exported. The `accessToken` variable itself is module-private. A grep for `setAccessToken(` catches every mutation path; a grep for `accessToken = ` outside `session.ts` is the failure mode to detect in code review (and future lint rules).

**Why a module ref, not a React ref:** the API client interceptor runs outside React's lifecycle. React refs (`useRef`) are per-component and die on unmount. A module-level `let` is the right container for state that the interceptor reads synchronously and the mutations write synchronously.

### Session state is a TanStack Query

`frontend/src/lib/auth/hooks.ts`:

```ts
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { setAccessToken } from "./session";
import type { AuthUser, AuthSession } from "./session";

const SESSION_QUERY_KEY = ["auth", "session"] as const;

async function bootstrapSession(): Promise<AuthUser> {
  // Calls POST /api/auth/refresh. On success, sets the access token module ref
  // BEFORE returning the user. This side effect MUST live inside queryFn —
  // first subscribe uses queryFn's return value directly without firing onSuccess
  // (TanStack Query nuance, see FOOTGUNS).
  const response = await api.post<AuthResponse>("/api/auth/refresh");
  setAccessToken(response.accessToken);
  return response.user;
}

export function useAuth(): AuthSession {
  const query = useQuery({
    queryKey: SESSION_QUERY_KEY,
    queryFn: bootstrapSession,
    staleTime: Infinity,
    gcTime: Infinity,
    retry: false,
    // refetchOnWindowFocus: false — the 15-min access token + 7-day refresh
    // cookie already handle session liveness. We don't want to refetch /refresh
    // every time the user tabs back.
    refetchOnWindowFocus: false,
  });

  if (query.isPending) return { status: "loading", user: null };
  if (query.isError) return { status: "unauthenticated", user: null };
  return { status: "authenticated", user: query.data };
}
```

**The `staleTime: Infinity` + `gcTime: Infinity` + `retry: false` trio is deliberate:**

- `staleTime: Infinity` — the session doesn't auto-refresh. The only refresh paths are the 401 interceptor + the bootstrap on mount. Without `Infinity`, TanStack Query would consider the session stale after 5 minutes and re-fetch on every `useAuth()` call.
- `gcTime: Infinity` — the session query data is never garbage-collected. If `Header` unmounts temporarily (unlikely but possible), the cached session data persists.
- `retry: false` — a 401 on bootstrap means "no valid session cookie" and is a legitimate unauthenticated state, not a transient error. Retrying would send three `/refresh` calls on a fresh visit.

The three flags together need a FOOTGUNS entry — they're non-default and a contributor "tuning" them based on standard React Query advice would break the auth layer.

### Mutations

`frontend/src/lib/auth/hooks.ts` (continued):

```ts
export function useLogin() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: LoginRequest) => api.post<AuthResponse>("/api/auth/login", body),
    onSuccess: (response) => {
      setAccessToken(response.accessToken);
      // setQueryData (NOT invalidateQueries) — invalidate would trigger a
      // wasted /refresh round-trip immediately after login.
      queryClient.setQueryData(SESSION_QUERY_KEY, response.user);
    },
  });
}

export function useRegister() {
  // Same shape as useLogin, different endpoint.
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: RegisterRequest) => api.post<AuthResponse>("/api/auth/register", body),
    onSuccess: (response) => {
      setAccessToken(response.accessToken);
      queryClient.setQueryData(SESSION_QUERY_KEY, response.user);
    },
  });
}

export function useLogout() {
  const queryClient = useQueryClient();
  const router = useRouter();
  return useMutation({
    mutationFn: () => api.post("/api/auth/logout"),
    // onSettled (NOT onSuccess) — clear local state even if the network call fails.
    // Logout is idempotent and should always complete client-side.
    onSettled: () => {
      setAccessToken(null);
      queryClient.setQueryData(SESSION_QUERY_KEY, null);
      queryClient.removeQueries({ queryKey: SESSION_QUERY_KEY });
      router.push("/");
    },
  });
}

export function useLogoutAll() {
  // Dedicated sub-task per Task 01-07 §15 handoff note.
  // logoutAll requires a valid access token. Refresh first (cheap, ~100ms),
  // THEN call logoutAll. Handles the "user's access token just expired" edge case.
  const queryClient = useQueryClient();
  const router = useRouter();
  return useMutation({
    mutationFn: async () => {
      // Always refresh before logout-all to guarantee a valid access token.
      const refreshed = await api.post<AuthResponse>("/api/auth/refresh");
      setAccessToken(refreshed.accessToken);
      return api.post("/api/auth/logout-all");
    },
    onSettled: () => {
      setAccessToken(null);
      queryClient.setQueryData(SESSION_QUERY_KEY, null);
      queryClient.removeQueries({ queryKey: SESSION_QUERY_KEY });
      router.push("/");
    },
  });
}

export function useForgotPassword() {
  // STUB: no backend password-reset endpoint exists yet. This mutationFn fakes
  // a ~300ms delay and always resolves successfully so the UI can display its
  // success state. NO EMAIL IS ACTUALLY SENT.
  //
  // When the real endpoint ships:
  //   1. Replace this body with `api.post("/api/auth/forgot-password", { email })`.
  //   2. Remove the [STUB] indicator from ForgotPasswordForm's success state.
  //   3. Remove the inline comment in ForgotPasswordPage.
  //   4. Update the brief at docs/implementation/epic-01/task-08-frontend-auth.md
  //      to remove the "UI only" caveat.
  //
  // This four-step swap is the cost of shipping a UI without its backend.
  return useMutation({
    mutationFn: async (email: string) => {
      await new Promise((resolve) => setTimeout(resolve, 300));
      return { success: true };
    },
  });
}
```

### API client wiring

`frontend/src/lib/api.ts` gains a module-level state block:

```ts
let queryClientRef: QueryClient | null = null;
let inFlightRefresh: Promise<void> | null = null;

export function configureApiClient(queryClient: QueryClient): void {
  queryClientRef = queryClient;
}
```

And the fetch wrapper grows a 401 branch:

```ts
async function handleUnauthorized(originalRequest: RequestInit & { url: string }): Promise<Response> {
  // Concurrent-stampede protection: multiple 401s share one refresh attempt.
  //
  // The cleanup (clearing inFlightRefresh) lives INSIDE the IIFE's try/finally
  // so only the CREATOR clears the ref, not every awaiter racing to null it
  // out. JS microtask ordering makes "every awaiter clears in its own finally"
  // safe in practice, but having one code path own the cleanup is structurally
  // correct and easier to reason about.
  if (!inFlightRefresh) {
    inFlightRefresh = (async () => {
      try {
        const refreshed = await fetch(`${BASE_URL}/api/auth/refresh`, {
          method: "POST",
          credentials: "include",
        });
        if (!refreshed.ok) {
          // Refresh failed — treat as unauthenticated.
          setAccessToken(null);
          if (queryClientRef) {
            queryClientRef.setQueryData(["auth", "session"], null);
            queryClientRef.removeQueries({ queryKey: ["auth", "session"] });
          }
          if (typeof window !== "undefined") {
            const next = encodeURIComponent(window.location.pathname + window.location.search);
            window.location.href = `/login?next=${next}`;
          }
          throw new ApiError("Session expired", 401);
        }
        const body = await refreshed.json() as AuthResponse;
        setAccessToken(body.accessToken);
        if (queryClientRef) {
          queryClientRef.setQueryData(["auth", "session"], body.user);
        }
      } finally {
        inFlightRefresh = null;
      }
    })();
  }

  await inFlightRefresh;

  // Retry the original request once with the new access token.
  return fetch(originalRequest.url, {
    ...originalRequest,
    headers: {
      ...originalRequest.headers,
      Authorization: `Bearer ${getAccessToken()}`,
    },
  });
}
```

The 401 path:

1. First 401 sets `inFlightRefresh` to the refresh promise.
2. Subsequent 401s await the same promise (no duplicate refresh calls).
3. On success, retry the original request once with the new access token.
4. On failure, clear session state and redirect.

**Configuration call site:** `frontend/src/app/providers.tsx` (the client component that wraps children in `QueryClientProvider`) calls `configureApiClient(queryClient)` once inside the `useEffect` or at module scope (client-side only). The call must happen before any component calls `useAuth()` for the first time, so ideally during the provider's render — `useState(() => { configureApiClient(client); return client; })` is a concise idiom that runs once on mount.

---

## §5. Form architecture (Q3 decision)

### Dependencies added

`frontend/package.json` gains three packages in a single install step:

```json
{
  "dependencies": {
    "react-hook-form": "^7.53.0",
    "@hookform/resolvers": "^3.9.0",
    "zod": "^3.23.8"
  }
}
```

All three are dependencies (not devDependencies) because they ship in the production bundle. Versions are illustrative — the implementer picks the latest stable at install time and captures the exact versions in the lockfile.

### Shared zod schemas

`frontend/src/lib/auth/schemas.ts`:

```ts
import { z } from "zod";

// Email — reused by register, login, forgot-password.
export const emailSchema = z
  .string()
  .min(1, "Email is required")
  .email("Enter a valid email")
  .max(255);

// Password for CREATION (register). Mirrors the backend regex exactly:
//   ^(?=.*[A-Za-z])(?=.*[\d\W]).{10,}$
// 10+ chars, at least one letter, at least one digit OR symbol.
export const passwordCreateSchema = z
  .string()
  .min(10, "At least 10 characters")
  .regex(
    /^(?=.*[A-Za-z])(?=.*[\d\W]).{10,}$/,
    "Must contain a letter and a digit or symbol"
  )
  .max(255);

// Password for INPUT (login). Just non-empty. The backend checks credentials.
// KEEP THESE SCHEMAS DISTINCT. A contributor who "unifies" them breaks login
// for any pre-existing password that predates the regex tightening.
export const passwordInputSchema = z
  .string()
  .min(1, "Password is required")
  .max(255);

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

export const loginSchema = z.object({
  email: emailSchema,
  password: passwordInputSchema,
});

export const forgotPasswordSchema = z.object({
  email: emailSchema,
});

export type RegisterFormValues = z.infer<typeof registerSchema>;
export type LoginFormValues = z.infer<typeof loginSchema>;
export type ForgotPasswordFormValues = z.infer<typeof forgotPasswordSchema>;
```

### Form configuration

Every form uses the same `useForm` config:

```ts
const form = useForm<RegisterFormValues>({
  resolver: zodResolver(registerSchema),
  mode: "onBlur",              // Errors show after first blur, not on every keystroke.
  reValidateMode: "onChange",  // After first submit, re-validate live as user fixes errors.
  defaultValues: {
    email: "",
    password: "",
    confirmPassword: "",
    terms: false,
  },
});
```

**Both `mode` and `reValidateMode` are explicit** (not left to defaults) so a future contributor reading the code doesn't have to memorize RHF's default behavior.

### ProblemDetail error mapping

`frontend/src/lib/auth/errors.ts`:

```ts
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
  errors?: ProblemDetailValidationError[]; // Array of {field, message} from Task 01-07 §9.
};

/**
 * Maps a backend ProblemDetail error into react-hook-form field errors.
 *
 * Handles three paths:
 *   - VALIDATION_FAILED (400 with errors[] array) → per-field form.setError
 *   - AUTH_EMAIL_EXISTS (409) → field-level on `email`
 *   - Everything else → form-level root.serverError
 *
 * Unknown-field guard: if a VALIDATION_FAILED entry references a field that doesn't
 * exist on the form, fall back to root.serverError with the concatenated message.
 * Log to console in dev so the next contributor knows a new field needs wiring.
 */
export function mapProblemDetailToForm<T extends FieldValues>(
  error: unknown,
  form: UseFormReturn<T>,
  knownFields: readonly Path<T>[]
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
      if (knownFields.includes(entry.field as Path<T>)) {
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

**Four design rules in this helper:**

1. **`type: "server"` on every `setError` call** — future code can distinguish server-originated errors from client-side zod errors (e.g., clear only stale client errors on field change).
2. **`knownFields` parameter** — each caller passes its form's field names so the helper can detect unknown-field drift. Per form: `["email", "password", "confirmPassword", "terms"]` for register, `["email", "password"]` for login, `["email"]` for forgot-password.
3. **Unknown-field fallback + console warn** — never silently drop errors. Drift between backend validators and frontend form fields is visible immediately.
4. **`AUTH_INVALID_CREDENTIALS` → form-level, not field-level** — Login errors deliberately don't reveal which of email/password was wrong. The form-level message "Email or password is incorrect" is the industry standard.

### Form error display

`frontend/src/components/ui/FormError.tsx`:

```tsx
type FormErrorProps = {
  message?: string;
};

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

Two lines of CSS, one prop, one conditional. **Lives in `components/ui/`, not `components/auth/`** — it's a generic form primitive. Every future form in the project reuses it.

### Submit button state

Every form's submit button is disabled when:

```tsx
<Button
  type="submit"
  disabled={form.formState.isSubmitting || mutation.isPending}
  loading={mutation.isPending}
  fullWidth
>
  Create Account
</Button>
```

**Both conditions, not one.** `isSubmitting` covers the validation phase (zod resolver running); `isPending` covers the network phase. A single check would flicker the button to enabled between validation and network.

---

## §6. `AuthCard` layout component (Q4 decision)

### Compound pattern

`frontend/src/components/auth/AuthCard.tsx`:

```tsx
import { Card } from "@/components/ui/Card";
import type { ReactNode } from "react";

type AuthCardProps = { children: ReactNode };

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
  // "No-Line Rule" (DESIGN.md §2): no border-t. Use a background shift + pt-6
  // to separate the footer link from the form body.
  //
  // CRITICAL: The negative margins MUST match the Card primitive's padding
  // exactly. If the underlying Card uses `p-10`, the negative margins must be
  // `-mx-10 -mb-10 px-10`. If `p-8`, then `-mx-8 -mb-8 px-8`. The values
  // shown below are placeholder — the implementer MUST read
  // `frontend/src/components/ui/Card.tsx` and match its padding before
  // committing. One wrong value and the footer either floats inward or
  // overflows the card horizontally.
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

**Design decisions encoded:**

1. **Brand header is hardcoded** — SLPA wordmark + "The Digital Curator" tagline are always rendered at the top. Not a slot. If a future task needs to hide it (we don't), it adds an `showBrand={false}` prop then.
2. **`AuthCard` wraps the existing `Card` primitive** from Task 01-06 — inherits `bg-surface-container-lowest`, `shadow-soft`, `rounded-default`, `p-8`. No new card styling.
3. **`max-w-md` (448px) centering container** — all three pages share this width per the Stitch designs.
4. **`AuthCard.Title` and `AuthCard.Subtitle` are thin typographic wrappers** — the page passes text, the card applies the type scale.
5. **`AuthCard.Body` provides the `space-y-6` rhythm** — forms inside don't declare the vertical gap; it comes from the wrapper.
6. **`AuthCard.Footer` uses a background shift** (`bg-surface-container-low` + negative margin to hit card edges) instead of a border. **Implementer verifies against DESIGN.md §2 "No-Line Rule" and §4 "Depth" rules before committing** — the Stitch HTML shows a `border-t` that DESIGN.md forbids. The background-shift approach matches what Task 01-06's `Header` does.
7. **Forgot-password success state swap** is the page's responsibility, not `AuthCard`'s. The page puts `{isSuccess ? <SuccessMessage /> : <ForgotPasswordForm />}` inside `<AuthCard.Body>` — one conditional, no new props.

8. **Forgot-password STUB indicator (UI-visible).** Because no backend password-reset endpoint exists yet (see §7.5), the success state ("Check your email for a reset link") is a lie — no email is actually sent. Without a visible indicator, a QA tester or demo audience will file "I never got the email" as a bug. The success-state component MUST render a small dev/demo banner above the success message:

```tsx
<div className="mb-4 rounded-md bg-tertiary-container px-3 py-2 text-label-sm text-on-tertiary-container">
  <strong>[STUB]</strong> Backend password-reset endpoint not yet implemented.
  No email will arrive. This success state is UI-only for the current task.
</div>
```

The banner uses `bg-tertiary-container` (the M3 "warning/notice" color) so it's visually distinct from real success messaging without looking like an error. Inline code comment in `ForgotPasswordForm.tsx` and `app/forgot-password/page.tsx` repeats the warning so a contributor reading the source sees it before the user. When the real endpoint ships, deletion of this banner is one of the four steps documented in `useForgotPassword`'s JavaDoc-equivalent comment (§4.3).

### Page usage example

`frontend/src/app/register/page.tsx`:

```tsx
"use client";

import { AuthCard } from "@/components/auth/AuthCard";
import { RegisterForm } from "@/components/auth/RegisterForm";
import Link from "next/link";

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

Login and forgot-password pages follow the same shape. The form component is the only per-page difference.

---

## §7. Form content — password strength, Checkbox primitive, displayName (Q5 decisions)

### 7.1 Password strength algorithm

`frontend/src/lib/auth/passwordStrength.ts`:

```ts
export type PasswordStrength = "empty" | "weak" | "fair" | "good" | "strong";

const BACKEND_REGEX = /^(?=.*[A-Za-z])(?=.*[\d\W]).{10,}$/;

/**
 * Hand-rolled strength estimator aligned with the backend regex.
 *
 * Level mapping:
 *   - empty: input length 0
 *   - weak: doesn't meet backend regex (too short OR missing required class)
 *   - fair: doesn't meet backend regex but is close (length ≥8 with partial class coverage)
 *   - good: meets backend regex exactly (≥10 chars + letter + digit/symbol)
 *   - strong: meets backend regex AND goes beyond (≥14 chars OR 3+ character classes)
 *
 * A password that satisfies the backend regex MUST NEVER show less than "good".
 * A user who meets the real requirement and sees only 2 bars will feel punished
 * for complying. "Strong" is the bonus for going above and beyond.
 */
export function computePasswordStrength(password: string): PasswordStrength {
  if (password.length === 0) return "empty";

  const meetsBackendRegex = BACKEND_REGEX.test(password);

  if (meetsBackendRegex) {
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

**Advisory only, not blocking.** The form's submit button is gated by the zod resolver (which enforces the backend regex). Strength below "Good" is visible feedback to the user, not a submit blocker. A password that passes zod can be submitted regardless of strength — the strength indicator exists to give positive feedback for going above the minimum.

### 7.2 `PasswordStrengthIndicator` component

`frontend/src/components/ui/PasswordStrengthIndicator.tsx`:

```tsx
import {
  computePasswordStrength,
  strengthToBars,
  strengthToLabel,
} from "@/lib/auth/passwordStrength";

type PasswordStrengthIndicatorProps = {
  password: string;
};

export function PasswordStrengthIndicator({ password }: PasswordStrengthIndicatorProps) {
  const strength = computePasswordStrength(password);
  const bars = strengthToBars(strength);
  const label = strengthToLabel(strength);

  if (strength === "empty") return null;

  return (
    <div className="mt-2">
      <div className="flex gap-1" role="progressbar" aria-valuenow={bars} aria-valuemin={0} aria-valuemax={4}>
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

**Visual matches the Stitch design:** 4-segment bar, 1px height, `gap-1`, `rounded-full`, with filled segments in `bg-primary` and empty in `bg-primary/20`. Label below in `text-label-sm`.

### 7.3 `Checkbox` primitive

`frontend/src/components/ui/Checkbox.tsx`:

```tsx
import { forwardRef, type InputHTMLAttributes, type ReactNode } from "react";
import { Check } from "@/components/ui/icons";
import { cn } from "@/lib/cn";

type CheckboxProps = Omit<InputHTMLAttributes<HTMLInputElement>, "type"> & {
  label: ReactNode;
  error?: string;
};

export const Checkbox = forwardRef<HTMLInputElement, CheckboxProps>(
  ({ label, error, className, id, ...props }, ref) => {
    const checkboxId = id ?? `checkbox-${label?.toString().slice(0, 16)}`;
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

**API mirrors `Input`:**

- `label` is a `ReactNode` so the Terms checkbox can pass inline links: `label={<>I agree to the <Link href="/terms">Terms</Link></>}`.
- `error` surfaces zod + server errors.
- `forwardRef` so RHF's `register` works.
- Native `<input type="checkbox">` with `appearance-none` + custom styling via `peer` selectors — checked state uses `peer-checked:block` on the `Check` icon overlay.
- Label and input share an `htmlFor` / `id` binding for accessibility + clickability.

### 7.4 `displayName` handling

Register form **does not include a `displayName` field**. The Stitch design shows only email + password + confirm, and the brief agrees. The register request payload sends `displayName: null`:

```ts
// In RegisterForm onSubmit:
register.mutate({
  email: values.email,
  password: values.password,
  displayName: null,
});
```

The backend's `RegisterRequest` accepts `@Size(max=255)` with no `@NotBlank`, so `null` is valid. After registration, `response.user.displayName` is `null`. The `Header`'s authenticated branch displays a fallback when `displayName` is null — either the first character of the email in an avatar circle, or the literal string "Account" next to an avatar icon. Implementer picks whichever reads cleaner; this is a small UI detail not a design question.

A future profile-edit task lands the field on a dedicated settings page. Task 01-08 is out of scope for profile editing.

---

## §8. Protected routes and redirects (Q6 decision)

### `RequireAuth` wrapper

`frontend/src/components/auth/RequireAuth.tsx`:

```tsx
"use client";

import { useEffect, type ReactNode } from "react";
import { useRouter, usePathname } from "next/navigation";
import { useAuth } from "@/lib/auth";

type RequireAuthProps = {
  children: ReactNode;
};

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
    // Redirect is in-flight via the useEffect. Render nothing to avoid flashing
    // the protected content before the redirect resolves.
    return null;
  }

  return <>{children}</>;
}
```

**Three states, three renderings:**

- `loading` → centered spinner (matches the bootstrap-in-progress state).
- `unauthenticated` → `useEffect` fires the redirect, component renders `null` in the meantime.
- `authenticated` → render children.

### `getSafeRedirect` helper

`frontend/src/lib/auth/redirects.ts`:

```ts
const FALLBACK = "/dashboard";

/**
 * Parses a ?next= query parameter and returns a safe internal URL.
 *
 * Security rules:
 *   - Must start with / (relative)
 *   - Must NOT start with // (protocol-relative URL — open redirect attack vector)
 *   - Must NOT contain a newline or control character
 *   - Falls back to /dashboard on any failure
 *
 * This is the open-redirect guard. A malicious link like
 * /login?next=//evil.example/phish would otherwise bounce the user to evil.example
 * after login. The // check catches that.
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

Pure function, unit-tested. Called by:

1. `LoginForm`'s `onSuccess` — reads `searchParams.get("next")` and redirects.
2. `RegisterForm`'s `onSuccess` — same behavior (new users hitting `/dashboard → /login?next=/dashboard → /register?next=/dashboard` still land at `/dashboard`).

### Dashboard page

`frontend/src/app/dashboard/page.tsx`:

```tsx
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
  if (session.status !== "authenticated") return null; // should never render
  return (
    <>
      <PageHeader
        title="Dashboard"
        subtitle={`Signed in as ${session.user.email}`}
      />
      <div className="mx-auto max-w-4xl px-4 py-8">
        <p className="text-body-md text-on-surface-variant">
          Your bids, listings, and sales will appear here. Real dashboard content
          lands in a future task.
        </p>
      </div>
    </>
  );
}
```

Minimal placeholder — the real dashboard content (bids, listings, sales) is out of scope for this task.

---

## §9. Header authenticated-state display

`frontend/src/components/layout/Header.tsx` (modified from Task 01-06):

The existing `Header` has a glassmorphism + scroll-aware shadow + sticky-top layout with three auth branches already stubbed out:

- `session.status === "loading"` → render `null` for the auth cluster (the rest of the header renders normally)
- `session.status === "authenticated"` → **Task 01-08 wires this:** render `<UserMenuDropdown user={session.user} />`
- `session.status === "unauthenticated"` → render "Sign In" + "Create Account" buttons

### `UserMenuDropdown` component

`frontend/src/components/auth/UserMenuDropdown.tsx`:

```tsx
"use client";

import { useLogout } from "@/lib/auth";
import { Dropdown } from "@/components/ui/Dropdown";
import { Avatar } from "@/components/ui/Avatar";
import type { AuthUser } from "@/lib/auth";

type UserMenuDropdownProps = { user: AuthUser };

export function UserMenuDropdown({ user }: UserMenuDropdownProps) {
  const logout = useLogout();
  const displayLabel = user.displayName ?? user.email.split("@")[0];

  return (
    <Dropdown
      trigger={
        <button className="flex items-center gap-2 rounded-full p-1 hover:bg-surface-container-low">
          <Avatar name={displayLabel} />
          <span className="text-body-sm text-on-surface hidden sm:inline">{displayLabel}</span>
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

**Logout via `useLogout().mutate()`** — triggers the mutation which runs the `POST /api/auth/logout` call, then in `onSettled` clears the access token, clears the session query cache, removes the query, and redirects to `/`.

**`Sign Out` is present, `Sign out all sessions` is not.** The latter uses `useLogoutAll()` which lives as a dedicated hook for future use (account settings page, security dashboard). Adding it to the header dropdown now is scope creep.

**Avatar fallback:** if `displayName` is null, use the email local-part (`alice@example.com` → `alice`). If we want a visual avatar, `<Avatar name={displayLabel} />` generates initials from the label. The existing `Avatar` primitive from Task 01-06 already does this.

---

## §10. Testing strategy (Q7 decision)

### MSW 2.x setup

`frontend/src/test/msw/server.ts`:

```ts
import { setupServer } from "msw/node";

export const server = setupServer();
```

`frontend/src/test/msw/handlers.ts`:

```ts
import { http, HttpResponse } from "msw";
import { mockUser, mockAuthResponse } from "./fixtures";

export const authHandlers = {
  // Default: bootstrap fails with 401 (no existing session).
  refreshUnauthenticated: () =>
    http.post("*/api/auth/refresh", () =>
      HttpResponse.json(
        {
          type: "https://slpa.example/problems/auth/token-missing",
          status: 401,
          code: "AUTH_TOKEN_MISSING",
          detail: "Authentication is required to access this resource.",
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
    http.post("*/api/auth/logout", () => new HttpResponse(null, { status: 204 })),

  logoutAllSuccess: () =>
    http.post("*/api/auth/logout-all", () => new HttpResponse(null, { status: 204 })),
};

// Default handlers used at server startup: unauthenticated bootstrap.
export const defaultHandlers = [authHandlers.refreshUnauthenticated()];
```

`frontend/src/test/msw/fixtures.ts`:

```ts
import type { AuthUser } from "@/lib/auth";

export const mockUser: AuthUser = {
  id: 42,
  email: "test@example.com",
  displayName: null,
  slAvatarUuid: null,
  verified: false,
};

export function mockAuthResponse(user: AuthUser = mockUser) {
  return {
    accessToken: "mock-access-token-jwt",
    user,
  };
}
```

### `vitest.setup.ts` additions

```ts
// frontend/vitest.setup.ts (additions)
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

**`onUnhandledRequest: "error"` is load-bearing.** If a test makes a fetch to an endpoint without a matching handler, it fails loudly instead of silently passing the real network call. A future contributor who switches this to `"warn"` or `"bypass"` to make a flaky test pass has silently allowed real network requests from tests — the same failure mode as deleting the reuse-cascade canary in Task 01-07. This is a FOOTGUNS-worthy entry (§Frontend.X, added in Task 01-08's finalization phase).

### Test pyramid distribution

| Level | Count | Targets |
|---|---|---|
| **Unit** (~15) | 15 | `schemas` (3 cases), `mapProblemDetailToForm` (4 cases), `computePasswordStrength` (4 cases), `getSafeRedirect` (3 cases), `getAccessToken`/`setAccessToken` pair (1 case) |
| **Component** (~12) | 12 | `AuthCard` compound (2), `FormError` (2), `Checkbox` (3), `PasswordStrengthIndicator` (4), `RequireAuth` rendering each status (3) |
| **Integration** (~20) | 20 | `RegisterForm` (5: happy/validation/email-exists/network/password-mismatch), `LoginForm` (5: happy/invalid-credentials/validation/loading/reset), `ForgotPasswordForm` (3: happy/validation/success-state-swap), `Header` auth branches (2: logged-out / logged-in), bootstrap-on-mount (2: 401 → unauthenticated / 200 → authenticated), **401-auto-refresh-retry canary** (3) |

**Target after Task 01-08: ~114 frontend tests** (67 from Task 01-06 + 47 new).

### The canary integration test

`frontend/src/lib/api.401-interceptor.test.ts` (or similar — implementer picks the location) contains **the 401-auto-refresh-and-retry canary**. Non-negotiable, never delete.

Scenario:

1. Configure MSW with a handler that returns 401 on the first call to a protected endpoint (e.g., `GET /api/users/me`), then returns 200 on the retry.
2. Configure MSW with a handler that returns 200 + fresh access token on `POST /api/auth/refresh`.
3. Call the protected endpoint via the API client.
4. Assert the endpoint call fired twice (first 401, second 200), the refresh endpoint fired once, the access token was updated in the module ref, and the final result returned the 200 payload.

**Also test the stampede protection:**

1. Same setup as above but with three concurrent calls to the protected endpoint.
2. Assert that `POST /api/auth/refresh` was called exactly once, all three protected-endpoint retries fired, and all three got the refreshed access token.

**Also test the refresh-failure path:**

1. MSW returns 401 on the protected endpoint, 401 on the refresh.
2. Assert the session query data is cleared, the access token is null, and the browser location was updated to `/login?next=...`.

This canary is the frontend equivalent of Task 01-07's `refreshTokenReuseCascade` test. FOOTGUNS §Frontend.Y: **The 401-auto-refresh canary is the security model.** If a future contributor deletes or quarantines it, they've silently disabled the auth layer's self-healing behavior.

---

## §11. Dependencies to add

Single npm install step:

```bash
cd frontend
npm install react-hook-form@^7.53.0 @hookform/resolvers@^3.9.0 zod@^3.23.8
npm install --save-dev msw@^2.6.0
```

All four land in the same commit as the `lib/auth/schemas.ts` file that first consumes them. Lockfile captures exact versions.

---

## §12. File inventory

### New files (~25 production + ~30 test)

**Production (`frontend/src/`)**

| Path | Purpose |
|---|---|
| `lib/auth/session.ts` | `getAccessToken`/`setAccessToken`, `AuthUser`/`AuthSession` types |
| `lib/auth/api.ts` | Typed wrappers for `/api/auth/*` endpoints |
| `lib/auth/hooks.ts` | `useAuth`, `useLogin`, `useRegister`, `useLogout`, `useLogoutAll`, `useForgotPassword` |
| `lib/auth/schemas.ts` | Shared zod schemas |
| `lib/auth/errors.ts` | `mapProblemDetailToForm` helper |
| `lib/auth/passwordStrength.ts` | `computePasswordStrength` + helpers |
| `lib/auth/redirects.ts` | `getSafeRedirect` helper |
| `lib/auth/index.ts` | Public barrel |
| `components/ui/Checkbox.tsx` | Styled checkbox primitive |
| `components/ui/FormError.tsx` | Generic form-level error display |
| `components/ui/PasswordStrengthIndicator.tsx` | 4-bar strength visualization |
| `components/auth/AuthCard.tsx` | Compound layout for auth pages |
| `components/auth/RequireAuth.tsx` | Client-side protected-page guard |
| `components/auth/RegisterForm.tsx` | Register form component |
| `components/auth/LoginForm.tsx` | Login form component |
| `components/auth/ForgotPasswordForm.tsx` | Forgot password form + success state |
| `components/auth/UserMenuDropdown.tsx` | Header's authenticated cluster |

**Test infrastructure (`frontend/src/test/`)**

| Path | Purpose |
|---|---|
| `test/msw/server.ts` | `setupServer()` instance |
| `test/msw/handlers.ts` | Named handlers for every auth endpoint |
| `test/msw/fixtures.ts` | `mockUser`, `mockAuthResponse` |

### Modified files (~8)

| Path | Change |
|---|---|
| `frontend/package.json` | Add `react-hook-form`, `@hookform/resolvers`, `zod`, `msw` |
| `frontend/vitest.setup.ts` | Add MSW lifecycle hooks (`beforeAll`, `afterEach`, `afterAll`) with `onUnhandledRequest: "error"` |
| `frontend/src/lib/api.ts` | Add `configureApiClient`, 401 interceptor, stampede protection |
| `frontend/src/lib/auth.ts` | **Deleted** — replaced by `lib/auth/` directory |
| `frontend/src/app/providers.tsx` | Call `configureApiClient(queryClient)` once on mount |
| `frontend/src/components/layout/Header.tsx` | Wire the authenticated branch to `UserMenuDropdown` |
| `frontend/src/app/register/page.tsx` | Replace RSC placeholder with real form |
| `frontend/src/app/login/page.tsx` | Replace RSC placeholder with real form |
| `frontend/src/app/forgot-password/page.tsx` | Replace RSC placeholder with real form |
| `frontend/src/app/dashboard/page.tsx` | Replace RSC placeholder with RequireAuth + minimal content |
| `docs/implementation/FOOTGUNS.md` | Add Task 01-08 footgun entries + §5.9 stale-brief meta-lesson |

---

## §13. FOOTGUNS additions

Task 01-08 adds one new top-level section and one §5 meta-lesson entry to `docs/implementation/FOOTGUNS.md`.

### §5.9 (new meta-lesson, process)

**§5.9 Stale briefs are a drift source — patch N+1 briefs when Task N lands contradicting decisions.**

When Task N's spec or plan locks a decision that contradicts the original brief for Task N+1, patch the N+1 brief in the same PR. Otherwise N+1's implementer walks into a pre-resolved conflict and wastes a brainstorm round re-discovering the answer. Example: Task 01-07 locked in-memory-access-token + HttpOnly-refresh-cookie three days before Task 01-08's brainstorm, but Task 01-08's brief still said "localStorage or httpOnly cookie" and "remember me controls localStorage vs sessionStorage." The brainstorm spent the entire Q1 round re-litigating a resolved decision.

How to apply: when shipping a spec that contradicts an upstream brief for a dependent task, grep for the dependent task's brief in the same PR and patch any statements that no longer apply. Leave a one-line `> **Brief updated post-Task N-1:** <description>` note so the N+1 reader knows a correction happened.

### §Frontend (new top-level section)

Backend got §B in Task 01-07. Frontend gets its own section starting with Task 01-08. Naming: `## §F. Frontend / React / Next.js / Auth`.

Entries to add:

**§F.1 Access token lives in a module-level `let`, not a React `useRef`.**

The API client interceptor runs outside React's lifecycle. React refs are per-component and die on unmount. A module-level `let` in `lib/auth/session.ts` is the correct container for state that the interceptor reads synchronously and mutations write synchronously. Only `getAccessToken` and `setAccessToken` are exported — never the variable itself, so a grep for `accessToken = ` outside `session.ts` catches any mutation path that bypasses the setter.

**§F.2 The session query's side effect (setting the access token) lives inside `queryFn`, not `onSuccess`.**

TanStack Query's first subscriber receives `queryFn`'s return value directly — `onSuccess` only fires on subsequent subscribers and refetches. If you put `setAccessToken(response.accessToken)` in `onSuccess`, the first component to call `useAuth()` gets the user but the access token ref stays null until the next refetch, which may never happen. Place the side effect inside `queryFn` before returning, so the first subscribe path updates the token synchronously with the query cache.

**§F.3 `configureApiClient(queryClient)` — pass the QueryClient via setup function, not module global.**

The 401 interceptor in `lib/api.ts` needs to update the session query cache on failed refresh. Storing the QueryClient as a module global would create import-order footguns (the API client might initialize before the QueryClient exists). Instead, `providers.tsx` calls `configureApiClient(queryClient)` once on mount, passing the client into a module-scoped ref inside `lib/api.ts`. Keeps the dependency explicit and testable.

**§F.4 Concurrent 401 stampede — share one in-flight refresh promise.**

If three requests return 401 simultaneously, the naive interceptor fires three `/refresh` calls. Deduplicate via a single `inFlightRefresh: Promise<void> | null` at module scope. The first 401 starts the refresh and stores the promise; subsequent 401s await the same promise; all retry after it resolves. Classic pattern but easy to miss.

**§F.5 `staleTime: Infinity` + `gcTime: Infinity` + `retry: false` on the session query — all three are load-bearing.**

`staleTime: Infinity` prevents auto-refetching the session on every `useAuth()` call. `gcTime: Infinity` prevents garbage collection if `Header` unmounts temporarily. `retry: false` prevents three `/refresh` calls on a fresh visit — a 401 on bootstrap is a legitimate unauthenticated state, not a transient error. A contributor "tuning" these based on standard React Query advice would break the auth layer. Comment block above the `useQuery` call explains each flag.

**§F.6 `mapProblemDetailToForm` — `errors[]` is an array of `{field, message}`, not an object.**

The backend's `AuthExceptionHandler` and `GlobalExceptionHandler` emit `errors` as an array of `{field, message}` objects, not an object map. The helper must iterate the array. Don't assume the object shape — check `Array.isArray(problem.errors)` first.

**§F.7 `mapProblemDetailToForm` — unknown-field guard with console warn in dev.**

If the backend returns a `VALIDATION_FAILED` with a field that doesn't exist on the form, fall back to `root.serverError` with a concatenated message and `console.warn` in dev. Silent dropping hides drift between backend validators and frontend form fields. The warn is a drift-detection mechanism.

**§F.8 `onUnhandledRequest: "error"` in MSW setup is load-bearing.**

A future contributor who switches this to `"warn"` or `"bypass"` to make a flaky test pass has silently allowed real network requests from tests. That's the same failure mode as deleting a canary integration test — it masks a real integration gap. Do not relax it. If a test is flaky because a handler is missing, add the handler; don't widen the escape hatch.

**§F.9 The 401-auto-refresh canary is the frontend security model.**

The integration test that verifies "401 on protected endpoint → auto-refresh → retry → success" is the canary that proves the API client interceptor works. The complementary tests for stampede protection and refresh-failure redirect are also part of the canary. If a future contributor deletes or quarantines any of them, they've silently disabled the auth layer's self-healing behavior. Never delete. Named explicitly in the test file with a comment pointing back to this entry.

**§F.10 `useLogoutAll` must refresh before calling the endpoint.**

`POST /api/auth/logout-all` requires a valid access token (it's protected). If the user's access token is already expired when they click "Sign out all sessions," the call fails with 401 and the interceptor triggers a refresh anyway — so the operation succeeds after one round-trip. But this is messy. Cleaner: the `useLogoutAll` hook always calls `/refresh` first, gets a fresh access token, then calls `/logout-all`. Refresh is cheap (~100ms), and the alternative is a user-visible "something went wrong, try again" flicker.

**§F.11 `onSettled` for logout, not `onSuccess`.**

Logout should clear local state even if the network call fails. A user clicking "Sign Out" expects to be logged out regardless of whether the backend acknowledged the POST. Placing `setAccessToken(null)` + `setQueryData(null)` + `removeQueries` + `router.push("/")` in `onSettled` (rather than `onSuccess`) makes logout idempotent against network errors. Four lines, all idempotent.

**§F.12 `onSuccess` on login/register uses `setQueryData`, not `invalidateQueries`.**

`invalidateQueries` would trigger a wasted `/refresh` round-trip immediately after login. `setQueryData(["auth", "session"], response.user)` directly seats the cached value, so `Header` re-renders instantly without a network call. Use `invalidateQueries` only when you actually want to refetch from the server.

**§F.13 DESIGN.md §2 "No-Line Rule" wins over Stitch HTML.**

The Stitch design HTML often contains `border-t` or `border-b` classes that violate DESIGN.md §2. When a section separator is needed, use a background-color shift (e.g., `bg-surface-container-low`) or vertical spacing (`pt-6`) instead of a border. The Stitch HTML is a mockup; DESIGN.md is the rulebook. Example: `AuthCard.Footer` must not use `border-t border-outline-variant/10` even though the Stitch HTML shows it.

---

## §14. Out of scope

Things this task explicitly does NOT do:

- **Password reset email flow** — the forgot-password page is UI-only. The backend password-reset endpoint doesn't exist. A future task adds the backend + wires the real mutation into `useForgotPassword`.
- **Email verification flow** — `users.email_verified` exists in the backend but no verification flow. Future task.
- **Profile editing** — `PUT /api/users/me` and `DELETE /api/users/me` stay 501. Profile-edit needs its own design pass (field-level rules, soft-vs-hard delete, GDPR).
- **Real dashboard content** — `/dashboard` is a minimal placeholder with "Signed in as X" text. Real bid lists, listings, sales cards land in later tasks.
- **Avatar image upload** — `<UserMenuDropdown>` uses initials or a fallback icon, not a real avatar image. Image upload is future work.
- **`Sign out all sessions` in the header dropdown** — `useLogoutAll` is implemented (for use in a future account-settings page) but not surfaced in the header. Adding it to the dropdown is scope creep.
- **Two-factor authentication** — future task, well after MVP.
- **OAuth / SSO providers** — never in scope (per DESIGN.md).
- **Session listing UI** — the backend supports it via `last_used_at` / `user_agent` / `ip_address` on `refresh_tokens`, but no endpoint surfaces it and no frontend page consumes it.
- **zxcvbn or similar third-party password strength library** — hand-rolled algorithm is sufficient. Adding zxcvbn-ts (~80kb lazy-loaded) is future work only if user feedback says we need it.
- **Remember-me checkbox** — explicitly dropped. The backend's 7-day sliding refresh cookie IS remember-me. Helper text "Signed in for 7 days on this device" replaces it.

---

## Self-review checklist

Applied inline before committing:

- **Placeholder scan** — no TBDs, TODOs, or incomplete sections. The `React.useState` initializer trick for `configureApiClient` is the implementer's idiomatic call (not a placeholder). The dashboard content is intentionally minimal and flagged as out-of-scope for this task.
- **Internal consistency** — the `AuthResult` analog here is the `AuthResponse` type returned from the backend (`{accessToken, user}`). Only the shape of the `user` object matters on the frontend. The `setQueryData(["auth", "session"], response.user)` is consistent across login, register, and the bootstrap refresh path. The `onSettled` vs `onSuccess` split (logout vs login) is deliberate and documented.
- **Scope check** — one vertical slice (frontend auth) with documented touches to existing Task 01-06 modules (`lib/api.ts`, `Header.tsx`, `vitest.setup.ts`). Appropriately sized for a single implementation plan.
- **Ambiguity check** — every decision that had multiple reasonable answers in brainstorming (token storage, state architecture, form library, layout composition, strength algorithm, protected route approach, test infrastructure) is locked to one specific choice with its rationale documented.

---

## Next step

Invoke the `writing-plans` skill to produce the task-by-task implementation plan. The plan lives at `docs/superpowers/plans/2026-04-12-task-01-08-frontend-auth.md` on the same branch.
