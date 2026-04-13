# Task 01-09: WebSocket STOMP Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a JWT-authenticated STOMP-over-WebSocket pipe between the Next.js frontend and Spring Boot backend, with an end-to-end verification harness, so Epic 04's real-time auction features can build on top of it.

**Architecture:** Backend exposes `/ws` as a SockJS-capable STOMP endpoint; authentication happens inside the STOMP `CONNECT` frame via a `ChannelInterceptor` that reads the `Authorization: Bearer <jwt>` header and validates it with the existing `JwtService`. Frontend ships a singleton `@stomp/stompjs` client wrapped in a reference-counted module with observable connection state. A shared `ensureFreshAccessToken()` helper (extracted from `lib/api.ts`) dedupes refresh calls across the HTTP 401 interceptor and the WebSocket `beforeConnect` hook, keeping the single in-flight refresh promise invariant.

**Tech Stack:** Spring Boot 4 + Spring Messaging STOMP + SockJS, `@stomp/stompjs` v7, `sockjs-client`, Next.js 16 App Router, TanStack Query v5, Vitest + MSW, Mockito + JUnit 5, `WebSocketStompClient` (spring-messaging).

---

## Overview

**Spec:** `docs/superpowers/specs/2026-04-12-task-01-09-websocket-stomp-design.md` — the design source of truth. Read its §5 and §6 (backend and frontend detailed design) before starting implementation work.

**Worktree:** This plan executes in `C:/Users/heath/Repos/Personal/slpa-task-01-09`, branched from `dev`. Commit and push to `task/01-09-websocket-stomp`. Final PR targets `dev`, not `main`.

**Phases:**

| Phase | Tasks  | What it proves                                                     |
|-------|--------|--------------------------------------------------------------------|
| A     | 1–2    | Refresh stampede guard extracted; HTTP 401 canary still green.     |
| B     | 3–4    | STOMP broker + JWT `CONNECT` interceptor wired end-to-end.         |
| C     | 5–6    | Dev-profile broadcast endpoint; full backend E2E smoke test green. |
| D     | 7–10   | Frontend singleton client + hooks with full unit coverage.         |
| E     | 11     | Dev-only `/dev/ws-test` harness that exercises the live pipe.      |
| F     | 12     | FOOTGUNS §F.16–§F.19 and README sweep.                             |
| G     | 13     | Full verify chain + manual end-to-end smoke.                       |

**Commit discipline:**
- One atomic commit per task unless the task says otherwise.
- Commit messages: conventional commits (`feat:`, `fix:`, `test:`, `docs:`, `chore:`).
- No AI/tool attribution footers. No `Co-Authored-By`. No `--no-verify` on pre-commit hooks — if a hook fails, fix the underlying issue.
- Push after each task so review can happen continuously.

**Invariants that must NOT drift during execution:**
1. The `finally { inFlightRefresh = null; }` clause in `lib/auth/refresh.ts` lives **inside the IIFE**, not outside the Promise chain. See spec §F.17 and existing §F.4.
2. The HTTP 401 canary at `frontend/src/lib/api.401-interceptor.test.tsx` must stay green throughout. It's the security canary for the refresh pipeline.
3. `SecurityConfig` matchers are first-match-wins. `/ws/**` must appear ABOVE `/api/**` in the matcher list.
4. `WsTestController` is `@Profile({"dev", "test"})` — it must NOT exist in the prod bean graph.
5. The `subscribe()` function in `lib/ws/client.ts` defers to `onConnect` when the client isn't connected yet; do not "simplify" the deferral away.
6. `beforeConnect` in `lib/ws/client.ts` catches errors but NEVER throws — throwing from `beforeConnect` deactivates stompjs in confusing ways.

**Backend test commands:**
```
cd backend
./mvnw test -Dtest=JwtChannelInterceptorTest          # Task 3
./mvnw test -Dtest=WsTestIntegrationTest              # Task 6
./mvnw test                                           # All backend tests
```

**Frontend test commands:**
```
cd frontend
npx vitest run src/lib/auth/refresh.test.ts          # Task 1
npx vitest run src/lib/api.401-interceptor.test.tsx  # Task 2 (canary)
npx vitest run src/lib/ws                            # Tasks 9, 10
npm test                                             # Full frontend suite
npm run lint
npm run build
```

**Docker prerequisites for backend integration tests:**
- PostgreSQL running at `localhost:5432` (user `slpa`, password `slpa`, db `slpa`)
- Redis running at `localhost:6379`
- See memory `dev_containers.md` for exact `docker run` commands.

---

## Phase A — Frontend Refresh Extraction

**Phase goal:** Move the refresh-stampede guard out of `lib/api.ts` into a new shared module that the WebSocket client will also consume. The existing HTTP 401 canary test is the regression gate — it must stay green without any test-code changes.

**Why this phase runs first:** Doing the extraction before any WebSocket code means Phase D's `beforeConnect` can import `ensureFreshAccessToken` from day one. No late-stage refactor. The refactor is also isolated — if something goes wrong, only the HTTP path is affected, and we haven't built anything new on top yet.

---

### Task 1: Create `lib/auth/refresh.ts` with Stampede Canary

**Why:** Extracts the refresh-token deduplication primitive. Adds three unit tests (the "three-test canary" locked in Q7d) to pin behavior: single call, concurrent dedup, failure-and-retry. This task creates the module in isolation — it is not yet consumed by anyone.

**Files:**
- Create: `frontend/src/lib/auth/refresh.ts`
- Create: `frontend/src/lib/auth/refresh.test.ts`

---

- [ ] **Step 1: Write the failing tests**

Create `frontend/src/lib/auth/refresh.test.ts`:

```typescript
// frontend/src/lib/auth/refresh.test.ts
//
// CANARY — see FOOTGUNS §F.17. This test pins the stampede-dedup contract for
// the shared refresh helper. Both the HTTP 401 interceptor and the STOMP
// beforeConnect hook depend on `inFlightRefresh` being nulled inside the IIFE's
// finally clause. If this canary starts failing with "fetch called twice",
// someone has moved the finally out of the IIFE.

import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { QueryClient } from "@tanstack/react-query";
import { server } from "@/test/msw/server";
import {
  RefreshFailedError,
  configureRefresh,
  ensureFreshAccessToken,
  __resetRefreshStateForTests,
} from "./refresh";
import { getAccessToken, setAccessToken } from "./session";

describe("ensureFreshAccessToken (stampede canary)", () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    configureRefresh(queryClient);
    setAccessToken("stale-token");
  });

  afterEach(() => {
    __resetRefreshStateForTests();
    setAccessToken(null);
  });

  it("single call hits /api/auth/refresh once and returns the fresh token", async () => {
    let refreshCount = 0;
    server.use(
      http.post("*/api/auth/refresh", () => {
        refreshCount++;
        return HttpResponse.json({
          accessToken: "fresh-token-A",
          user: {
            id: 1,
            email: "t@example.com",
            displayName: null,
            slAvatarUuid: null,
            verified: false,
          },
        });
      })
    );

    const token = await ensureFreshAccessToken();

    expect(refreshCount).toBe(1);
    expect(token).toBe("fresh-token-A");
    expect(getAccessToken()).toBe("fresh-token-A");
    expect(queryClient.getQueryData(["auth", "session"])).toMatchObject({
      id: 1,
    });
  });

  it("three concurrent calls hit the backend exactly once and all resolve with the same token", async () => {
    let refreshCount = 0;
    server.use(
      http.post("*/api/auth/refresh", async () => {
        refreshCount++;
        // Small delay so the stampede has a window to form.
        await new Promise((resolve) => setTimeout(resolve, 20));
        return HttpResponse.json({
          accessToken: "fresh-token-B",
          user: {
            id: 2,
            email: "t2@example.com",
            displayName: null,
            slAvatarUuid: null,
            verified: false,
          },
        });
      })
    );

    const results = await Promise.all([
      ensureFreshAccessToken(),
      ensureFreshAccessToken(),
      ensureFreshAccessToken(),
    ]);

    expect(refreshCount).toBe(1);
    expect(results).toEqual(["fresh-token-B", "fresh-token-B", "fresh-token-B"]);
    expect(getAccessToken()).toBe("fresh-token-B");
  });

  it("failed refresh clears session, throws RefreshFailedError, and allows the next call to retry", async () => {
    let refreshCount = 0;
    server.use(
      http.post("*/api/auth/refresh", () => {
        refreshCount++;
        if (refreshCount === 1) {
          return HttpResponse.json(
            { code: "AUTH_TOKEN_MISSING", status: 401 },
            { status: 401 }
          );
        }
        return HttpResponse.json({
          accessToken: "fresh-token-C",
          user: {
            id: 3,
            email: "t3@example.com",
            displayName: null,
            slAvatarUuid: null,
            verified: false,
          },
        });
      })
    );

    // First call — fails.
    await expect(ensureFreshAccessToken()).rejects.toThrow(RefreshFailedError);
    expect(getAccessToken()).toBeNull();
    expect(queryClient.getQueryData(["auth", "session"])).toBeNull();

    // Second call — proves inFlightRefresh was cleared in the finally block
    // on the failure path. Fetch is called a SECOND time and resolves fresh.
    const token = await ensureFreshAccessToken();
    expect(refreshCount).toBe(2);
    expect(token).toBe("fresh-token-C");
    expect(getAccessToken()).toBe("fresh-token-C");
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npx vitest run src/lib/auth/refresh.test.ts`
Expected: FAIL with `Cannot find module './refresh'` or similar.

- [ ] **Step 3: Create `lib/auth/refresh.ts`**

Create `frontend/src/lib/auth/refresh.ts`:

```typescript
// frontend/src/lib/auth/refresh.ts
//
// Shared refresh-token stampede guard. Both the HTTP 401 interceptor
// (lib/api.ts) and the STOMP beforeConnect hook (lib/ws/client.ts) call
// ensureFreshAccessToken() — a single in-flight promise dedupes concurrent
// refresh attempts from either source.
//
// The finally clause that clears inFlightRefresh MUST live inside the IIFE,
// not outside the Promise chain. See FOOTGUNS §F.4 and §F.17.

import type { QueryClient } from "@tanstack/react-query";
import { setAccessToken } from "./session";
import type { AuthUser } from "./session";

let queryClientRef: QueryClient | null = null;
let inFlightRefresh: Promise<string> | null = null;

const BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const SESSION_QUERY_KEY = ["auth", "session"] as const;

type RefreshResponse = { accessToken: string; user: AuthUser };

export class RefreshFailedError extends Error {
  readonly status: number;
  constructor(status: number) {
    super(`Refresh failed with status ${status}`);
    this.name = "RefreshFailedError";
    this.status = status;
  }
}

export function configureRefresh(queryClient: QueryClient): void {
  queryClientRef = queryClient;
}

/**
 * Refreshes the access token, deduping concurrent callers onto a single
 * in-flight promise. Returns the new access token string. On failure, clears
 * the in-memory access token and the session query cache, then throws
 * RefreshFailedError.
 *
 * Callers that need redirect-to-login behavior (HTTP 401 interceptor) do so
 * after catching RefreshFailedError.
 */
export async function ensureFreshAccessToken(): Promise<string> {
  if (inFlightRefresh) return inFlightRefresh;

  inFlightRefresh = (async () => {
    try {
      const response = await fetch(`${BASE_URL}/api/auth/refresh`, {
        method: "POST",
        credentials: "include",
      });

      if (!response.ok) {
        setAccessToken(null);
        if (queryClientRef) {
          queryClientRef.setQueryData(SESSION_QUERY_KEY, null);
        }
        throw new RefreshFailedError(response.status);
      }

      const body = (await response.json()) as RefreshResponse;
      setAccessToken(body.accessToken);
      if (queryClientRef) {
        queryClientRef.setQueryData(SESSION_QUERY_KEY, body.user);
      }
      return body.accessToken;
    } finally {
      inFlightRefresh = null;
    }
  })();

  return inFlightRefresh;
}

// Test-only reset. The ugly name signals "do not call from production code".
export function __resetRefreshStateForTests(): void {
  inFlightRefresh = null;
  queryClientRef = null;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/lib/auth/refresh.test.ts`
Expected: PASS — 3 tests green.

- [ ] **Step 5: Run lint**

Run: `cd frontend && npm run lint`
Expected: PASS — no lint errors.

- [ ] **Step 6: Commit**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-09
git add frontend/src/lib/auth/refresh.ts frontend/src/lib/auth/refresh.test.ts
git commit -m "feat(auth): extract ensureFreshAccessToken stampede guard

Create lib/auth/refresh.ts as the shared refresh-token primitive. Both
the HTTP 401 interceptor (existing) and the STOMP beforeConnect hook
(Task 01-09 Phase D) will consume this single in-flight promise to
dedupe concurrent refresh attempts.

Ships with a three-test canary pinning:
1. single call hits /api/auth/refresh once
2. three concurrent calls dedupe onto one fetch
3. failed refresh clears session and allows the next call to retry

The finally clause inside the IIFE is load-bearing — see FOOTGUNS §F.4
and (incoming) §F.17. Both canary tests and the existing 401 interceptor
canary depend on it."
git push
```

---

### Task 2: Delegate `lib/api.ts` to `ensureFreshAccessToken`

**Why:** Replaces the inlined `handleUnauthorized` IIFE in `lib/api.ts` with a delegating call to the extracted helper. Keeps `configureApiClient` as a one-line passthrough so `providers.tsx` and the existing canary test do not need to change. Verifies the 401 canary stays green.

**Files:**
- Modify: `frontend/src/lib/api.ts`
- Test: `frontend/src/lib/api.401-interceptor.test.tsx` (unchanged — must still pass)

---

- [ ] **Step 1: Rewrite `lib/api.ts` to delegate**

Replace the file content with:

```typescript
import type { QueryClient } from "@tanstack/react-query";
import { getAccessToken } from "@/lib/auth/session";
import {
  ensureFreshAccessToken,
  configureRefresh,
  RefreshFailedError,
} from "@/lib/auth/refresh";

/**
 * Wires the API client to the app's QueryClient. Called once at app mount from
 * `app/providers.tsx`. Kept as a passthrough to `configureRefresh` so existing
 * call sites (providers.tsx, api.401-interceptor.test.tsx) do not need to
 * change when the refresh primitive moved into `lib/auth/refresh.ts`.
 */
export function configureApiClient(queryClient: QueryClient): void {
  configureRefresh(queryClient);
}

/**
 * RFC 7807 Problem Details. Matches the shape that
 * backend/src/main/java/.../common/GlobalExceptionHandler.java emits.
 *
 * `errors` is a SLPA extension for validation failures —
 * { fieldName: "must not be blank", ... } — populated by the
 * MethodArgumentNotValidException handler.
 */
export type ProblemDetail = {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  instance?: string;
  errors?: Record<string, string>;
  [key: string]: unknown;
};

/**
 * Thrown by every non-2xx response. Callers `try { } catch (e)` and
 * either rethrow, narrow with `isApiError(e)`, or read the normalized
 * `e.problem` to render field-level error messages.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly problem: ProblemDetail;

  constructor(problem: ProblemDetail) {
    super(problem.detail ?? problem.title ?? `HTTP ${problem.status}`);
    this.name = "ApiError";
    this.status = problem.status;
    this.problem = problem;
  }
}

/**
 * Type guard. Prefer this over `instanceof ApiError` across module
 * boundaries — bundler edge cases in RSC + client splits can produce
 * duplicate class identities, breaking instanceof checks.
 */
export function isApiError(e: unknown): e is ApiError {
  return e instanceof ApiError;
}

const BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

type RequestOptions = Omit<RequestInit, "body"> & {
  body?: unknown;
  params?: Record<string, string | number | boolean | undefined>;
};

function buildPath(
  path: string,
  params?: RequestOptions["params"]
): string {
  if (!params) return path;
  const sp = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined) continue;
    sp.append(key, String(value));
  }
  const qs = sp.toString();
  return qs ? `${path}?${qs}` : path;
}

/**
 * Handles a 401 response from a non-auth path by delegating to the shared
 * `ensureFreshAccessToken` helper and retrying the original request. On
 * refresh failure, performs the redirect-to-login side effect.
 *
 * The stampede-dedup logic lives in `lib/auth/refresh.ts` — see FOOTGUNS §F.4
 * and §F.17 for why the finally clause must live inside the IIFE there.
 */
async function handleUnauthorized<T>(
  path: string,
  options: RequestOptions
): Promise<T> {
  try {
    await ensureFreshAccessToken();
  } catch (e) {
    if (e instanceof RefreshFailedError) {
      if (typeof window !== "undefined") {
        const next = encodeURIComponent(
          window.location.pathname + window.location.search
        );
        window.location.href = `/login?next=${next}`;
      }
      const problem: ProblemDetail = { status: 401, title: "Session expired" };
      throw new ApiError(problem);
    }
    throw e;
  }
  return request<T>(path, options, /* isRetry */ true);
}

async function request<T>(
  path: string,
  { body, headers, params, ...rest }: RequestOptions = {},
  isRetry = false
): Promise<T> {
  const token = getAccessToken();
  const response = await fetch(`${BASE_URL}${buildPath(path, params)}`, {
    credentials: "include",
    ...rest,
    headers: {
      Accept: "application/json",
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });

  if (response.status === 401 && !isRetry && !path.startsWith("/api/auth/")) {
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

export const api = {
  get: <T>(path: string, options?: RequestOptions) =>
    request<T>(path, { ...options, method: "GET" }),
  post: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>(path, { ...options, method: "POST", body }),
  put: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>(path, { ...options, method: "PUT", body }),
  delete: <T>(path: string, options?: RequestOptions) =>
    request<T>(path, { ...options, method: "DELETE" }),
};
```

**What changed compared to the previous `lib/api.ts`:**
- Removed module-level `queryClientRef` and `inFlightRefresh` — those live in `lib/auth/refresh.ts` now.
- `configureApiClient` is now a one-line passthrough to `configureRefresh`.
- `handleUnauthorized` now calls `ensureFreshAccessToken()` and handles the `RefreshFailedError` redirect path.
- Everything else (request/response shapes, ApiError class, path building) is unchanged byte-for-byte.

- [ ] **Step 2: Run the HTTP 401 canary test**

Run: `cd frontend && npx vitest run src/lib/api.401-interceptor.test.tsx`
Expected: PASS — all 3 canary tests still green. If any fail, STOP and debug before touching anything else — the canary is the regression gate for the refresh pipeline.

- [ ] **Step 3: Run the new refresh canary**

Run: `cd frontend && npx vitest run src/lib/auth/refresh.test.ts`
Expected: PASS — all 3 canary tests still green.

- [ ] **Step 4: Run the full frontend test suite**

Run: `cd frontend && npm test`
Expected: PASS — the extracted module should not break any other test. Particularly watch for auth hook tests that may call `bootstrapSession()` through the same refresh path.

- [ ] **Step 5: Run lint and build**

Run: `cd frontend && npm run lint && npm run build`
Expected: PASS — no lint errors, production build succeeds.

- [ ] **Step 6: Commit**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-09
git add frontend/src/lib/api.ts
git commit -m "refactor(api): delegate 401 refresh to shared ensureFreshAccessToken

Replace the inlined handleUnauthorized IIFE with a call to
ensureFreshAccessToken from lib/auth/refresh. The HTTP 401 canary at
lib/api.401-interceptor.test.tsx is unchanged and still passes — its
contract is preserved because the stampede-dedup and failure-redirect
behaviors are identical, just moved.

configureApiClient is kept as a one-line passthrough to configureRefresh
so app/providers.tsx and the canary test imports do not need updating."
git push
```

---

## Phase B — Backend WebSocket Infrastructure

**Phase goal:** Wire up the STOMP broker, the CONNECT-time JWT validator, and the `/ws/**` security permit rule. At the end of this phase, a STOMP client can connect to `/ws` with a valid JWT and have its principal attached to the session. No broadcasting yet — that's Phase C.

---

### Task 3: `JwtChannelInterceptor` with Unit Tests

**Why:** Creates the STOMP CONNECT-frame authentication gate. The interceptor reads the `Authorization: Bearer <jwt>` header from the first CONNECT frame, validates it with the existing `JwtService`, and either attaches a `Principal` to the session or throws `MessagingException` to reject. Six unit tests pin every branch.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auth/StompAuthenticationToken.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auth/JwtChannelInterceptor.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auth/JwtChannelInterceptorTest.java`

---

- [ ] **Step 1: Create `StompAuthenticationToken`**

Create `backend/src/main/java/com/slparcelauctions/backend/auth/StompAuthenticationToken.java`:

```java
package com.slparcelauctions.backend.auth;

import java.security.Principal;

/**
 * Minimal {@link Principal} wrapper attached to a STOMP session by
 * {@link JwtChannelInterceptor} after a successful CONNECT-frame JWT
 * validation. Spring's {@code SimpMessagingTemplate.convertAndSendToUser}
 * resolves user-scoped destinations via {@link #getName()}.
 *
 * <p>This wrapper exists so Epic 04 can convert and send to specific users
 * for per-auction notifications. For Task 01-09, only {@link #principal()} is
 * read (via {@code @AuthenticationPrincipal} on the test controller, which
 * reaches in through the Spring MVC resolver chain anyway).
 */
public record StompAuthenticationToken(AuthPrincipal principal) implements Principal {
    @Override
    public String getName() {
        return String.valueOf(principal.userId());
    }
}
```

- [ ] **Step 2: Write the failing interceptor tests**

Create `backend/src/test/java/com/slparcelauctions/backend/auth/JwtChannelInterceptorTest.java`:

```java
package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.auth.exception.TokenExpiredException;
import com.slparcelauctions.backend.auth.exception.TokenInvalidException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class JwtChannelInterceptorTest {

    private JwtService jwtService;
    private JwtChannelInterceptor interceptor;
    private MessageChannel channel;

    @BeforeEach
    void setUp() {
        jwtService = Mockito.mock(JwtService.class);
        interceptor = new JwtChannelInterceptor(jwtService);
        channel = Mockito.mock(MessageChannel.class);
    }

    private Message<byte[]> stompMessage(StompCommand command, String authHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (authHeader != null) {
            accessor.setNativeHeader("Authorization", authHeader);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("non-CONNECT frames pass through unchanged")
    void preSend_nonConnectFrame_passesThrough() {
        Message<byte[]> msg = stompMessage(StompCommand.SEND, null);

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        Mockito.verifyNoInteractions(jwtService);
    }

    @Test
    @DisplayName("CONNECT with missing Authorization header is rejected")
    void preSend_connectFrame_missingAuthHeader_throws() {
        Message<byte[]> msg = stompMessage(StompCommand.CONNECT, null);

        assertThatThrownBy(() -> interceptor.preSend(msg, channel))
            .isInstanceOf(MessagingException.class)
            .hasMessageContaining("Missing or invalid Authorization header");

        Mockito.verifyNoInteractions(jwtService);
    }

    @Test
    @DisplayName("CONNECT with malformed Bearer header is rejected")
    void preSend_connectFrame_malformedBearer_throws() {
        Message<byte[]> msg = stompMessage(StompCommand.CONNECT, "NotBearer foo");

        assertThatThrownBy(() -> interceptor.preSend(msg, channel))
            .isInstanceOf(MessagingException.class)
            .hasMessageContaining("Missing or invalid Authorization header");

        Mockito.verifyNoInteractions(jwtService);
    }

    @Test
    @DisplayName("CONNECT with valid token attaches StompAuthenticationToken principal")
    void preSend_connectFrame_validToken_attachesPrincipal() {
        AuthPrincipal authPrincipal = new AuthPrincipal(42L, "test@example.com", 1L);
        when(jwtService.parseAccessToken("valid-jwt")).thenReturn(authPrincipal);

        Message<byte[]> msg = stompMessage(StompCommand.CONNECT, "Bearer valid-jwt");

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isNotNull();
        StompHeaderAccessor resultAccessor =
            StompHeaderAccessor.wrap(result);
        Principal user = resultAccessor.getUser();
        assertThat(user).isInstanceOf(StompAuthenticationToken.class);
        assertThat(user.getName()).isEqualTo("42");
        StompAuthenticationToken token = (StompAuthenticationToken) user;
        assertThat(token.principal().userId()).isEqualTo(42L);
        assertThat(token.principal().email()).isEqualTo("test@example.com");
        assertThat(token.principal().tokenVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("CONNECT with expired token is rejected")
    void preSend_connectFrame_expiredToken_throws() {
        when(jwtService.parseAccessToken(any()))
            .thenThrow(new TokenExpiredException("expired"));

        Message<byte[]> msg = stompMessage(StompCommand.CONNECT, "Bearer expired-jwt");

        assertThatThrownBy(() -> interceptor.preSend(msg, channel))
            .isInstanceOf(MessagingException.class)
            .hasMessageContaining("Invalid or expired access token");
    }

    @Test
    @DisplayName("CONNECT with invalid token is rejected")
    void preSend_connectFrame_invalidToken_throws() {
        when(jwtService.parseAccessToken(any()))
            .thenThrow(new TokenInvalidException("bad token"));

        Message<byte[]> msg = stompMessage(StompCommand.CONNECT, "Bearer bad-jwt");

        assertThatThrownBy(() -> interceptor.preSend(msg, channel))
            .isInstanceOf(MessagingException.class)
            .hasMessageContaining("Invalid or expired access token");
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=JwtChannelInterceptorTest`
Expected: FAIL — `JwtChannelInterceptor` class does not exist yet. Compilation error, or the test class won't even run.

- [ ] **Step 4: Implement `JwtChannelInterceptor`**

Create `backend/src/main/java/com/slparcelauctions/backend/auth/JwtChannelInterceptor.java`:

```java
package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.auth.exception.TokenExpiredException;
import com.slparcelauctions.backend.auth.exception.TokenInvalidException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * STOMP CONNECT-frame authentication gate. Reads the {@code Authorization}
 * native header from the first {@link StompCommand#CONNECT} frame, parses it
 * via {@link JwtService}, and attaches a {@link StompAuthenticationToken} to
 * the session. Subsequent frames (SUBSCRIBE, SEND, DISCONNECT) pass through
 * unchanged — the principal set here lives on the session for their lifetime.
 *
 * <p><strong>Why CONNECT-only:</strong> Task 01-09 spec §3 (Q3c-i). Matching
 * the HTTP filter's one-check-per-request behavior. Epic 04 may revisit if
 * sensitive write operations ever flow through {@code @MessageMapping}
 * handlers.
 *
 * <p><strong>Why throw {@link MessagingException}:</strong> Spring's STOMP
 * infrastructure catches it and sends a STOMP ERROR frame back to the client
 * before closing the session. The frontend's {@code onStompError} handler
 * picks it up and surfaces the error via the ConnectionState machine.
 *
 * <p><strong>Why {@code getFirstNativeHeader}, not {@code getHeader}:</strong>
 * STOMP headers arrive as a {@code MultiValueMap<String, String>} under the
 * key {@code nativeHeaders}. {@code getHeader} looks up Spring-internal
 * framework headers and returns null for STOMP client headers.
 * See FOOTGUNS §F.16.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtChannelInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
            MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("STOMP CONNECT rejected: missing or non-Bearer Authorization header");
            throw new MessagingException(message, "Missing or invalid Authorization header");
        }

        String token = authHeader.substring(7);
        try {
            AuthPrincipal principal = jwtService.parseAccessToken(token);
            accessor.setUser(new StompAuthenticationToken(principal));
            log.debug("STOMP CONNECT authenticated: userId={}", principal.userId());
            return message;
        } catch (TokenExpiredException | TokenInvalidException e) {
            log.debug("STOMP CONNECT rejected: {}", e.getMessage());
            throw new MessagingException(message, "Invalid or expired access token");
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=JwtChannelInterceptorTest`
Expected: PASS — all 6 tests green.

- [ ] **Step 6: Commit**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-09
git add backend/src/main/java/com/slparcelauctions/backend/auth/StompAuthenticationToken.java \
        backend/src/main/java/com/slparcelauctions/backend/auth/JwtChannelInterceptor.java \
        backend/src/test/java/com/slparcelauctions/backend/auth/JwtChannelInterceptorTest.java
git commit -m "feat(auth): add JwtChannelInterceptor for STOMP CONNECT auth

Authenticate the first STOMP CONNECT frame by reading the
Authorization: Bearer <jwt> header and delegating to the existing
JwtService.parseAccessToken. On success, attach a StompAuthenticationToken
to the session via accessor.setUser. On any failure (missing header,
malformed bearer, expired token, invalid token), throw MessagingException
which Spring translates to a STOMP ERROR frame.

Subsequent frames (SUBSCRIBE, SEND, DISCONNECT) pass through unchanged.
Matches the HTTP filter's one-check-per-request behavior — see spec §3
Q3c-i.

Ships with 6 unit tests covering:
- non-CONNECT frame passthrough
- CONNECT with missing Authorization header
- CONNECT with malformed Bearer prefix
- CONNECT with valid token (principal attached)
- CONNECT with expired token
- CONNECT with invalid token"
git push
```

---

### Task 4: `WebSocketConfig` + SecurityConfig `/ws/**` Permit

**Why:** Wires up the STOMP broker (`/topic` destinations, SockJS `/ws` endpoint, `/app` application prefix) and injects the `JwtChannelInterceptor` into the client inbound channel. Also updates `SecurityConfig` to permit the raw HTTP WebSocket upgrade on `/ws/**` — authentication happens at the STOMP layer, not the HTTP layer, so the HTTP matcher must be `permitAll`.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/config/WebSocketConfig.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java`

---

- [ ] **Step 1: Create `WebSocketConfig`**

Create `backend/src/main/java/com/slparcelauctions/backend/config/WebSocketConfig.java`:

```java
package com.slparcelauctions.backend.config;

import com.slparcelauctions.backend.auth.JwtChannelInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket configuration. Exposes a single endpoint at {@code /ws}
 * with SockJS fallback, a simple in-memory broker for {@code /topic}
 * destinations, and the {@link JwtChannelInterceptor} on the client inbound
 * channel.
 *
 * <p><strong>In-memory broker:</strong> sufficient for Phase 1 single-instance
 * deployments. Epic 04 may swap to a relayed broker (RabbitMQ STOMP plugin)
 * when cross-instance fanout is needed. Leaving this comment so the Epic 04
 * implementer does not need to rediscover the rationale.
 *
 * <p><strong>{@code /app} application prefix:</strong> unused in Task 01-09
 * (the test controller uses REST + {@code SimpMessagingTemplate}) but set
 * here so Epic 04 can add {@code @MessageMapping("/bid")} handlers without
 * reconfiguring the broker.
 *
 * <p><strong>CORS on the endpoint:</strong> Spring's STOMP endpoint CORS is
 * configured separately from the main {@code HttpSecurity} CORS source.
 * Without {@code .setAllowedOrigins()} the SockJS handshake XHR fails with a
 * confusing CORS error even though the main security filter passes.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtChannelInterceptor jwtChannelInterceptor;

    @Value("${cors.allowed-origin:http://localhost:3000}")
    private String allowedOrigin;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
            .setAllowedOrigins(allowedOrigin)
            .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(jwtChannelInterceptor);
    }
}
```

- [ ] **Step 2: Update `SecurityConfig` to permit `/ws/**`**

Open `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java` and add a new matcher above `/api/**`:

Replace the `.authorizeHttpRequests(...)` block with:

```java
.authorizeHttpRequests(auth -> auth
        .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
        .requestMatchers(
                "/api/auth/register",
                "/api/auth/login",
                "/api/auth/refresh",
                "/api/auth/logout"
        ).permitAll()
        .requestMatchers(HttpMethod.POST, "/api/users").permitAll()
        .requestMatchers(HttpMethod.GET, "/api/users/me").authenticated()
        .requestMatchers(HttpMethod.GET, "/api/users/{id}").permitAll()
        .requestMatchers("/api/auth/logout-all").authenticated()
        // WebSocket handshake is permitted at the HTTP layer.
        // Authentication happens at the STOMP CONNECT frame via
        // JwtChannelInterceptor. Do NOT change this to .authenticated() —
        // the browser WebSocket API cannot set an Authorization header on
        // the HTTP upgrade, so gating it here is impossible. See FOOTGUNS §F.16.
        .requestMatchers("/ws/**").permitAll()
        .requestMatchers("/api/**").authenticated()
        .anyRequest().denyAll())
```

Everything else in `SecurityConfig` (imports, fields, CORS config, the `@Bean` definitions) stays the same.

- [ ] **Step 3: Run the full backend test suite**

Run: `cd backend && ./mvnw test`
Expected: PASS — no tests should break. `JwtChannelInterceptorTest` from Task 3 still passes; no new integration tests yet.

- [ ] **Step 4: Verify the app starts**

Run: `cd backend && ./mvnw spring-boot:run`

In another terminal, verify the `/ws/info` SockJS endpoint responds:

```bash
curl -s http://localhost:8080/ws/info
```

Expected: JSON response containing `"websocket":true` and a `"origins"` array. This is SockJS's "is this endpoint alive?" check — if it 404s, the endpoint is not registered.

Stop the backend with Ctrl+C.

- [ ] **Step 5: Commit**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-09
git add backend/src/main/java/com/slparcelauctions/backend/config/WebSocketConfig.java \
        backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java
git commit -m "feat(ws): configure STOMP broker and permit /ws/** at HTTP layer

- WebSocketConfig: simple in-memory broker on /topic, /app application
  prefix (unused now, ready for Epic 04's @MessageMapping handlers),
  SockJS endpoint at /ws with explicit setAllowedOrigins for the CORS
  handshake, JwtChannelInterceptor on clientInboundChannel.
- SecurityConfig: permit /ws/** above the /api/** authenticated catch-all.
  Authentication happens at the STOMP CONNECT frame layer, not the HTTP
  upgrade — browsers cannot set an Authorization header on WebSocket
  upgrades, so HTTP-layer auth is impossible."
git push
```

---

## Phase C — Backend Test Harness

**Phase goal:** Ship a dev/test profile-gated REST endpoint that proves the broker wiring works end-to-end. A real STOMP client connecting to the backend can subscribe to `/topic/ws-test`, receive a broadcast triggered via `POST /api/ws-test/broadcast`, and reject unauthenticated connects.

---

### Task 5: `WsTestController` + `WsTestBroadcastRequest` DTO

**Why:** Creates the HTTP-triggered broadcast endpoint. Profile-gated to `dev` and `test` so it's entirely absent from the prod bean graph. The controller is trivial — POST a message, shove it onto `/topic/ws-test` via `SimpMessagingTemplate`.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/wstest/dto/WsTestBroadcastRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/wstest/WsTestController.java`

---

- [ ] **Step 1: Create the request DTO**

Create `backend/src/main/java/com/slparcelauctions/backend/wstest/dto/WsTestBroadcastRequest.java`:

```java
package com.slparcelauctions.backend.wstest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WsTestBroadcastRequest(
    @NotBlank @Size(max = 500) String message
) {}
```

- [ ] **Step 2: Create the controller**

Create `backend/src/main/java/com/slparcelauctions/backend/wstest/WsTestController.java`:

```java
package com.slparcelauctions.backend.wstest;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.wstest.dto.WsTestBroadcastRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Dev/test-only WebSocket verification harness. Broadcasts whatever the caller
 * POSTs onto {@code /topic/ws-test}, which any authenticated STOMP subscriber
 * receives in real time.
 *
 * <p><strong>Profile gating:</strong> {@code @Profile({"dev", "test"})} means
 * this bean is absent from the prod application context entirely. There is no
 * SecurityConfig matcher to maintain because there is no bean to guard.
 *
 * <p><strong>Authentication:</strong> the broadcast endpoint falls under the
 * {@code /api/**} authenticated catch-all in {@code SecurityConfig} — only
 * logged-in users can trigger a broadcast. The {@code AuthPrincipal} injected
 * here is the same one the JWT filter attaches in {@code JwtAuthenticationFilter}.
 *
 * <p><strong>Wire-type note:</strong> {@code principal.userId()} is a Java
 * {@code Long}. Jackson serializes it as a JSON number, which lands in
 * JavaScript as a plain {@code number}. Safe because user IDs are well under
 * {@code Number.MAX_SAFE_INTEGER} (2^53 - 1). The frontend harness types
 * {@code senderId} as {@code number} to match.
 */
@RestController
@RequestMapping("/api/ws-test")
@RequiredArgsConstructor
@Profile({"dev", "test"})
@Slf4j
public class WsTestController {

    private final SimpMessagingTemplate messagingTemplate;

    @PostMapping("/broadcast")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void broadcast(
            @Valid @RequestBody WsTestBroadcastRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        log.info("WS test broadcast from userId={}: {}", principal.userId(), request.message());
        messagingTemplate.convertAndSend("/topic/ws-test",
            Map.of(
                "message", request.message(),
                "senderId", principal.userId(),
                "timestamp", Instant.now().toString()
            ));
    }
}
```

- [ ] **Step 3: Run the full backend test suite**

Run: `cd backend && ./mvnw test`
Expected: PASS — no tests break. The new controller has no tests yet (those come in Task 6 as an integration test).

- [ ] **Step 4: Manual verification (optional but recommended)**

Run: `cd backend && ./mvnw spring-boot:run`

In another terminal, get an access token by registering and logging in, then POST to the broadcast endpoint:

```bash
# Register a dev user (or use an existing one)
curl -s -c /tmp/slpa-cookies.txt \
  -H "Content-Type: application/json" \
  -d '{"email":"wstest@example.com","password":"Testpass123!","displayName":null}' \
  http://localhost:8080/api/auth/register | jq .

# Copy the accessToken from the response. Then:
export TOKEN="<paste-access-token>"

curl -v -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"hello"}' \
  http://localhost:8080/api/ws-test/broadcast
```

Expected: `204 No Content`. Log line in the backend console like `WS test broadcast from userId=1: hello`. No STOMP subscribers yet, so nothing receives the message — but the endpoint fires cleanly.

Stop the backend with Ctrl+C.

- [ ] **Step 5: Commit**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-09
git add backend/src/main/java/com/slparcelauctions/backend/wstest/dto/WsTestBroadcastRequest.java \
        backend/src/main/java/com/slparcelauctions/backend/wstest/WsTestController.java
git commit -m "feat(wstest): add dev/test profile-gated broadcast endpoint

POST /api/ws-test/broadcast pushes the provided message onto
/topic/ws-test via SimpMessagingTemplate. Authenticated via the existing
JWT filter; the endpoint is @Profile({\"dev\", \"test\"}) so it is
entirely absent from the prod bean graph.

Ships alongside WsTestBroadcastRequest DTO with @NotBlank + @Size(500)
validation."
git push
```

---

### Task 6: `WsTestIntegrationTest` — End-to-End Smoke Test

**Why:** The single load-bearing backend test. Proves the entire pipe works: STOMP client connects with a JWT, interceptor validates and attaches the principal, subscription to `/topic/ws-test` is accepted, POST to broadcast endpoint triggers a message that the STOMP client actually receives. Also covers the two rejection paths (missing header, invalid token).

**Files:**
- Create: `backend/src/test/java/com/slparcelauctions/backend/wstest/WsTestIntegrationTest.java`

---

- [ ] **Step 1: Write the integration test**

Create `backend/src/test/java/com/slparcelauctions/backend/wstest/WsTestIntegrationTest.java`:

```java
package com.slparcelauctions.backend.wstest;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.wstest.dto.WsTestBroadcastRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WsTestIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private WebSocketStompClient stompClient;
    private BlockingQueue<Map<String, Object>> receivedMessages;

    @BeforeEach
    void setUp() {
        List<Transport> transports = List.of(new WebSocketTransport(new StandardWebSocketClient()));
        SockJsClient sockJsClient = new SockJsClient(transports);
        stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
        receivedMessages = new LinkedBlockingQueue<>();
    }

    @AfterEach
    void tearDown() {
        if (stompClient != null) {
            stompClient.stop();
        }
    }

    private String issueAccessTokenForTestUser() {
        AuthPrincipal principal = new AuthPrincipal(9999L, "wstest@example.com", 1L);
        return jwtService.issueAccessToken(principal);
    }

    private String wsUrl() {
        return "http://localhost:" + port + "/ws";
    }

    @Test
    void stompConnectWithValidToken_receivesBroadcast() throws Exception {
        String token = issueAccessTokenForTestUser();

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();
        stompClient.connectAsync(
            wsUrl(),
            new WebSocketHttpHeaders(),
            connectHeaders,
            new StompSessionHandlerAdapter() {
                @Override
                public void afterConnected(StompSession session, StompHeaders headers) {
                    sessionFuture.complete(session);
                }

                @Override
                public void handleException(StompSession session, StompCommand command,
                                            StompHeaders headers, byte[] payload, Throwable exception) {
                    sessionFuture.completeExceptionally(exception);
                }

                @Override
                public void handleTransportError(StompSession session, Throwable exception) {
                    if (!sessionFuture.isDone()) {
                        sessionFuture.completeExceptionally(exception);
                    }
                }
            }
        );

        StompSession session = sessionFuture.get(5, TimeUnit.SECONDS);

        session.subscribe("/topic/ws-test", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                receivedMessages.offer((Map<String, Object>) payload);
            }
        });

        // Tiny pause to let the subscription register before we broadcast.
        Thread.sleep(200);

        // Trigger the broadcast via the HTTP endpoint.
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setBearerAuth(token);
        httpHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        HttpEntity<WsTestBroadcastRequest> request =
            new HttpEntity<>(new WsTestBroadcastRequest("hello from test"), httpHeaders);

        ResponseEntity<Void> response = restTemplate.exchange(
            "http://localhost:" + port + "/api/ws-test/broadcast",
            HttpMethod.POST,
            request,
            Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Map<String, Object> received = receivedMessages.poll(5, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.get("message")).isEqualTo("hello from test");
        assertThat(received.get("senderId")).isEqualTo(9999);
        assertThat(received.get("timestamp")).isNotNull();
    }

    @Test
    void stompConnectWithoutAuthHeader_isRejected() {
        // No Authorization header in connectHeaders — interceptor throws
        // MessagingException which surfaces as a connect failure.
        StompHeaders connectHeaders = new StompHeaders();

        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();
        stompClient.connectAsync(
            wsUrl(),
            new WebSocketHttpHeaders(),
            connectHeaders,
            new StompSessionHandlerAdapter() {
                @Override
                public void afterConnected(StompSession session, StompHeaders headers) {
                    sessionFuture.complete(session);
                }

                @Override
                public void handleException(StompSession session, StompCommand command,
                                            StompHeaders headers, byte[] payload, Throwable exception) {
                    sessionFuture.completeExceptionally(exception);
                }

                @Override
                public void handleTransportError(StompSession session, Throwable exception) {
                    if (!sessionFuture.isDone()) {
                        sessionFuture.completeExceptionally(exception);
                    }
                }
            }
        );

        assertThatThrownBy(() -> sessionFuture.get(5, TimeUnit.SECONDS))
            .isInstanceOfAny(ExecutionException.class, TimeoutException.class);
    }

    @Test
    void stompConnectWithInvalidToken_isRejected() {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer not-a-real-jwt");

        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();
        stompClient.connectAsync(
            wsUrl(),
            new WebSocketHttpHeaders(),
            connectHeaders,
            new StompSessionHandlerAdapter() {
                @Override
                public void afterConnected(StompSession session, StompHeaders headers) {
                    sessionFuture.complete(session);
                }

                @Override
                public void handleException(StompSession session, StompCommand command,
                                            StompHeaders headers, byte[] payload, Throwable exception) {
                    sessionFuture.completeExceptionally(exception);
                }

                @Override
                public void handleTransportError(StompSession session, Throwable exception) {
                    if (!sessionFuture.isDone()) {
                        sessionFuture.completeExceptionally(exception);
                    }
                }
            }
        );

        assertThatThrownBy(() -> sessionFuture.get(5, TimeUnit.SECONDS))
            .isInstanceOfAny(ExecutionException.class, TimeoutException.class);
    }
}
```

**Why `@DirtiesContext(AFTER_CLASS)`:** the STOMP broker holds background threads (message scheduler, heartbeat). Without dirtying the context, a second integration test class in the same JVM may leak sessions. Cheap insurance.

**Why `@ActiveProfiles("test")`:** activates `WsTestController`'s `@Profile({"dev", "test"})` gate. Without this, the bean is absent and the broadcast POST returns 404.

**Why `Thread.sleep(200)` before broadcasting:** STOMP `subscribe()` is sent asynchronously. Without the pause, the broadcast sometimes fires before the server registers the subscription and the test flakes. 200ms is comfortable.

- [ ] **Step 2: Run the integration test**

**Prerequisite:** Postgres and Redis containers must be running (see memory `dev_containers.md`).

Run: `cd backend && ./mvnw test -Dtest=WsTestIntegrationTest`
Expected: PASS — all 3 tests green. Test 1 completes within ~1s after the sleep; tests 2 and 3 complete within the 5s timeout.

If test 1 flakes on the broadcast receipt (poll returns null), try raising the poll timeout from 5s to 10s — CI machines are slower than dev boxes.

- [ ] **Step 3: Run the full backend test suite**

Run: `cd backend && ./mvnw test`
Expected: PASS — everything green including the new integration test.

- [ ] **Step 4: Commit**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-09
git add backend/src/test/java/com/slparcelauctions/backend/wstest/WsTestIntegrationTest.java
git commit -m "test(ws): end-to-end STOMP integration smoke test

Single load-bearing integration test covering the full WebSocket pipe:
- STOMP client connects to /ws with a freshly-issued JWT
- JwtChannelInterceptor validates, attaches the principal
- Subscription to /topic/ws-test accepted
- POST /api/ws-test/broadcast triggers a message
- The subscriber receives it within the timeout

Also covers the two rejection paths: missing Authorization header on
connect, and malformed JWT on connect.

@ActiveProfiles(\"test\") activates WsTestController via its
@Profile({\"dev\",\"test\"}) gate. @DirtiesContext(AFTER_CLASS) cleans
up the broker threads between test classes."
git push
```

---

## Phase D — Frontend WebSocket Client

**Phase goal:** Ship the singleton, reference-counted, observable-state STOMP client plus the React hooks that wrap it. At the end of Phase D, any component can call `useStompSubscription("/topic/foo", cb)` and `useConnectionState()` to participate in the WebSocket pipe.

---

### Task 7: Install Frontend Dependencies

**Why:** Pulls in `@stomp/stompjs` v7 (the STOMP client library), `sockjs-client` (the SockJS transport), and `@types/sockjs-client` (the TypeScript definitions). Version pinning notes in the spec §6.1.

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json` (auto-generated)

---

- [ ] **Step 1: Install the runtime dependencies**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-09/frontend
npm install @stomp/stompjs@^7.1.1 sockjs-client@^1.6.1
```

- [ ] **Step 2: Install the type definitions as a dev dependency**

```bash
npm install -D @types/sockjs-client@^1.5.4
```

- [ ] **Step 3: Verify the installation**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-09/frontend
node -e "console.log(require('@stomp/stompjs/package.json').version)"
node -e "console.log(require('sockjs-client/package.json').version)"
```

Expected: prints `7.1.1` (or `7.x.y` where y >= 1) and `1.6.1` (or higher).

- [ ] **Step 4: Verify the build still works**

Run: `cd frontend && npm run build`
Expected: PASS — production build succeeds. The deps are not yet used by any code, so they're just available.

- [ ] **Step 5: Commit**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-09
git add frontend/package.json frontend/package-lock.json
git commit -m "chore(deps): add @stomp/stompjs, sockjs-client for WebSocket client

- @stomp/stompjs ^7.1.1 — browser STOMP client with fully-awaited
  beforeConnect hook (v7.1+ guarantees async await in the connect
  sequence, which our shared ensureFreshAccessToken depends on).
- sockjs-client ^1.6.1 — SockJS fallback transport for proxies that
  strip WebSocket upgrades.
- @types/sockjs-client ^1.5.4 — type definitions.

Not yet consumed by any code; lib/ws/client.ts in the next task will
import them."
git push
```

---

### Task 8: `lib/ws/types.ts`

**Why:** Small type-only module carrying the discriminated-union `ConnectionState` and the `Unsubscribe` alias. Lives separately from `client.ts` so tests can import the types without triggering the client's module-level state.

**Files:**
- Create: `frontend/src/lib/ws/types.ts`

---

- [ ] **Step 1: Create `lib/ws/types.ts`**

Create `frontend/src/lib/ws/types.ts`:

```typescript
// frontend/src/lib/ws/types.ts
//
// Type-only module for the WebSocket layer. Separate from client.ts so tests
// can import the types without pulling in client.ts's module-level state.

/**
 * Discriminated union of the five states the WebSocket connection can be in.
 * Consumers branch on `status` and TypeScript narrows the shape.
 *
 *   disconnected  — initial state, or after the last subscriber unsubscribed
 *                   past the grace window.
 *   connecting    — beforeConnect has fired; awaiting STOMP CONNECTED.
 *   connected     — STOMP session is live, subscriptions can flow.
 *   reconnecting  — WebSocket closed unexpectedly and stompjs is retrying.
 *   error         — a STOMP ERROR frame arrived or auth refresh failed.
 */
export type ConnectionState =
  | { status: "disconnected" }
  | { status: "connecting" }
  | { status: "connected" }
  | { status: "reconnecting" }
  | { status: "error"; detail: string };

/**
 * Unsubscribe handle returned by `subscribe()` and `subscribeToConnectionState()`.
 * Calling the handle removes the subscription; idempotent.
 */
export type Unsubscribe = () => void;
```

- [ ] **Step 2: Verify it compiles**

Run: `cd frontend && npx tsc --noEmit`
Expected: PASS — no type errors. (If `tsc --noEmit` isn't set up, `npm run build` also works.)

- [ ] **Step 3: Commit**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-09
git add frontend/src/lib/ws/types.ts
git commit -m "feat(ws): add ConnectionState and Unsubscribe types

Type-only module for the WebSocket layer, separate from client.ts so
tests can import the types without triggering client.ts's module-level
state. Discriminated union with five states: disconnected, connecting,
connected, reconnecting, error."
git push
```

---

### Task 9: `lib/ws/client.ts` — Singleton Client with Unit Tests

**Why:** The heart of the frontend WebSocket layer. Singleton module-level `@stomp/stompjs` client with reference counting, 5-second disconnect grace period, observable connection state, and a `subscribe<T>(destination, onMessage)` primitive with generic JSON-parse wrapping. Nine unit tests pin every public-API branch and state transition.

**Files:**
- Create: `frontend/src/lib/ws/client.ts`
- Create: `frontend/src/lib/ws/client.test.ts`

---

- [ ] **Step 1: Write the failing tests**

Create `frontend/src/lib/ws/client.test.ts`:

```typescript
// frontend/src/lib/ws/client.test.ts
//
// Unit tests for lib/ws/client.ts. Mocks @stomp/stompjs so tests run without
// a real WebSocket — the captured callbacks on the mock Client instance let
// us simulate onConnect, onWebSocketClose, onStompError etc. manually.

import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from "vitest";

// The mocked Client class. Captured callbacks and flags live on the instance
// so tests can trigger lifecycle events on demand.
type MockStompClient = {
  activate: Mock;
  deactivate: Mock;
  subscribe: Mock;
  connected: boolean;
  active: boolean;
  connectHeaders: Record<string, string>;
  beforeConnect?: () => void | Promise<void>;
  onConnect?: () => void;
  onWebSocketClose?: () => void;
  onStompError?: (frame: { headers: Record<string, string> }) => void;
};

let mockClientInstance: MockStompClient | null = null;

vi.mock("@stomp/stompjs", () => {
  return {
    Client: vi.fn().mockImplementation((config: Partial<MockStompClient>) => {
      const instance: MockStompClient = {
        activate: vi.fn(() => {
          instance.active = true;
        }),
        deactivate: vi.fn(() => {
          instance.active = false;
          instance.connected = false;
        }),
        subscribe: vi.fn(() => ({ unsubscribe: vi.fn() })),
        connected: false,
        active: false,
        connectHeaders: {},
        beforeConnect: config.beforeConnect,
        onConnect: config.onConnect,
        onWebSocketClose: config.onWebSocketClose,
        onStompError: config.onStompError,
      };
      mockClientInstance = instance;
      return instance;
    }),
  };
});

vi.mock("sockjs-client", () => ({
  default: vi.fn().mockImplementation(() => ({})),
}));

vi.mock("@/lib/auth/refresh", () => ({
  ensureFreshAccessToken: vi.fn(async () => "mock-access-token"),
  RefreshFailedError: class extends Error {
    readonly status: number;
    constructor(status: number) {
      super(`Refresh failed ${status}`);
      this.status = status;
    }
  },
}));

// Import UNDER TEST after the mocks are declared.
import {
  __devForceDisconnect,
  __devForceReconnect,
  __resetWsClientForTests,
  getConnectionState,
  subscribe,
  subscribeToConnectionState,
} from "./client";
import { ensureFreshAccessToken } from "@/lib/auth/refresh";

describe("lib/ws/client", () => {
  beforeEach(() => {
    __resetWsClientForTests();
    mockClientInstance = null;
    vi.clearAllMocks();
  });

  afterEach(() => {
    __resetWsClientForTests();
  });

  it("first subscribe activates the client", () => {
    subscribe<unknown>("/topic/foo", () => {});

    expect(mockClientInstance).not.toBeNull();
    expect(mockClientInstance!.activate).toHaveBeenCalledTimes(1);
  });

  it("second subscribe reuses the existing client (single activate)", () => {
    subscribe<unknown>("/topic/foo", () => {});
    subscribe<unknown>("/topic/bar", () => {});

    expect(mockClientInstance!.activate).toHaveBeenCalledTimes(1);
  });

  it("last unsubscribe schedules disconnect with 5s grace period", () => {
    vi.useFakeTimers();
    try {
      const unsub = subscribe<unknown>("/topic/foo", () => {});

      unsub();
      // Not yet — grace period.
      expect(mockClientInstance!.deactivate).not.toHaveBeenCalled();

      vi.advanceTimersByTime(5_000);
      expect(mockClientInstance!.deactivate).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it("resubscribe within grace window cancels the scheduled teardown", () => {
    vi.useFakeTimers();
    try {
      const unsub1 = subscribe<unknown>("/topic/foo", () => {});

      unsub1();
      vi.advanceTimersByTime(4_000);
      // Second subscribe within grace — teardown should cancel.
      subscribe<unknown>("/topic/bar", () => {});
      vi.advanceTimersByTime(5_000);

      expect(mockClientInstance!.deactivate).not.toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });

  it("beforeConnect calls ensureFreshAccessToken and sets Authorization header", async () => {
    subscribe<unknown>("/topic/foo", () => {});

    // Invoke the captured beforeConnect callback.
    await mockClientInstance!.beforeConnect!();

    expect(ensureFreshAccessToken).toHaveBeenCalledTimes(1);
    expect(mockClientInstance!.connectHeaders.Authorization).toBe("Bearer mock-access-token");
  });

  it("onConnect transitions ConnectionState to connected", () => {
    const states: string[] = [];
    subscribeToConnectionState((s) => states.push(s.status));

    subscribe<unknown>("/topic/foo", () => {});
    // Simulate the captured onConnect firing.
    mockClientInstance!.onConnect!();

    expect(getConnectionState().status).toBe("connected");
    expect(states).toContain("connected");
  });

  it("onWebSocketClose while active transitions ConnectionState to reconnecting", () => {
    subscribe<unknown>("/topic/foo", () => {});
    mockClientInstance!.active = true;

    mockClientInstance!.onWebSocketClose!();

    expect(getConnectionState().status).toBe("reconnecting");
  });

  it("onStompError transitions ConnectionState to error with the frame detail", () => {
    subscribe<unknown>("/topic/foo", () => {});

    mockClientInstance!.onStompError!({ headers: { message: "Auth failed" } });

    const state = getConnectionState();
    expect(state.status).toBe("error");
    if (state.status === "error") {
      expect(state.detail).toBe("Auth failed");
    }
  });

  it("subscribe before connected defers the stompjs subscribe until onConnect", () => {
    const handler = vi.fn();
    subscribe<{ msg: string }>("/topic/foo", handler);

    // Client is not connected yet.
    mockClientInstance!.connected = false;
    expect(mockClientInstance!.subscribe).not.toHaveBeenCalled();

    // Now connect — the deferred attach should fire.
    mockClientInstance!.connected = true;
    mockClientInstance!.onConnect!();

    expect(mockClientInstance!.subscribe).toHaveBeenCalledTimes(1);
    expect(mockClientInstance!.subscribe.mock.calls[0][0]).toBe("/topic/foo");
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npx vitest run src/lib/ws/client.test.ts`
Expected: FAIL — module `./client` not found.

- [ ] **Step 3: Create `lib/ws/client.ts`**

Create `frontend/src/lib/ws/client.ts`:

```typescript
// frontend/src/lib/ws/client.ts
"use client";

import { Client, type IMessage, type StompSubscription } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import { ensureFreshAccessToken, RefreshFailedError } from "@/lib/auth/refresh";
import type { ConnectionState, Unsubscribe } from "./types";

const WS_URL = process.env.NEXT_PUBLIC_WS_URL ?? "http://localhost:8080/ws";
const DISCONNECT_GRACE_MS = 5_000;

let client: Client | null = null;
let subscriberCount = 0;
let disconnectTimer: ReturnType<typeof setTimeout> | null = null;
let connectionState: ConnectionState = { status: "disconnected" };
const stateListeners = new Set<(state: ConnectionState) => void>();

function setState(next: ConnectionState): void {
  connectionState = next;
  for (const listener of stateListeners) listener(next);
}

export function getConnectionState(): ConnectionState {
  return connectionState;
}

export function subscribeToConnectionState(
  listener: (state: ConnectionState) => void
): Unsubscribe {
  stateListeners.add(listener);
  // Fire immediately with current state so consumers have something to render.
  listener(connectionState);
  return () => {
    stateListeners.delete(listener);
  };
}

function getOrCreateClient(): Client {
  if (client) return client;

  client = new Client({
    webSocketFactory: () => new SockJS(WS_URL),
    reconnectDelay: 5_000,
    heartbeatIncoming: 10_000,
    heartbeatOutgoing: 10_000,
    beforeConnect: async () => {
      if (!client) return;
      setState({ status: "connecting" });
      try {
        const token = await ensureFreshAccessToken();
        client.connectHeaders = { Authorization: `Bearer ${token}` };
      } catch (e) {
        // Do NOT throw from beforeConnect — stompjs handles a thrown
        // beforeConnect as a catastrophic failure. Instead, let stompjs
        // proceed without an Authorization header; the interceptor will
        // reject the CONNECT frame, onStompError will fire, and the
        // ConnectionState machine will surface the error.
        //
        // See FOOTGUNS §F.18.
        if (e instanceof RefreshFailedError) {
          setState({
            status: "error",
            detail: "Session expired — please sign in again",
          });
        } else {
          setState({
            status: "error",
            detail: "Could not refresh access token",
          });
        }
      }
    },
    onConnect: () => {
      setState({ status: "connected" });
    },
    onWebSocketClose: () => {
      if (client?.active) {
        setState({ status: "reconnecting" });
      } else {
        setState({ status: "disconnected" });
      }
    },
    onStompError: (frame) => {
      const detail = frame.headers["message"] ?? "STOMP error";
      setState({ status: "error", detail });
    },
  });

  return client;
}

/**
 * Subscribe to a STOMP destination. Returns an unsubscribe closure. The
 * callback receives the JSON-parsed message payload typed as `T`. Malformed
 * JSON is logged via `console.error` and the subscription stays alive.
 *
 * Reference counting: the first subscribe activates the singleton client;
 * the last unsubscribe schedules a `DISCONNECT_GRACE_MS` deactivation. A new
 * subscribe inside the grace window cancels the teardown.
 *
 * Deferral: if the client is not yet connected, the actual `client.subscribe`
 * call is deferred until `onConnect` fires. See FOOTGUNS §F.19.
 */
export function subscribe<T>(
  destination: string,
  onMessage: (payload: T) => void
): Unsubscribe {
  const c = getOrCreateClient();
  subscriberCount += 1;

  if (disconnectTimer) {
    clearTimeout(disconnectTimer);
    disconnectTimer = null;
  }

  if (!c.active) {
    c.activate();
  }

  let stompSub: StompSubscription | null = null;

  const attach = () => {
    stompSub = c.subscribe(destination, (frame: IMessage) => {
      try {
        const parsed = JSON.parse(frame.body) as T;
        onMessage(parsed);
      } catch (err) {
        console.error(
          `[ws] Failed to parse message body on ${destination}:`,
          err,
          frame.body
        );
      }
    });
  };

  if (c.connected) {
    attach();
  } else {
    // KNOWN RACE (Epic 04 hardening followup, spec §14.7):
    //   If onConnect → onWebSocketClose fires in rapid succession, the
    //   listener sees "reconnecting" mid-cycle and keeps waiting through the
    //   reconnect. On the next successful connect the listener fires and
    //   attach() runs — subscription not lost, just delayed by one reconnect
    //   cycle. Acceptable for 01-09; Epic 04 will want a more robust
    //   re-attach strategy.
    const stateUnsub = subscribeToConnectionState((state) => {
      if (state.status === "connected") {
        attach();
        stateUnsub();
      }
    });
  }

  return () => {
    if (stompSub) stompSub.unsubscribe();
    subscriberCount = Math.max(0, subscriberCount - 1);
    if (subscriberCount === 0) {
      scheduleTeardown();
    }
  };
}

function scheduleTeardown(): void {
  if (disconnectTimer) return;
  disconnectTimer = setTimeout(() => {
    disconnectTimer = null;
    if (subscriberCount === 0 && client) {
      client.deactivate();
      setState({ status: "disconnected" });
    }
  }, DISCONNECT_GRACE_MS);
}

// Manual controls for the /dev/ws-test page's Disconnect / Reconnect buttons.
// Consumers should NOT use these in production code — they bypass reference
// counting and can leave other subscribers stranded.
export function __devForceDisconnect(): void {
  if (client?.active) client.deactivate();
  setState({ status: "disconnected" });
}

export function __devForceReconnect(): void {
  const c = getOrCreateClient();
  if (!c.active) c.activate();
}

// Test-only reset. Clears all module state so each test starts from scratch.
export function __resetWsClientForTests(): void {
  if (client) {
    try {
      client.deactivate();
    } catch {
      // Ignore — mocked client may not implement deactivate cleanly.
    }
  }
  client = null;
  subscriberCount = 0;
  if (disconnectTimer) {
    clearTimeout(disconnectTimer);
    disconnectTimer = null;
  }
  stateListeners.clear();
  connectionState = { status: "disconnected" };
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/lib/ws/client.test.ts`
Expected: PASS — all 9 tests green.

If test 6 (`onConnect transitions state to connected`) fails because the listener was already registered when the state was `"connecting"`, check that the test uses `subscribeToConnectionState` BEFORE calling `subscribe`. The listener fires immediately with the current state when registered.

- [ ] **Step 5: Run the full frontend test suite**

Run: `cd frontend && npm test`
Expected: PASS — no regressions in any existing tests.

- [ ] **Step 6: Run lint and build**

Run: `cd frontend && npm run lint && npm run build`
Expected: PASS — no lint errors, production build succeeds.

- [ ] **Step 7: Commit**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-09
git add frontend/src/lib/ws/client.ts frontend/src/lib/ws/client.test.ts
git commit -m "feat(ws): singleton STOMP client with reference counting

lib/ws/client.ts is the module-level @stomp/stompjs client wrapped in a
reference-counted API. First subscribe activates the client; last
unsubscribe schedules a DISCONNECT_GRACE_MS (5s) deactivation; a new
subscribe inside the grace window cancels the teardown.

Key contract:
- beforeConnect calls ensureFreshAccessToken and sets the Authorization
  header; catches RefreshFailedError and stores it in ConnectionState
  without throwing (stompjs handles thrown beforeConnect badly).
- onConnect / onWebSocketClose / onStompError drive the ConnectionState
  discriminated union; consumers subscribe via subscribeToConnectionState.
- subscribe<T>() defers the actual client.subscribe until onConnect if
  the client is not yet connected — see spec §14.7 for the known race.
- JSON.parse is wrapped in try/catch so malformed frames don't kill the
  subscription.

Ships with 9 unit tests via mocked @stomp/stompjs covering all public
API branches and state transitions."
git push
```

---

### Task 10: `lib/ws/hooks.ts` — React Hook Wrappers

**Why:** Thin React hooks around the module primitives. `useConnectionState` subscribes via `subscribeToConnectionState`; `useStompSubscription` wraps `subscribe()` in a `useEffect` with a stable-callback ref so re-renders don't resubscribe. Three tests cover the React integration.

**Files:**
- Create: `frontend/src/lib/ws/hooks.ts`
- Create: `frontend/src/lib/ws/hooks.test.tsx`

---

- [ ] **Step 1: Write the failing tests**

Create `frontend/src/lib/ws/hooks.test.tsx`:

```typescript
// frontend/src/lib/ws/hooks.test.tsx

import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from "vitest";
import { render, renderHook, act } from "@testing-library/react";

// Track calls to the module primitives without pulling in stompjs.
const subscribeMock = vi.fn();
const subscribeToConnectionStateMock = vi.fn();
const getConnectionStateMock = vi.fn(() => ({ status: "disconnected" as const }));

vi.mock("./client", () => ({
  subscribe: (...args: unknown[]) => {
    subscribeMock(...args);
    return () => {
      // Unsubscribe closure is tracked via subscribeMock.mock.results.
    };
  },
  subscribeToConnectionState: (
    listener: (state: { status: string }) => void
  ) => {
    subscribeToConnectionStateMock(listener);
    // Fire immediately with current state, like the real impl.
    listener(getConnectionStateMock());
    return () => {};
  },
  getConnectionState: getConnectionStateMock,
}));

import { useConnectionState, useStompSubscription } from "./hooks";

describe("lib/ws/hooks", () => {
  beforeEach(() => {
    subscribeMock.mockReset();
    (subscribeMock as unknown as Mock).mockImplementation(() => () => {});
    subscribeToConnectionStateMock.mockReset();
    getConnectionStateMock.mockReset();
    getConnectionStateMock.mockReturnValue({ status: "disconnected" });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("useConnectionState renders the current state and reacts to updates", () => {
    let capturedListener: ((s: { status: string }) => void) | null = null;
    subscribeToConnectionStateMock.mockImplementation((listener) => {
      capturedListener = listener;
      listener({ status: "disconnected" });
      return () => {};
    });

    const { result } = renderHook(() => useConnectionState());
    expect(result.current.status).toBe("disconnected");

    act(() => {
      capturedListener!({ status: "connected" });
    });

    expect(result.current.status).toBe("connected");
  });

  it("useStompSubscription subscribes on mount and unsubscribes on unmount", () => {
    const unsubscribeMock = vi.fn();
    subscribeMock.mockImplementation(() => unsubscribeMock);

    function Probe() {
      useStompSubscription<{ msg: string }>("/topic/foo", () => {});
      return null;
    }

    const { unmount } = render(<Probe />);
    expect(subscribeMock).toHaveBeenCalledTimes(1);
    expect(subscribeMock.mock.calls[0][0]).toBe("/topic/foo");

    unmount();
    expect(unsubscribeMock).toHaveBeenCalledTimes(1);
  });

  it("useStompSubscription does not re-subscribe when the callback identity changes", () => {
    const unsubscribeMock = vi.fn();
    subscribeMock.mockImplementation(() => unsubscribeMock);

    let renderCount = 0;
    function Probe() {
      renderCount++;
      // Inline arrow creates a new function identity on every render.
      useStompSubscription<{ msg: string }>("/topic/foo", () => {
        // noop
      });
      return null;
    }

    const { rerender } = render(<Probe />);
    rerender(<Probe />);
    rerender(<Probe />);

    expect(renderCount).toBe(3);
    expect(subscribeMock).toHaveBeenCalledTimes(1);
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npx vitest run src/lib/ws/hooks.test.tsx`
Expected: FAIL — module `./hooks` not found.

- [ ] **Step 3: Create `lib/ws/hooks.ts`**

Create `frontend/src/lib/ws/hooks.ts`:

```typescript
// frontend/src/lib/ws/hooks.ts
"use client";

import { useEffect, useRef, useState } from "react";
import {
  getConnectionState,
  subscribe,
  subscribeToConnectionState,
} from "./client";
import type { ConnectionState } from "./types";

/**
 * React-friendly accessor for the module-level ConnectionState. Re-renders
 * whenever the underlying state changes. Fires immediately on mount with the
 * current state (the listener is invoked synchronously inside
 * `subscribeToConnectionState`).
 */
export function useConnectionState(): ConnectionState {
  const [state, setState] = useState<ConnectionState>(() => getConnectionState());
  useEffect(() => {
    return subscribeToConnectionState(setState);
  }, []);
  return state;
}

/**
 * React-friendly wrapper around `subscribe()`. The callback is held in a ref
 * so re-renders with a new inline function identity do not re-subscribe; only
 * a change to the `destination` string triggers a new subscription.
 */
export function useStompSubscription<T>(
  destination: string,
  onMessage: (payload: T) => void
): void {
  const callbackRef = useRef(onMessage);
  callbackRef.current = onMessage;

  useEffect(() => {
    const unsubscribe = subscribe<T>(destination, (payload) => {
      callbackRef.current(payload);
    });
    return unsubscribe;
  }, [destination]);
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/lib/ws/hooks.test.tsx`
Expected: PASS — all 3 tests green.

- [ ] **Step 5: Run lint**

Run: `cd frontend && npm run lint`
Expected: PASS. If the React compiler complains about the ref pattern, add an `// eslint-disable-next-line` comment on the `callbackRef.current = onMessage;` line with a note that this is intentional stable-callback pattern.

- [ ] **Step 6: Commit**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-09
git add frontend/src/lib/ws/hooks.ts frontend/src/lib/ws/hooks.test.tsx
git commit -m "feat(ws): useConnectionState and useStompSubscription hooks

Thin React hooks around the module primitives from lib/ws/client.ts.

useConnectionState:
- subscribes via subscribeToConnectionState; re-renders on state change.
- initial value comes from getConnectionState() so the first render has
  the correct state without waiting for the first effect.

useStompSubscription:
- holds the callback in a ref so re-renders with new inline functions
  do not re-subscribe (classic stable-callback pattern).
- [destination] dep array — a destination change triggers a new sub.

Ships with 3 tests covering state rendering/updates, mount/unmount
subscribe lifecycle, and callback-identity stability."
git push
```

---

## Phase E — Frontend Test Harness

**Phase goal:** Ship the `/dev/ws-test` page and its `WsTestHarness` component. This is the browser-based verification surface that proves the full pipe works end-to-end when a human clicks through it. Also adds the `NEXT_PUBLIC_WS_URL` env var example.

---

### Task 11: `WsTestHarness` Component + `/dev/ws-test` Page + `.env.local.example`

**Why:** Bundled task because the page, the component, and the env var all ship together as the "dev harness" unit and have no independent value. The page is a thin server component that 404s in production and renders `WsTestHarness` in development. The harness has four zones: connection status, manual controls, broadcast form, received messages log.

**Files:**
- Create: `frontend/src/components/dev/WsTestHarness.tsx`
- Create: `frontend/src/app/dev/ws-test/page.tsx`
- Create: `frontend/.env.local.example`

---

- [ ] **Step 1: Create `WsTestHarness` component**

Create `frontend/src/components/dev/WsTestHarness.tsx`:

```tsx
// frontend/src/components/dev/WsTestHarness.tsx
"use client";

import { useState } from "react";
import { api } from "@/lib/api";
import { __devForceDisconnect, __devForceReconnect } from "@/lib/ws/client";
import { useConnectionState, useStompSubscription } from "@/lib/ws/hooks";
import type { ConnectionState } from "@/lib/ws/types";

// senderId is a Java Long on the backend, serialized as a JSON number, which
// parses to a plain JS number here. User IDs are well under 2^53 - 1. See
// spec §5.4 for the wire-type note.
type WsTestMessage = {
  message: string;
  senderId: number;
  timestamp: string;
};

const MAX_MESSAGES = 50;

export function WsTestHarness() {
  const state = useConnectionState();
  const [messages, setMessages] = useState<WsTestMessage[]>([]);
  const [input, setInput] = useState("");
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);

  useStompSubscription<WsTestMessage>("/topic/ws-test", (payload) => {
    setMessages((prev) => [payload, ...prev].slice(0, MAX_MESSAGES));
  });

  const sendBroadcast = async () => {
    const trimmed = input.trim();
    if (!trimmed) return;
    setSending(true);
    setSendError(null);
    try {
      await api.post("/api/ws-test/broadcast", { message: trimmed });
      setInput("");
    } catch (err) {
      setSendError(err instanceof Error ? err.message : "Failed to send");
    } finally {
      setSending(false);
    }
  };

  return (
    <main className="mx-auto max-w-3xl p-8">
      <h1 className="text-2xl font-semibold text-on-surface">
        WebSocket Test Harness
      </h1>
      <p className="mt-2 text-on-surface-variant">
        Dev-only page for verifying the STOMP pipe. Returns 404 in production
        builds.
      </p>

      <div className="mt-6">
        <ConnectionBadge state={state} />
      </div>

      <div className="mt-4 flex gap-2">
        <button
          type="button"
          onClick={__devForceDisconnect}
          className="rounded-md bg-surface-container px-3 py-2 text-sm text-on-surface hover:bg-surface-container-high"
        >
          Force Disconnect
        </button>
        <button
          type="button"
          onClick={__devForceReconnect}
          className="rounded-md bg-surface-container px-3 py-2 text-sm text-on-surface hover:bg-surface-container-high"
        >
          Force Reconnect
        </button>
      </div>

      <form
        onSubmit={(e) => {
          e.preventDefault();
          void sendBroadcast();
        }}
        className="mt-8"
      >
        <label
          htmlFor="ws-test-input"
          className="block text-sm font-medium text-on-surface"
        >
          Send test message
        </label>
        <div className="mt-2 flex gap-2">
          <input
            id="ws-test-input"
            type="text"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="Type a message"
            className="flex-1 rounded-md bg-surface-container px-3 py-2 text-on-surface placeholder:text-on-surface-variant"
          />
          <button
            type="submit"
            disabled={sending || !input.trim()}
            className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary disabled:opacity-50"
          >
            {sending ? "Sending..." : "Send"}
          </button>
        </div>
        {sendError ? (
          <p className="mt-2 text-sm text-error">{sendError}</p>
        ) : null}
      </form>

      <section className="mt-8">
        <h2 className="text-lg font-medium text-on-surface">
          Received ({messages.length})
        </h2>
        <ol className="mt-2 space-y-2">
          {messages.map((m, i) => (
            <li
              key={`${m.timestamp}-${i}`}
              className="rounded-md bg-surface-container p-3"
            >
              <div className="flex items-baseline justify-between">
                <span className="text-on-surface">{m.message}</span>
                <span className="font-mono text-xs text-on-surface-variant">
                  {m.timestamp}
                </span>
              </div>
              <div className="mt-1 text-xs text-on-surface-variant">
                from userId {m.senderId}
              </div>
            </li>
          ))}
          {messages.length === 0 ? (
            <li className="rounded-md bg-surface-container p-3 text-sm text-on-surface-variant">
              No messages yet. Send one or trigger a broadcast from another
              tab / curl.
            </li>
          ) : null}
        </ol>
      </section>
    </main>
  );
}

function ConnectionBadge({ state }: { state: ConnectionState }) {
  const label = {
    disconnected: "Disconnected",
    connecting: "Connecting...",
    connected: "Connected",
    reconnecting: "Reconnecting...",
    error: "Error",
  }[state.status];

  const tone = {
    disconnected: "bg-surface-container text-on-surface-variant",
    connecting: "bg-tertiary-container text-on-tertiary-container",
    connected: "bg-primary-container text-on-primary-container",
    reconnecting: "bg-tertiary-container text-on-tertiary-container",
    error: "bg-error-container text-on-error-container",
  }[state.status];

  return (
    <div className={`inline-flex flex-col rounded-md px-3 py-2 text-sm ${tone}`}>
      <span className="font-medium">{label}</span>
      {state.status === "error" ? (
        <span className="mt-1 text-xs">{state.detail}</span>
      ) : null}
    </div>
  );
}
```

**Design-system notes:**
- All colors use semantic tokens from the Tailwind config (`text-on-surface`, `bg-surface-container`, `bg-primary-container`, etc.). No hex values. No inline styles. The harness is a dev tool but still has to pass `npm run verify:no-hex-colors` and `npm run verify:no-inline-styles`.
- No `border-*` classes. DESIGN.md §2 "No-Line Rule" applies everywhere.
- If your project's Tailwind config doesn't have an `bg-tertiary-container` token, check `frontend/tailwind.config.ts` or `frontend/src/app/globals.css` for the actual available tokens and pick the closest. The lint-verify scripts are authoritative.

- [ ] **Step 2: Create the page file**

Create `frontend/src/app/dev/ws-test/page.tsx`:

```tsx
// frontend/src/app/dev/ws-test/page.tsx
import { notFound } from "next/navigation";
import { WsTestHarness } from "@/components/dev/WsTestHarness";

export default function WsTestPage() {
  // Route 404s in production — /dev/** is a development-only surface.
  if (process.env.NODE_ENV === "production") {
    notFound();
  }
  return <WsTestHarness />;
}
```

- [ ] **Step 3: Create `.env.local.example`**

Create `frontend/.env.local.example` (if one doesn't already exist — check with `ls frontend/.env*` first and only create it if absent):

```
# Copy to .env.local for local development. Never commit .env.local.

NEXT_PUBLIC_API_URL=http://localhost:8080
NEXT_PUBLIC_WS_URL=http://localhost:8080/ws
```

If the file already exists, append the `NEXT_PUBLIC_WS_URL` line instead.

- [ ] **Step 4: Run lint**

Run: `cd frontend && npm run lint`
Expected: PASS. If the ESLint React-compiler or react/no-unescaped-entities rule complains, address the specific warning — do not blanket-disable.

- [ ] **Step 5: Run the full frontend test suite**

Run: `cd frontend && npm test`
Expected: PASS — no regressions. The harness and page have no direct unit tests (they're manually verified in Task 13), but importing them must not break existing tests.

- [ ] **Step 6: Run the verify chain**

Run: `cd frontend && npm run verify`
Expected: PASS — no-dark-variants, no-hex-colors, no-inline-styles, and coverage scripts all pass.

- [ ] **Step 7: Run the production build**

Run: `cd frontend && npm run build`
Expected: PASS. Specifically watch for:
- Next.js static-generation errors on `/dev/ws-test`. The `notFound()` call in a Server Component during SSG returns a 404 page, which is fine — the build should succeed.
- `"use client"` boundary errors. `WsTestHarness` is a client component imported from a server-component page; that's the correct pattern.

- [ ] **Step 8: Commit**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-09
git add frontend/src/components/dev/WsTestHarness.tsx \
        frontend/src/app/dev/ws-test/page.tsx \
        frontend/.env.local.example
git commit -m "feat(dev): /dev/ws-test harness with four verification zones

Browser-based WebSocket verification surface. Renders in development
and 404s in production via a NODE_ENV check in the server-component
page wrapper.

Four zones in WsTestHarness:
1. Connection status badge (driven by useConnectionState).
2. Force Disconnect / Force Reconnect manual controls (exercise the
   reconnect path without killing the backend).
3. Send test message form (POST /api/ws-test/broadcast).
4. Received messages log (last 50, subscribed via useStompSubscription).

Adds NEXT_PUBLIC_WS_URL to .env.local.example so contributors know to
set it alongside NEXT_PUBLIC_API_URL."
git push
```

---

## Phase F — Documentation

**Phase goal:** Ship the FOOTGUNS additions locked in the spec §13 and update the root README.md with a one-liner about the new dev harness and WebSocket infrastructure. Per the auto-memory `feedback_update_readme_each_task.md`, the README sweep is mandatory for every task.

---

### Task 12: FOOTGUNS §F.16–§F.19 + README Sweep

**Why:** Encode the four WebSocket-related invariants into the permanent FOOTGUNS ledger so future contributors can't accidentally unwind them. Also update the README to mention the new `/dev/ws-test` harness and the WebSocket pipe.

**Files:**
- Modify: `docs/implementation/FOOTGUNS.md`
- Modify: `README.md`

---

- [ ] **Step 1: Open `FOOTGUNS.md` and locate the end of §F**

Find the `§F.15` block (the last existing frontend entry). Append the four new entries after it.

- [ ] **Step 2: Add §F.16 — WebSocket HTTP permit rule**

Append to `docs/implementation/FOOTGUNS.md`:

```markdown
### F.16 WebSocket handshake is permitted at HTTP, authenticated at STOMP

**Rule:** `SecurityConfig` must list `/ws/**` as `.permitAll()` above the `/api/**` authenticated catch-all. The HTTP-layer WebSocket upgrade does not carry an `Authorization` header from the browser (the WebSocket API has no mechanism to set custom headers on upgrades), so gating the upgrade at the HTTP layer is impossible. Authentication happens in `JwtChannelInterceptor.preSend()` on the first STOMP `CONNECT` frame, before the session is usable for any subscription or send.

**Why:** Task 01-09 locked this model in the brainstorm (spec §3 Q1-A). Matcher order in `SecurityConfig` is first-match-wins (§B.5), so `/ws/**` must appear ABOVE `/api/**`. Moving it below the catch-all silently flips it from permitAll to authenticated and every WebSocket connection starts failing with a bewildering 401 before the STOMP layer even sees the frame.

**How to apply:** when editing `SecurityConfig.authorizeHttpRequests`, verify the `/ws/**` matcher is still above `/api/**`. If code review proposes "tightening" it to `.authenticated()`, reject — browsers cannot send the required header.
```

- [ ] **Step 3: Add §F.17 — Extracted stampede guard's finally-in-IIFE invariant**

Append:

```markdown
### F.17 `ensureFreshAccessToken` stampede guard — `finally` inside the IIFE

**Rule:** In `lib/auth/refresh.ts`, the `inFlightRefresh = null` cleanup MUST live inside the IIFE's `try { ... } finally { inFlightRefresh = null; }`, not after the outer promise chain. The shared promise between HTTP 401 interceptor and STOMP `beforeConnect` depends on this: if the cleanup runs outside the IIFE, every awaiter nulls the ref as they resolve, and a concurrent refresh kicked off during the resolution window sees `null` and fires a second `/api/auth/refresh`, defeating the stampede guard.

**Why:** this is §F.4 restated for the extracted module. Two canary tests pin this behavior:
- `frontend/src/lib/auth/refresh.test.ts` (three tests, local to the extracted module)
- `frontend/src/lib/api.401-interceptor.test.tsx` (three tests, HTTP end-to-end via MSW)

If either canary starts failing with "fetch called twice instead of once", the `finally` clause has been moved.

**How to apply:** when touching `lib/auth/refresh.ts`, diff the `finally` placement. If code review ever proposes "flattening the IIFE for readability", reject it — the flattening breaks the contract.
```

- [ ] **Step 4: Add §F.18 — `beforeConnect` must not throw**

Append:

```markdown
### F.18 `beforeConnect` must not throw — stash errors and let stompjs produce the ERROR frame

**Rule:** In `lib/ws/client.ts`, the `beforeConnect` callback wraps `ensureFreshAccessToken` in try/catch. On `RefreshFailedError`, store the message via `setState({status:"error", detail})` and return normally — do NOT throw from `beforeConnect`. Stompjs treats a thrown `beforeConnect` as a catastrophic failure and either deactivates the client entirely or loops infinitely depending on the version.

**Why:** letting stompjs proceed with no `Authorization` header causes the interceptor to reject the CONNECT frame with an `ERROR` frame, which the client handles gracefully via `onStompError` and our `ConnectionState` machine. Any path that throws from `beforeConnect` hides the error from UI and potentially deadlocks the client.

**How to apply:** when reviewing `beforeConnect` changes, verify that the catch block only stores state, never throws or calls `client.deactivate()`. The setState call path is safe; throw is not.
```

- [ ] **Step 5: Add §F.19 — stompjs `subscribe()` requires connected**

Append:

```markdown
### F.19 `@stomp/stompjs` `subscribe()` only works when `client.connected === true`

**Rule:** `client.subscribe(destination, callback)` throws if called before the client is connected. Our `lib/ws/client.ts` handles this by deferring the actual `client.subscribe()` call until `onConnect` fires, via an inline `subscribeToConnectionState` listener that unsubscribes itself after one fire.

**Why:** without the deferral, calling `useStompSubscription` during initial page load (before the WS handshake completes) throws a runtime error that crashes the React tree. The deferral is ~6 lines but load-bearing — do not "simplify" it away.

A known edge case lives here (spec §14.7): a rapid `onConnect → onWebSocketClose` sequence can leave the deferred listener waiting mid-cycle. The subscription is not lost — it attaches on the next successful connect — just delayed by one reconnect. Epic 04 may harden this with re-attach-on-every-transition + subscription dedup when auction-room subscriptions need robustness under flaky networks.

**How to apply:** any path that eagerly invokes `client.subscribe` must first check `client.connected`, and if false, defer via the state listener. This rule applies equally to Epic 04's auction-room subscription code.
```

- [ ] **Step 6: Update `README.md`**

Open `README.md` at the project root. Find the section that describes the frontend features (should mention the auth pages from Task 01-08) and the backend services. Add a one-liner under each:

In the frontend section, append near the existing auth pages list:
```markdown
- `/dev/ws-test` — development-only WebSocket verification harness (404s in production)
```

In the backend section, append near the existing auth endpoints:
```markdown
- **WebSocket**: STOMP over `/ws` with SockJS fallback; JWT-authenticated at the CONNECT frame via `JwtChannelInterceptor`. Dev-only broadcast endpoint at `POST /api/ws-test/broadcast`.
```

If the README doesn't have explicit "frontend pages" or "backend endpoints" subsections, find a reasonable equivalent location and add a short paragraph mentioning the WebSocket pipe and the dev harness page. The specific wording is less important than the README not being stale.

- [ ] **Step 7: Run the verify chain**

Run: `cd frontend && npm run verify` (verifies the dev harness doesn't introduce hex colors etc.)

Run: `cd backend && ./mvnw test`
Expected: PASS — nothing touched by this task affects compilation or tests.

- [ ] **Step 8: Commit**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-09
git add docs/implementation/FOOTGUNS.md README.md
git commit -m "docs: FOOTGUNS F.16-F.19 and README sweep for Task 01-09

Four new entries in the frontend FOOTGUNS section covering the
WebSocket-specific invariants:
- F.16: /ws/** must be permitAll at the HTTP layer; auth is at STOMP.
- F.17: ensureFreshAccessToken finally clause lives inside the IIFE.
- F.18: beforeConnect must not throw; stash errors in ConnectionState.
- F.19: stompjs subscribe() requires client.connected; deferral pattern.

README updated to mention the new /dev/ws-test harness page and the
JWT-authenticated STOMP pipe backend."
git push
```

---

## Phase G — Final Verification

**Phase goal:** Prove the full stack works end-to-end in a real browser, not just in tests. Backend + frontend + Postgres + Redis all running; navigate to `/dev/ws-test`, click through the four verification zones, observe messages arrive in real time, exercise the reconnect path.

---

### Task 13: End-to-End Manual Smoke Test + Full Verify Chain

**Why:** Tests prove units work; manual smoke proves the deployed system works. CLAUDE.md's "For UI or frontend changes, start the dev server and use the feature in a browser" rule makes this mandatory for any UI-adjacent work.

**Files:**
- No file changes. This task is pure verification.

---

- [ ] **Step 1: Start infrastructure containers**

Verify Postgres and Redis are running:

```bash
docker ps | grep -E "postgres|redis"
```

Expected: both containers listed. If missing, see memory `dev_containers.md` for the `docker run` commands.

- [ ] **Step 2: Run the full backend test suite**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-09/backend
./mvnw clean test
```

Expected: ALL tests pass. Specifically verify:
- `JwtChannelInterceptorTest` — 6 tests.
- `WsTestIntegrationTest` — 3 tests.
- Any pre-existing tests from previous tasks (Task 01-07 auth, Task 01-04 user) still pass.

- [ ] **Step 3: Run the full frontend test suite**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-09/frontend
npm test
```

Expected: ALL tests pass. Specifically:
- `src/lib/auth/refresh.test.ts` — 3 tests.
- `src/lib/api.401-interceptor.test.tsx` — 3 tests (unchanged canary).
- `src/lib/ws/client.test.ts` — 9 tests.
- `src/lib/ws/hooks.test.tsx` — 3 tests.
- Any pre-existing tests from Task 01-08 (auth hooks, forms, Header, etc.) still pass.

- [ ] **Step 4: Run frontend lint, verify, and build**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-09/frontend
npm run lint
npm run verify
npm run build
```

Expected: ALL pass. Any failure is a hard stop — do not proceed to manual smoke until the verify chain is green.

- [ ] **Step 5: Start the backend**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-09/backend
./mvnw spring-boot:run
```

Wait for `Started BackendApplication in X.Xs` in the logs. Expected: no exceptions. Specifically watch for:
- `WebSocketConfig` bean initialization (look for `Broker` or `stomp` in the startup logs at DEBUG level, if enabled).
- `WsTestController` registered — should see `Mapped "/api/ws-test/broadcast"` in the mappings output.

- [ ] **Step 6: Start the frontend dev server (in another terminal)**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-09/frontend
npm run dev
```

Wait for `Ready in X.Xs`. Expected: dev server listens on `http://localhost:3000` with no compile errors.

- [ ] **Step 7: Manual smoke test — register and sign in**

1. Open `http://localhost:3000/register` in a browser.
2. Register a new user (any valid email + password meeting the validation rules).
3. After registration, verify the Header shows the authenticated state (user dropdown).

- [ ] **Step 8: Manual smoke test — /dev/ws-test page**

1. Navigate to `http://localhost:3000/dev/ws-test`.
2. Expected: the harness renders. Connection badge cycles through "Connecting..." → "Connected".
3. **Zone 1 (connection status):** badge shows green "Connected".
4. **Zone 3 (send broadcast):** type "hello" in the input, click "Send". Expected:
   - The input clears.
   - A new message appears in Zone 4 (received log) within ~100ms with `message: "hello"`, `senderId: <your user id>`, and a timestamp.
5. Send a few more messages to confirm the log retains up to 50 and prepends new ones.

- [ ] **Step 9: Manual smoke test — reconnection**

1. Still on the `/dev/ws-test` page, click **Force Disconnect**.
2. Expected: connection badge transitions to "Disconnected".
3. Click **Force Reconnect**.
4. Expected: badge transitions "Connecting..." → "Connected" within a few seconds.
5. Send another test message. Verify it arrives — proves the reconnected session works.

- [ ] **Step 10: Manual smoke test — backend restart reconnect**

1. In the backend terminal, press Ctrl+C to stop Spring Boot.
2. In the browser, observe the connection badge transition to "Reconnecting..." (stompjs auto-reconnect kicks in every 5 seconds).
3. Restart the backend: `./mvnw spring-boot:run` in the backend terminal.
4. Wait for startup.
5. Expected: the browser's connection badge transitions back to "Connected" automatically within ~5-10 seconds without any user interaction. This proves the `beforeConnect` refresh flow works across a real reconnect cycle.
6. Send one final test message to confirm post-reconnect subscriptions still work.

- [ ] **Step 11: Manual smoke test — production build 404 verification**

1. Stop the dev server (Ctrl+C).
2. Build the production bundle: `cd frontend && npm run build`.
3. Start the production server: `npm run start`.
4. Navigate to `http://localhost:3000/dev/ws-test`.
5. Expected: **404 Not Found**. The `NODE_ENV === "production"` guard in `page.tsx` ran `notFound()`.
6. Navigate to `http://localhost:3000/` (or another valid route) — expected: renders normally. Proves only `/dev/**` is guarded, not the whole app.
7. Stop the production server.

- [ ] **Step 12: Stop all processes**

Stop the backend (Ctrl+C) and the frontend production server (Ctrl+C). Leave Postgres and Redis running.

- [ ] **Step 13: Final commit check**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-09
git status
```

Expected: clean. No uncommitted changes. All Task 01-09 commits are on `task/01-09-websocket-stomp` and pushed to origin.

```bash
git log --oneline origin/dev..HEAD
```

Expected: one commit per task (13 commits: spec, refresh extract, api delegate, interceptor, ws config, wstest controller, ws integration test, deps, types, client, hooks, harness, footguns+readme). Plus the spec commit from the brainstorm phase.

If there are no uncommitted changes and the test suites are all green, **Task 01-09 is complete**. The next step is opening a pull request against `dev`:

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-09
gh pr create --base dev --title "feat(ws): Task 01-09 — WebSocket STOMP infrastructure" --body "$(cat <<'EOF'
## Summary

Ships the JWT-authenticated STOMP over WebSocket pipe between the Next.js frontend and Spring Boot backend, with an end-to-end verification harness. Infrastructure only — no user-facing auction features (those are Epic 04).

**Backend:**
- `WebSocketConfig` — simple in-memory broker on /topic, /app application prefix, SockJS endpoint at /ws.
- `JwtChannelInterceptor` — validates the STOMP CONNECT frame's Authorization header via the existing JwtService and attaches a StompAuthenticationToken principal.
- `SecurityConfig` — /ws/** permitAll above /api/** (HTTP-layer auth is impossible for WebSocket upgrades).
- `WsTestController` — dev/test profile-gated REST endpoint that broadcasts to /topic/ws-test.

**Frontend:**
- `lib/auth/refresh.ts` — extracted stampede guard. Shared by HTTP 401 interceptor and STOMP beforeConnect hook.
- `lib/api.ts` — delegates 401 handling to ensureFreshAccessToken. HTTP 401 canary still green.
- `lib/ws/client.ts` — singleton @stomp/stompjs client with reference counting, 5s disconnect grace, observable ConnectionState.
- `lib/ws/hooks.ts` — useConnectionState + useStompSubscription React wrappers.
- `/dev/ws-test` — four-zone verification harness; 404s in production.

**Tests:**
- JwtChannelInterceptorTest — 6 unit tests
- WsTestIntegrationTest — 3 end-to-end tests with real STOMP client
- refresh.test.ts — 3 stampede canary tests
- client.test.ts — 9 unit tests
- hooks.test.tsx — 3 hook tests
- api.401-interceptor.test.tsx — unchanged, still green

**FOOTGUNS added:** §F.16 (WebSocket permit rule), §F.17 (finally-in-IIFE), §F.18 (beforeConnect must not throw), §F.19 (subscribe requires connected).

## Test plan
- [ ] Backend ./mvnw test all green
- [ ] Frontend npm test all green (all existing tests + new tests)
- [ ] Frontend npm run lint && npm run verify && npm run build green
- [ ] Manual smoke: register → /dev/ws-test → connected → send message → received
- [ ] Manual smoke: Force Disconnect → Force Reconnect → still works
- [ ] Manual smoke: backend restart → frontend auto-reconnects
- [ ] Manual smoke: production build → /dev/ws-test returns 404

Spec: docs/superpowers/specs/2026-04-12-task-01-09-websocket-stomp-design.md
Plan: docs/superpowers/plans/2026-04-12-task-01-09-websocket-stomp.md
EOF
)"
```

---

## Self-Review Notes

This section is the plan author's sanity check before handoff. Remove before final commit if policy requires.

**1. Spec coverage check:**

| Spec section | Implemented by |
|--------------|----------------|
| §5.1 WebSocketConfig | Task 4 |
| §5.2 JwtChannelInterceptor + StompAuthenticationToken | Task 3 |
| §5.3 SecurityConfig /ws/** permit | Task 4 |
| §5.4 WsTestController + DTO | Task 5 |
| §6.1 Dependencies | Task 7 |
| §6.2 lib/auth/refresh.ts | Task 1 |
| §6.3 lib/api.ts delegation | Task 2 |
| §6.4 lib/ws/client.ts | Task 9 |
| §6.5 lib/ws/types.ts | Task 8 |
| §6.6 lib/ws/hooks.ts | Task 10 |
| §6.7 /dev/ws-test page + harness | Task 11 |
| §10.3 .env.local.example | Task 11 |
| §10.4 providers.tsx update | NOT NEEDED — Task 2 keeps `configureApiClient` as a passthrough, so providers.tsx stays byte-exact |
| §11.1 JwtChannelInterceptorTest | Task 3 |
| §11.2 WsTestIntegrationTest | Task 6 |
| §11.3 client.test.ts | Task 9 |
| §11.4 refresh.test.ts canary | Task 1 |
| §11.5 hooks.test.tsx | Task 10 |
| §13 FOOTGUNS §F.16-§F.19 | Task 12 |

All spec sections mapped.

**2. Placeholder scan:** every step has either exact code, exact commands, or a specific enough instruction (with expected output) for the engineer to execute. No TBDs, no "handle errors appropriately", no "write tests for the above".

**3. Type consistency:** cross-checked the following identifiers across tasks:
- `ensureFreshAccessToken` — defined Task 1, consumed Task 2 (api.ts), Task 9 (ws/client.ts).
- `configureRefresh` — defined Task 1, consumed Task 2 (via `configureApiClient` passthrough).
- `RefreshFailedError` — defined Task 1, caught Task 2 and Task 9.
- `AuthPrincipal` (existing) — read by Task 3, Task 5.
- `StompAuthenticationToken` — defined Task 3, used inside the interceptor same task.
- `ConnectionState` — defined Task 8, consumed Tasks 9, 10, 11.
- `Unsubscribe` — defined Task 8, consumed Task 9.
- `useConnectionState`, `useStompSubscription` — defined Task 10, consumed Task 11.
- `DISCONNECT_GRACE_MS` — defined Task 9, referenced only inside client.ts.
- `subscribe<T>`, `subscribeToConnectionState`, `getConnectionState`, `__devForceDisconnect`, `__devForceReconnect`, `__resetWsClientForTests` — all defined Task 9, consumed Tasks 10, 11, and Task 9's own tests.

No drift. Every identifier used in a later task is defined in an earlier task (or is pre-existing in the codebase).
