# Task 01-09: WebSocket STOMP Configuration — Design Spec

**Task brief:** `docs/implementation/epic-01/task-09-websocket-setup.md`
**Date:** 2026-04-12
**Base branch:** `dev`
**Author:** brainstorming skill session

---

## 1. Goal

Ship the WebSocket infrastructure — backend STOMP broker with JWT-authenticated handshake, frontend singleton connection manager, and an end-to-end verification harness — so that Epic 04 can immediately build real-time auction rooms on top of it.

This task delivers no user-facing auction feature. Its deliverable is a working, authenticated, reconnectable pipe between browser and backend, plus the tests that prove it stays working.

## 2. Alignment With The Brief

The brief (`epic-01/task-09-websocket-setup.md`) is well-scoped and directly implementable. Two interpretation notes:

- **"Ensure WebSocket handshake works with JWT authentication (validate token on connect)"** — we interpret "handshake" as the STOMP `CONNECT` frame, not the raw HTTP WebSocket upgrade. The upgrade itself is permitted unauthenticated; authentication happens inside the first STOMP frame. See §6.1 for why.
- **"Test endpoint or scheduled message"** — we ship the test endpoint, not the scheduler. Scheduled background chatter pollutes logs and makes "did my message arrive?" ambiguous. See §7.1.

No other brief corrections needed.

## 3. Architecture Overview

```
Browser                                          Backend (Spring Boot 4)
┌─────────────────────────┐                     ┌────────────────────────────┐
│ lib/ws/client.ts        │                     │ WebSocketConfig            │
│ (singleton, ref-counted)│                     │  - /ws SockJS endpoint     │
│                         │ HTTP upgrade        │  - /topic broker           │
│  ┌──────────────────┐   │ ───────────────────▶│  - /app app prefix         │
│  │ @stomp/stompjs   │   │                     │                            │
│  │ Client           │   │ STOMP CONNECT       │                            │
│  │                  │   │  Authorization:     │ JwtChannelInterceptor      │
│  │  beforeConnect:  │   │  Bearer <jwt>       │   preSend on CONNECT:      │
│  │  ensureFresh-    │   │ ───────────────────▶│    - read Authorization    │
│  │  AccessToken()   │   │                     │    - jwtService.parse      │
│  └──────────────────┘   │                     │    - accessor.setUser()    │
│                         │ CONNECTED / ERROR   │   - throw on invalid       │
│                         │◀────────────────────│                            │
│                         │                     │                            │
│                         │ SUBSCRIBE           │                            │
│                         │ /topic/ws-test      │ SimpMessagingTemplate      │
│                         │────────────────────▶│  /topic/ws-test            │
│                         │                     │                            │
│                         │ MESSAGE             │                            │
│                         │◀────────────────────│ WsTestController           │
│                         │                     │  POST /api/ws-test/        │
│                         │                     │       broadcast            │
└─────────────────────────┘                     └────────────────────────────┘
        │                                                    │
        │ /api/auth/refresh (shared stampede guard)          │
        │ ensureFreshAccessToken() — lib/auth/refresh.ts     │
        │────────────────────────────────────────────────────▶
```

**Key design points captured in the Q&A:**

1. **STOMP-frame authentication** (Q1-A). Raw WS upgrade is unauthenticated; JWT lives in the `CONNECT` frame's `Authorization` header. ChannelInterceptor validates it via existing `JwtService.parseAccessToken`.
2. **`beforeConnect` always refreshes** (Q2-A). Every connect/reconnect calls `ensureFreshAccessToken()` before handing stompjs the token. Cheap (~100ms), trivially correct.
3. **Extracted stampede guard** (Q2-A follow-up). The refresh-dedup logic moves from `lib/api.ts` into `lib/auth/refresh.ts`. HTTP 401 interceptor and STOMP `beforeConnect` share one module, one in-flight promise.
4. **CONNECT-only auth** (Q3c). Subsequent `SUBSCRIBE`/`SEND` frames reuse the `Principal` set on the session by the interceptor.
5. **`tv` freshness not enforced on WS** (Q3d). Matches the HTTP filter — 15-minute token lifetime is the natural staleness window. Epic 04 will add Redis pub/sub eviction if bids ever flow through STOMP.
6. **HTTP-triggered broadcast** (Q4a). `POST /api/ws-test/broadcast` → `/topic/ws-test`. No scheduler.
7. **`@Profile({"dev","test"})` gating** (Q4b). Test controller is bean-graph-absent in prod.
8. **Reference-counted client with 5s grace period** (Q5b). `DISCONNECT_GRACE_MS = 5_000` named constant.
9. **Observable ConnectionState** (Q6a). Module-level state + `subscribeToConnectionState()` + `useConnectionState()` hook.
10. **Four-test suite** (Q7). Interceptor unit tests, STOMP integration smoke, frontend client unit tests, and a dedicated `ensureFreshAccessToken` canary.

## 4. Module Inventory

### 4.1 Backend

```
backend/src/main/java/com/slparcelauctions/backend/
├── config/
│   ├── SecurityConfig.java                          (MODIFY: permit /ws/**)
│   └── WebSocketConfig.java                         (CREATE)
├── auth/
│   └── JwtChannelInterceptor.java                   (CREATE)
└── wstest/                                          (CREATE — new package, dev/test profile only)
    ├── WsTestController.java                        (CREATE)
    └── dto/
        └── WsTestBroadcastRequest.java              (CREATE — record DTO)

backend/src/test/java/com/slparcelauctions/backend/
├── auth/
│   └── JwtChannelInterceptorTest.java               (CREATE — unit)
└── wstest/
    └── WsTestIntegrationTest.java                   (CREATE — @SpringBootTest, real STOMP client)
```

### 4.2 Frontend

```
frontend/src/
├── lib/
│   ├── auth/
│   │   ├── refresh.ts                               (CREATE — extracted stampede guard)
│   │   └── refresh.test.ts                          (CREATE — canary: stampede dedup, failure reset)
│   ├── api.ts                                       (MODIFY: consume lib/auth/refresh)
│   └── ws/                                          (CREATE — new directory)
│       ├── client.ts                                (CREATE — singleton Client, ref counting, state machine)
│       ├── client.test.ts                           (CREATE — unit tests w/ mocked @stomp/stompjs)
│       ├── hooks.ts                                 (CREATE — useStompSubscription, useConnectionState)
│       ├── hooks.test.tsx                           (CREATE — hook tests)
│       └── types.ts                                 (CREATE — ConnectionState, Message<T>)
└── app/
    └── dev/
        └── ws-test/
            └── page.tsx                             (CREATE — NODE_ENV guard + four-zone harness)
```

## 5. Backend Detailed Design

### 5.1 `WebSocketConfig`

```java
package com.slparcelauctions.backend.config;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtChannelInterceptor jwtChannelInterceptor;

    @Value("${cors.allowed-origin:http://localhost:3000}")
    private String allowedOrigin;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Simple in-memory broker for /topic (broadcast) destinations.
        // Epic 04 may swap to a relayed broker (RabbitMQ STOMP plugin) when
        // we need cross-instance fanout; for now single-instance in-memory is
        // sufficient.
        registry.enableSimpleBroker("/topic");
        // Prefix for messages bound for @MessageMapping handlers. Unused in
        // 01-09 — the test controller uses REST + SimpMessagingTemplate —
        // but set here so Epic 04 can @MessageMapping("/bid") without
        // reconfiguring.
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

**Why `.setAllowedOrigins()` on the endpoint:** Spring's STOMP endpoint CORS is configured separately from the main `HttpSecurity` CORS source. Forgetting this produces a 403 on the SockJS handshake XHR with a confusing "CORS policy: No 'Access-Control-Allow-Origin' header is present" message even though `SecurityConfig` has CORS set.

**Why in-memory broker instead of a relayed broker:** YAGNI. Single-instance dev and the Phase 1 topology don't need relayed STOMP. Leaving a comment so the Epic 04 implementer doesn't need to rediscover.

### 5.2 `JwtChannelInterceptor`

```java
package com.slparcelauctions.backend.auth;

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
            // Only gate CONNECT frames. SUBSCRIBE/SEND/DISCONNECT etc. flow
            // through unchanged — the user is already attached to the session
            // via accessor.setUser() from the earlier CONNECT.
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

**The `StompAuthenticationToken`** is a small wrapper implementing `java.security.Principal`:

```java
package com.slparcelauctions.backend.auth;

public record StompAuthenticationToken(AuthPrincipal principal) implements Principal {
    @Override
    public String getName() {
        return String.valueOf(principal.userId());
    }
}
```

It exists so Spring's `SimpMessagingTemplate.convertAndSendToUser(principal.getName(), ...)` can find user-scoped destinations later. Epic 04 may use this for per-user notifications. For 01-09, it's just the Principal handle.

**Why throw `MessagingException`:** locked in Q3a-i. Spring's STOMP infrastructure catches this and sends an `ERROR` frame back to the client, then closes the session cleanly. The frontend's `onStompError` handler picks up the error and surfaces it.

**Why `getFirstNativeHeader`:** STOMP headers come through as a `MultiValueMap<String, String>`. `getFirstNativeHeader` is the idiomatic single-value read; `getHeader` reads Spring-internal headers, not STOMP client headers, and returns null for `Authorization`. Footgun candidate — see §11.1.

### 5.3 `SecurityConfig` modification

Add one matcher **above** `/api/**`:

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
    // Authentication happens at the STOMP CONNECT frame via JwtChannelInterceptor.
    // See FOOTGUNS §F.16 (to be added in this task).
    .requestMatchers("/ws/**").permitAll()
    .requestMatchers("/api/**").authenticated()
    .anyRequest().denyAll())
```

**CORS update:** the existing `corsConfigurationSource()` uses `setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"))`. SockJS's info endpoint issues OPTIONS preflights, which is already allowed. No CORS change needed beyond the endpoint-level `.setAllowedOrigins()` in `WebSocketConfig`.

### 5.4 `WsTestController` (dev/test profile only)

```java
package com.slparcelauctions.backend.wstest;

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
        // Wire-type note: principal.userId() is a Java Long. Jackson serializes
        // it as a JSON number, which lands in JavaScript as a plain `number`.
        // Safe because user IDs are Long but well under Number.MAX_SAFE_INTEGER
        // (2^53 - 1). The frontend harness types senderId as `number` to match.
        // If user IDs ever grow past 2^53 (not going to happen), switch to
        // serializing as a string and parse on the frontend.
        messagingTemplate.convertAndSend("/topic/ws-test",
            Map.of(
                "message", request.message(),
                "senderId", principal.userId(),
                "timestamp", Instant.now().toString()
            ));
    }
}
```

**DTO:**

```java
package com.slparcelauctions.backend.wstest.dto;

public record WsTestBroadcastRequest(
    @NotBlank @Size(max = 500) String message
) {}
```

**Why `@Profile({"dev", "test"})`:** the controller and its package are absent from the prod bean graph. No SecurityConfig matcher needed to block it in prod — there's nothing to block. `dev` covers `application-dev.yml` runs; `test` covers `@SpringBootTest` integration tests (see §11.2).

**Authentication requirement:** the broadcast endpoint is on `/api/ws-test/**`, which falls under the `/api/**` authenticated catch-all in `SecurityConfig`. Only authenticated users can trigger broadcasts, which is what we want — the test harness proves end-to-end auth, not open broadcast.

## 6. Frontend Detailed Design

### 6.1 Dependencies

Add to `frontend/package.json`:

```json
{
  "dependencies": {
    "@stomp/stompjs": "^7.1.1"
  }
}
```

**Why `@stomp/stompjs` v7:** industry-standard STOMP client for browsers. Version 7 dropped the legacy SockJS hard dependency and introduced explicit `webSocketFactory` config, which is what we want — we'll pass a `SockJS` factory explicitly.

Also add SockJS:

```json
{
  "dependencies": {
    "sockjs-client": "^1.6.1"
  },
  "devDependencies": {
    "@types/sockjs-client": "^1.5.4"
  }
}
```

**Why SockJS:** browser native WebSocket is 95%+ supported, but corporate proxies sometimes strip WS upgrades. SockJS falls back to XHR long-polling in that case. The backend config enables `.withSockJS()` regardless; the frontend matches.

### 6.2 `lib/auth/refresh.ts` — Extracted Stampede Guard

**New file** (extracted from `lib/api.ts`):

```typescript
/**
 * Shared refresh-token stampede guard. Both the HTTP 401 interceptor
 * (lib/api.ts) and the STOMP beforeConnect hook (lib/ws/client.ts) call
 * ensureFreshAccessToken() — a single in-flight promise dedupes concurrent
 * refresh attempts from either source.
 *
 * The finally clause that clears inFlightRefresh MUST live inside the IIFE,
 * not outside the Promise chain. See FOOTGUNS §F.4 and §F.17.
 */
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
 * Callers that need to handle redirect-to-login (HTTP 401 interceptor) do so
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

// Test-only reset (exported under a deliberately-ugly name so production code
// doesn't call it). Unit tests reset module state between cases.
export function __resetRefreshStateForTests(): void {
  inFlightRefresh = null;
  queryClientRef = null;
}
```

**Design notes:**
- **Returns the new access token** rather than `void`. The old `lib/api.ts` version didn't need to return anything because the retry read from `getAccessToken()` after the refresh ran. The new version returns the token so STOMP's `beforeConnect` can plug it directly into `connectHeaders.Authorization` without a second `getAccessToken()` call. Small ergonomic win.
- **`RefreshFailedError`** is a new named exception. The HTTP 401 interceptor catches it, clears session, redirects to `/login?next=...`. STOMP `beforeConnect` catches it and lets stompjs error out (`onStompError` eventually surfaces it via ConnectionState).
- **`configureRefresh(queryClient)`** replaces the existing `configureApiClient(queryClient)` role. `lib/api.ts` will delegate: `configureApiClient` is kept as a thin wrapper that calls `configureRefresh` for backwards compatibility with `app/providers.tsx`, OR `providers.tsx` is updated to call `configureRefresh` directly. We'll pick the simpler path in the plan — `providers.tsx` updates.
- **`__resetRefreshStateForTests`** — only consumed by `refresh.test.ts`. The double-underscore prefix signals don't-ship-this-in-consumer-code.

### 6.3 `lib/api.ts` — Consumes The Shared Guard

The existing `handleUnauthorized<T>` IIFE is deleted. In its place:

```typescript
import { ensureFreshAccessToken, RefreshFailedError } from "@/lib/auth/refresh";

async function handleUnauthorized<T>(path: string, options: RequestOptions): Promise<T> {
  try {
    await ensureFreshAccessToken();
  } catch (e) {
    if (e instanceof RefreshFailedError) {
      if (typeof window !== "undefined") {
        const next = encodeURIComponent(window.location.pathname + window.location.search);
        window.location.href = `/login?next=${next}`;
      }
      const problem: ProblemDetail = { status: 401, title: "Session expired" };
      throw new ApiError(problem);
    }
    throw e;
  }
  return request<T>(path, options, /* isRetry */ true);
}
```

The `inFlightRefresh` and `queryClientRef` module-level vars in `lib/api.ts` are removed. `configureApiClient(queryClient)` is either deleted or becomes a one-line passthrough to `configureRefresh(queryClient)` — the plan will pick one. The existing `api.401-interceptor.test.tsx` canary still passes because `ensureFreshAccessToken` implements the same contract the inlined IIFE did.

### 6.4 `lib/ws/client.ts` — Singleton Connection Manager

**Full file sketch** (exact code lives in the plan):

```typescript
"use client";

import { Client, type IMessage, type StompSubscription } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import { ensureFreshAccessToken, RefreshFailedError } from "@/lib/auth/refresh";
import type { ConnectionState, StompMessage, Unsubscribe } from "./types";

const WS_URL = process.env.NEXT_PUBLIC_WS_URL ?? "http://localhost:8080/ws";
const DISCONNECT_GRACE_MS = 5_000;

let client: Client | null = null;
let subscriberCount = 0;
let disconnectTimer: ReturnType<typeof setTimeout> | null = null;
let connectionState: ConnectionState = { status: "disconnected" };
const stateListeners = new Set<(state: ConnectionState) => void>();
let lastError: string | null = null;

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
        lastError = e instanceof RefreshFailedError
          ? "Session expired — please sign in again"
          : "Could not refresh access token";
        // Let stompjs continue — it will fail CONNECT with a clear ERROR frame
        // which we surface via onStompError.
      }
    },
    onConnect: () => {
      lastError = null;
      setState({ status: "connected" });
    },
    onWebSocketClose: () => {
      // If auto-reconnect is active (client.active === true), we're about to
      // retry. Otherwise we've been fully stopped.
      if (client?.active) {
        setState({ status: "reconnecting" });
      } else {
        setState({ status: "disconnected" });
      }
    },
    onStompError: (frame) => {
      lastError = frame.headers["message"] ?? "STOMP error";
      setState({ status: "error", detail: lastError });
    },
  });

  return client;
}

export function subscribe<T>(
  destination: string,
  onMessage: (payload: T) => void
): Unsubscribe {
  const c = getOrCreateClient();
  subscriberCount += 1;

  // Cancel any pending teardown — a new subscriber re-activates the connection.
  if (disconnectTimer) {
    clearTimeout(disconnectTimer);
    disconnectTimer = null;
  }

  if (!c.active) {
    c.activate();
  }

  // If the client isn't connected yet, stompjs queues the subscription until
  // onConnect. No manual deferral needed — the library handles it.
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
    // Defer until onConnect. Stompjs does not auto-subscribe pending callbacks
    // in all paths for v7, so we register an onConnect hook-of-hooks via
    // publish + subscribe on the connection state.
    //
    // KNOWN RACE (Epic 04 hardening followup, §14.7):
    //   If onConnect → onWebSocketClose fires in rapid succession (fast
    //   backend bounce), the listener may see "reconnecting" mid-cycle and
    //   keep waiting through the reconnect. On the next successful connect
    //   the listener fires and attach() runs — so the subscription is not
    //   lost, just delayed by one reconnect cycle. Acceptable for the 01-09
    //   dev harness (reconnects are manual or rare). Epic 04's auction-room
    //   subscriptions will see flaky networks and want a more robust
    //   re-attach strategy (e.g., re-attach on every connected transition
    //   with subscription dedup, instead of self-unsubscribing after one
    //   fire).
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

// Manual controls for the /dev/ws-test page's "Disconnect" / "Reconnect" buttons.
// Consumers should NOT use these in production code — they bypass the reference
// counting and can leave other subscribers stranded.
export function __devForceDisconnect(): void {
  if (client?.active) client.deactivate();
  setState({ status: "disconnected" });
}

export function __devForceReconnect(): void {
  const c = getOrCreateClient();
  if (!c.active) c.activate();
}

// Test-only reset.
export function __resetWsClientForTests(): void {
  if (client) client.deactivate();
  client = null;
  subscriberCount = 0;
  if (disconnectTimer) {
    clearTimeout(disconnectTimer);
    disconnectTimer = null;
  }
  stateListeners.clear();
  connectionState = { status: "disconnected" };
  lastError = null;
}
```

**Why the subscribe-before-connected deferral via `subscribeToConnectionState`:** the stompjs `Client.subscribe()` method only works when `client.connected === true`. Calling it before connect throws. Rather than maintaining our own pending-queue, we reuse the ConnectionState observer to fire the subscribe exactly when `onConnect` runs. Self-unsubscribes the state listener to avoid leak.

**`DISCONNECT_GRACE_MS = 5_000`** as a named constant per Q5b.

**Why `__devForce*` exports:** the test page needs manual disconnect/reconnect buttons to exercise reconnection without `kill -9`-ing the backend. Ugly names so Epic 04 implementers don't accidentally reach for them.

### 6.5 `lib/ws/types.ts`

```typescript
export type ConnectionState =
  | { status: "disconnected" }
  | { status: "connecting" }
  | { status: "connected" }
  | { status: "reconnecting" }
  | { status: "error"; detail: string };

export type Unsubscribe = () => void;

// Re-exported for consumers who want to type their payload.
export type StompMessage<T> = T;
```

Discriminated union — consumers `switch(state.status)` and TypeScript narrows. The `error` variant carries the detail string.

### 6.6 `lib/ws/hooks.ts`

```typescript
"use client";

import { useEffect, useRef, useState } from "react";
import { subscribe, subscribeToConnectionState, getConnectionState } from "./client";
import type { ConnectionState, Unsubscribe } from "./types";

export function useConnectionState(): ConnectionState {
  const [state, setState] = useState<ConnectionState>(() => getConnectionState());
  useEffect(() => subscribeToConnectionState(setState), []);
  return state;
}

export function useStompSubscription<T>(
  destination: string,
  onMessage: (payload: T) => void
): void {
  // Stable-callback guard via ref — avoids re-subscribing on every render when
  // consumers pass an inline arrow function. Classic React pattern.
  const callbackRef = useRef(onMessage);
  callbackRef.current = onMessage;

  useEffect(() => {
    const unsubscribe: Unsubscribe = subscribe<T>(destination, (payload) => {
      callbackRef.current(payload);
    });
    return unsubscribe;
  }, [destination]);
}
```

**Why `useRef` for the callback:** passing `onMessage` as a dependency to `useEffect` would re-subscribe every render. Storing the current callback in a ref and reading `callbackRef.current` inside the subscription means the subscription survives re-renders while always dispatching to the latest callback.

### 6.7 `app/dev/ws-test/page.tsx` — Four-Zone Test Harness

```tsx
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

The harness itself lives in `components/dev/WsTestHarness.tsx` (client component) so the page file stays thin:

```tsx
"use client";

import { useState } from "react";
import { useConnectionState, useStompSubscription } from "@/lib/ws/hooks";
import { __devForceDisconnect, __devForceReconnect } from "@/lib/ws/client";
import { api } from "@/lib/api";

// senderId is a Java Long on the backend, serialized as JSON number, which
// parses to a plain JS number here. See §5.4 for the wire-type explanation.
type WsTestMessage = { message: string; senderId: number; timestamp: string };

export function WsTestHarness() {
  const state = useConnectionState();
  const [messages, setMessages] = useState<WsTestMessage[]>([]);
  const [input, setInput] = useState("");
  const [sending, setSending] = useState(false);

  useStompSubscription<WsTestMessage>("/topic/ws-test", (payload) => {
    setMessages((prev) => [payload, ...prev].slice(0, 50));
  });

  const sendBroadcast = async () => {
    if (!input.trim()) return;
    setSending(true);
    try {
      await api.post("/api/ws-test/broadcast", { message: input });
      setInput("");
    } finally {
      setSending(false);
    }
  };

  return (
    <main className="mx-auto max-w-3xl p-8">
      <h1 className="text-2xl font-semibold text-on-surface">WebSocket Test Harness</h1>
      <p className="mt-2 text-on-surface-variant">
        Dev-only page for verifying the STOMP pipe. Will 404 in production builds.
      </p>

      {/* Zone 1: connection status */}
      <ConnectionBadge state={state} />

      {/* Zone 4: manual controls */}
      <div className="mt-4 flex gap-2">
        <button onClick={__devForceDisconnect} className="...">Force Disconnect</button>
        <button onClick={__devForceReconnect} className="...">Force Reconnect</button>
      </div>

      {/* Zone 2: broadcast form */}
      <form onSubmit={(e) => { e.preventDefault(); sendBroadcast(); }} className="mt-6">
        <label className="...">Send test message</label>
        <input value={input} onChange={(e) => setInput(e.target.value)} className="..." />
        <button type="submit" disabled={sending || !input.trim()}>Send</button>
      </form>

      {/* Zone 3: received messages log */}
      <section className="mt-8">
        <h2 className="text-lg font-medium text-on-surface">Received ({messages.length})</h2>
        <ol className="mt-2 space-y-2">
          {messages.map((m, i) => (
            <li key={`${m.timestamp}-${i}`} className="...">
              <span className="font-mono text-xs text-on-surface-variant">{m.timestamp}</span>
              <p className="text-on-surface">{m.message}</p>
              <span className="text-xs text-on-surface-variant">from userId {m.senderId}</span>
            </li>
          ))}
        </ol>
      </section>
    </main>
  );
}

function ConnectionBadge({ state }: { state: ConnectionState }) {
  // Renders a colored badge + label based on state.status.
  // "error" variant shows state.detail inline.
  // ...
}
```

**DESIGN.md conformance:** the harness is a dev tool, not a user-facing surface, so we relax the strict design-system requirements. It should still use the token-based Tailwind classes (no hex, no inline styles) to stay lint-clean. No `border-t` (No-Line Rule). Plain structure — we're debugging, not shipping.

## 7. STOMP CONNECT Authentication Flow

Sequence for a first-time connection:

```
Browser                          Backend
   │                                │
   │ HTTP POST /ws/info (SockJS)    │
   │───────────────────────────────▶│
   │                                │ Spring Security: /ws/** permitAll
   │◀───────────────────────────────│ (no auth check at HTTP layer)
   │                                │
   │ HTTP upgrade /ws/xxx/yyy       │
   │───────────────────────────────▶│
   │◀───────────────────────────────│ 101 Switching Protocols
   │                                │
   │                                │ --- WS open, client.beforeConnect fires ---
   │                                │
   │ beforeConnect:                 │
   │   ensureFreshAccessToken()     │
   │ ───────────────────────────────│
   │ POST /api/auth/refresh         │
   │ (Cookie: refreshToken=...)     │
   │───────────────────────────────▶│
   │◀───────────────────────────────│ 200 { accessToken, user }
   │                                │
   │ connectHeaders.Authorization   │
   │   = "Bearer " + token          │
   │                                │
   │ STOMP CONNECT                  │
   │ Authorization: Bearer eyJ...   │
   │───────────────────────────────▶│
   │                                │ JwtChannelInterceptor.preSend()
   │                                │  - read Authorization
   │                                │  - jwtService.parseAccessToken()
   │                                │  - accessor.setUser(principal)
   │                                │
   │◀───────────────────────────────│ STOMP CONNECTED
   │                                │
   │ SUBSCRIBE /topic/ws-test       │
   │───────────────────────────────▶│
   │                                │ JwtChannelInterceptor.preSend()
   │                                │  - not a CONNECT frame, pass through
   │◀───────────────────────────────│ RECEIPT (implicit)
```

**Failure paths:**
- **Refresh fails in `beforeConnect`:** `ensureFreshAccessToken` throws `RefreshFailedError`, we catch it and store `lastError`. stompjs proceeds without an `Authorization` header. CONNECT frame arrives at interceptor with no header → interceptor throws `MessagingException` → client sees `ERROR` frame → `onStompError` fires → `setState({status:"error", detail})` → harness shows error. User has already been redirected to `/login` by the HTTP interceptor's own `RefreshFailedError` catch IF an HTTP request happened in parallel. If WS was the only in-flight consumer, we rely on the `onStompError` surface for now — Epic 04 may add an auth-specific redirect from the WS layer.
- **Interceptor throws invalid-token:** same — CONNECT gets ERROR frame. `onStompError` sets state to error.

## 8. Reconnection Flow

Stompjs auto-reconnects on WebSocket close with `reconnectDelay: 5_000`. Every reconnect attempt re-runs `beforeConnect`, which re-runs `ensureFreshAccessToken()`, which serves a fresh token. Fresh token → fresh CONNECT auth → success.

```
t=0     WS connected, happy path
t=T     backend restart, WS closes
t=T     onWebSocketClose fires, state → "reconnecting"
t=T+5s  stompjs retry #1
        beforeConnect → ensureFreshAccessToken → /api/auth/refresh → new token
        CONNECT with new token → CONNECTED
        onConnect fires, state → "connected"
        stompjs re-sends pending subscriptions
```

If the backend stays down beyond the access token lifetime (15 min), the refresh cookie still works (7 days), so the next successful reconnect gets a new access token and resumes cleanly. The only unrecoverable state is refresh-cookie expiry or user logout-all — both surface as a failed refresh → `RefreshFailedError` → harness error state.

## 9. Token Refresh Model

**Two sources of refresh:**
1. HTTP 401 interceptor (existing, unchanged behavior, now delegates to `ensureFreshAccessToken`).
2. STOMP `beforeConnect` (new).

**Single in-flight promise** guarded by `inFlightRefresh` in `lib/auth/refresh.ts`. If HTTP 401 and STOMP reconnect both hit at the same moment, they await the same promise. The `finally` clause that nulls `inFlightRefresh` lives inside the IIFE (not outside the return) — this is the FOOTGUNS §F.4 invariant, restated in §F.17 for the extracted module.

**Invariants:**
- `ensureFreshAccessToken()` called N times concurrently ⇒ exactly one `POST /api/auth/refresh`.
- On success, `setAccessToken(newToken)` fires exactly once, and the resolved promise value is the new token.
- On failure, `setAccessToken(null)` + `setQueryData(null)` fire exactly once, and `RefreshFailedError` is thrown. `inFlightRefresh` is nulled so the next caller can retry fresh (not stuck on the rejected promise forever).

## 10. Configuration

### 10.1 Backend `application-dev.yml`

No changes. The `dev` profile activates `WsTestController` automatically via `@Profile`.

### 10.2 Backend `application.yml` (shared)

No changes. `cors.allowed-origin` already set.

### 10.3 Frontend environment

Add one env var to `frontend/.env.local.example` (creating it if missing):

```
NEXT_PUBLIC_API_URL=http://localhost:8080
NEXT_PUBLIC_WS_URL=http://localhost:8080/ws
```

**Why a separate `NEXT_PUBLIC_WS_URL`:** in production the WS endpoint may live behind a different origin or subpath (e.g., `wss://realtime.slpa.example.com/ws` while API is at `https://api.slpa.example.com`). Splitting the vars now is cheap and avoids a rename later.

### 10.4 `app/providers.tsx` update

Replace the current `configureApiClient(queryClient)` call with `configureRefresh(queryClient)` (or keep the old name as a passthrough — the plan will pick one). No other provider changes needed.

## 11. Testing Strategy

Four test suites corresponding to Q7a-d.

### 11.1 `JwtChannelInterceptorTest` — Backend Unit

**Location:** `backend/src/test/java/com/slparcelauctions/backend/auth/JwtChannelInterceptorTest.java`

**Setup:**
- Mockito mock for `JwtService`.
- Direct interceptor instantiation — no Spring context.

**Test cases:**

1. `preSend_nonConnectFrame_passesThrough` — SEND frame with no `Authorization` returns the message unchanged, interceptor never touches the accessor user.
2. `preSend_connectFrame_missingAuthHeader_throwsMessagingException` — verify exception message contains "Missing or invalid Authorization header".
3. `preSend_connectFrame_malformedBearerHeader_throwsMessagingException` — header is `"NotBearer foo"`; same exception.
4. `preSend_connectFrame_validToken_attachesPrincipal` — mock `jwtService.parseAccessToken(token)` returns an `AuthPrincipal(42L, "test@example.com", 1L)`. Assert `accessor.getUser()` is a `StompAuthenticationToken` whose `.getName()` is `"42"`.
5. `preSend_connectFrame_expiredToken_throwsMessagingException` — mock throws `TokenExpiredException`, assert `MessagingException` propagated.
6. `preSend_connectFrame_invalidToken_throwsMessagingException` — mock throws `TokenInvalidException`.

**Why it matters:** these six cases pin every branch of the interceptor logic. A future refactor breaking any branch fails here before it ever reaches an integration test.

### 11.2 `WsTestIntegrationTest` — Backend End-to-End

**Location:** `backend/src/test/java/com/slparcelauctions/backend/wstest/WsTestIntegrationTest.java`

**Setup:**
- `@SpringBootTest(webEnvironment = RANDOM_PORT)`
- `@ActiveProfiles("test")` — activates `WsTestController`.
- `@Autowired` real `JwtService`, `TestRestTemplate`.
- Use Spring's `WebSocketStompClient` with `SockJsClient`.

**Test cases:**

1. `stompConnectWithValidToken_receivesBroadcast` — (a) issue a JWT via `jwtService.issueAccessToken(principal)`, (b) connect via `WebSocketStompClient` with `connectHeaders.Authorization: Bearer {jwt}`, (c) subscribe to `/topic/ws-test` with a `BlockingQueue<WsTestMessage>` handler, (d) POST to `/api/ws-test/broadcast` with `Authorization: Bearer {jwt}`, (e) `queue.poll(2, SECONDS)` returns the broadcast message with matching content. **This is the single test that proves the entire pipe works end-to-end.**
2. `stompConnectWithoutToken_isRejected` — connect with no `Authorization` in `connectHeaders`. Assert the session transitions to error / closed within a timeout.
3. `stompConnectWithInvalidToken_isRejected` — connect with `Authorization: Bearer not-a-real-jwt`. Same assertion.

**Why it matters:** this is the smoke test for the `SecurityConfig` permitlist, the CORS config, the interceptor wiring, the broker registry, and the test profile gating — all at once. If any one of those is wrong, at least one of the three tests fails with a loud, diagnosable message.

**Why `@ActiveProfiles("test")`:** `WsTestController` is `@Profile({"dev", "test"})`. The integration test needs the bean in the graph.

### 11.3 `lib/ws/client.test.ts` — Frontend Client Unit

**Setup:**
- `vi.mock("@stomp/stompjs")` returns a stub `Client` class whose methods (`activate`, `deactivate`, `subscribe`, etc.) are `vi.fn()`. The mock exposes the captured `beforeConnect`/`onConnect`/`onWebSocketClose`/`onStompError` callbacks so tests can trigger them manually.
- `vi.mock("@/lib/auth/refresh")` returns a mocked `ensureFreshAccessToken` returning a Promise we resolve manually per-test.
- `__resetWsClientForTests()` runs in `beforeEach`.

**Test cases:**

1. `firstSubscribe_activatesClient` — call `subscribe("/topic/foo", cb)`. Assert `client.activate()` called once.
2. `secondSubscribe_reusesClient` — two subscribes. Assert `activate()` called exactly once.
3. `lastUnsubscribe_schedulesDisconnectWithGrace` — subscribe then unsubscribe. Assert `deactivate()` NOT called synchronously. Use `vi.useFakeTimers()` + `vi.advanceTimersByTime(5_000)`. Assert `deactivate()` called.
4. `resubscribeWithinGrace_cancelsTeardown` — subscribe, unsubscribe, subscribe again within 4 seconds. Advance 6 seconds. Assert `deactivate()` never called.
5. `beforeConnect_callsEnsureFreshAccessToken` — trigger the captured `beforeConnect`. Assert `ensureFreshAccessToken` called and `client.connectHeaders.Authorization` set to `"Bearer <returned-token>"`.
6. `onConnect_transitionsStateToConnected` — subscribe to state via `subscribeToConnectionState`, trigger captured `onConnect`. Assert listener fired with `{status:"connected"}`.
7. `onWebSocketClose_whileActive_transitionsToReconnecting` — simulate `client.active === true`, trigger `onWebSocketClose`. Assert state is `"reconnecting"`.
8. `onStompError_transitionsToErrorWithDetail` — trigger `onStompError` with a frame carrying a `message` header. Assert state is `{status:"error", detail:"..."}`.
9. `subscribe_beforeConnected_defersUntilOnConnect` — mock `client.connected === false` initially. Call `subscribe(...)`. Assert `client.subscribe` NOT called yet. Trigger `onConnect`. Assert `client.subscribe` called with the right destination.

**Budget:** 9 tests. Scoped to the module's public API and state transitions. Does not test stompjs itself.

### 11.4 `lib/auth/refresh.test.ts` — Stampede Canary

**Setup:**
- `vi.mock("global.fetch")` with a controllable response.
- Mock `setAccessToken` via module mock on `@/lib/auth/session`.
- `__resetRefreshStateForTests()` in `beforeEach`.

**Test cases (three — locked as Q7d-i):**

1. `singleCall_hitsBackendOnce_returnsToken` — mock fetch → 200 with `{ accessToken: "abc", user: {...} }`. Call `ensureFreshAccessToken()`. Assert fetch called once, returned value is `"abc"`, `setAccessToken("abc")` called.
2. `threeConcurrentCalls_hitBackendOnce_allResolveSameToken` — fire three `ensureFreshAccessToken()` in parallel (`Promise.all`). Assert fetch called exactly once, all three resolve with the same token string.
3. `failedRefresh_clearsInFlightAndRejects_nextCallRetries` — first fetch returns 401, assert `RefreshFailedError` thrown AND `setAccessToken(null)` called. Second call (after first rejected) — mock fetch to succeed — assert fetch called a SECOND time and returns the new token. This proves the `inFlightRefresh = null` finally runs on the reject path.

**Why this canary is non-negotiable:** the extracted module is the only thing standing between "one refresh per auth failure" and "every hook independently slamming /api/auth/refresh", which would trip backend rate-limiting and desync the access token. Three tests, maybe 80 lines, catch every meaningful regression.

### 11.5 `lib/ws/hooks.test.tsx` — Hook Tests

**Budget:** 3 tests.
1. `useConnectionState_rendersCurrentState_reactsToUpdates` — render a test component using the hook, call `setState(...)` on the underlying module via a spy, assert re-render.
2. `useStompSubscription_subscribesOnMount_unsubscribesOnUnmount` — use RTL `render` + `unmount`. Assert subscribe returned closure was called on unmount.
3. `useStompSubscription_stableCallbackAcrossRenders_doesNotResubscribe` — re-render with a new inline callback. Assert `subscribe` called exactly once.

## 12. File Inventory

### Create

**Backend:**
- `backend/src/main/java/com/slparcelauctions/backend/config/WebSocketConfig.java`
- `backend/src/main/java/com/slparcelauctions/backend/auth/JwtChannelInterceptor.java`
- `backend/src/main/java/com/slparcelauctions/backend/auth/StompAuthenticationToken.java`
- `backend/src/main/java/com/slparcelauctions/backend/wstest/WsTestController.java`
- `backend/src/main/java/com/slparcelauctions/backend/wstest/dto/WsTestBroadcastRequest.java`
- `backend/src/test/java/com/slparcelauctions/backend/auth/JwtChannelInterceptorTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/wstest/WsTestIntegrationTest.java`

**Frontend:**
- `frontend/src/lib/auth/refresh.ts`
- `frontend/src/lib/auth/refresh.test.ts`
- `frontend/src/lib/ws/client.ts`
- `frontend/src/lib/ws/client.test.ts`
- `frontend/src/lib/ws/hooks.ts`
- `frontend/src/lib/ws/hooks.test.tsx`
- `frontend/src/lib/ws/types.ts`
- `frontend/src/app/dev/ws-test/page.tsx`
- `frontend/src/components/dev/WsTestHarness.tsx`
- `frontend/.env.local.example` (if missing)

**Docs:**
- `docs/implementation/FOOTGUNS.md` — add §F.16, §F.17, §F.18, §F.19 (see §13 below).

### Modify

- `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java` — add `/ws/**` permit rule above `/api/**`.
- `frontend/src/lib/api.ts` — delete `inFlightRefresh`, `queryClientRef`, `handleUnauthorized` IIFE body. Delegate to `ensureFreshAccessToken` from `@/lib/auth/refresh`.
- `frontend/src/app/providers.tsx` — replace `configureApiClient` call with `configureRefresh` (or keep name if plan chooses passthrough).
- `frontend/package.json` — add `@stomp/stompjs`, `sockjs-client`, `@types/sockjs-client`.
- `frontend/src/lib/api.401-interceptor.test.tsx` — verify it still passes against the delegated implementation. Should require zero production-code changes in the test file; the contract (`handleUnauthorized` catches refresh failure → redirect) is preserved.
- `README.md` — document `/ws` endpoint and the dev test harness page per the auto-memory rule about per-task README sweeps.

### Delete

Nothing.

## 13. FOOTGUNS Additions

Four entries added to the frontend section at the end of the `§F` block. Numbers continue from the existing §F.15.

### §F.16 WebSocket handshake is permitted at HTTP, authenticated at STOMP

**Rule:** `SecurityConfig` must list `/ws/**` as `.permitAll()` above the `/api/**` authenticated catch-all. The HTTP-layer WebSocket upgrade does not carry an `Authorization` header from the browser, so gating it at the HTTP layer is impossible. Authentication happens in `JwtChannelInterceptor.preSend()` on the first STOMP `CONNECT` frame, before the session is usable. Do not "fix" the permit rule by adding `.authenticated()` — every WebSocket connection will fail with a bewildering 401 before the STOMP layer even sees the frame.

**Why:** matcher order is first-match-wins (§B.5). `/ws/**` must appear above `/api/**`. Moving it down silently opens the WebSocket to any URL under `/ws/*` while keeping the behavior correct — this is not a real footgun until `/api/**` gets restructured, but the comment at the matcher codifies the invariant so the next person doesn't need to rediscover it.

### §F.17 `ensureFreshAccessToken` stampede guard — `finally` inside the IIFE

**Rule:** In `lib/auth/refresh.ts`, the `inFlightRefresh = null` cleanup MUST live inside the IIFE's `try { ... } finally { inFlightRefresh = null; }`, not after the outer promise chain. The shared promise between HTTP 401 interceptor and STOMP `beforeConnect` depends on this: if the cleanup runs outside the IIFE, every awaiter nulls the ref as they resolve, and a concurrent refresh kicked off during the resolution window sees `null` and fires a second `/api/auth/refresh`, defeating the stampede guard.

**Why:** this is §F.4 restated for the extracted module. The HTTP 401 canary (`api.401-interceptor.test.tsx`) and the new `refresh.test.ts` canary both pin this behavior. If either test starts failing with "fetch called twice instead of once", the `finally` clause has been moved.

**How to apply:** when touching `lib/auth/refresh.ts`, diff the `finally` placement. If code review ever proposes "flattening the IIFE for readability", reject it.

### §F.18 `beforeConnect` must not throw — stash errors and let stompjs produce the ERROR frame

**Rule:** In `lib/ws/client.ts`, the `beforeConnect` callback wraps `ensureFreshAccessToken` in try/catch. On `RefreshFailedError`, store the message in a module-level `lastError` ref and return normally — do NOT throw from `beforeConnect`. Stompjs treats a thrown `beforeConnect` as a catastrophic failure and either deactivates the client entirely or loops infinitely depending on version. Letting stompjs proceed with no `Authorization` header causes the interceptor to reject the CONNECT frame with an `ERROR` frame, which the client handles gracefully via `onStompError` and our `ConnectionState` machine.

**Why:** the error path is "CONNECT frame arrives with no auth header → interceptor throws `MessagingException` → stompjs surfaces ERROR frame → `onStompError` fires → state becomes error(detail)". Any other path (throwing from `beforeConnect`, calling `deactivate` inside `beforeConnect`, etc.) breaks this contract and either deadlocks the client or hides the error from UI.

**How to apply:** when reviewing `beforeConnect` changes, verify that the catch block only stores state, never throws or calls `client.deactivate()`.

### §F.19 `@stomp/stompjs` `subscribe()` only works when `client.connected === true`

**Rule:** `client.subscribe(destination, callback)` throws if called before the client is connected. Our `lib/ws/client.ts` handles this by deferring the actual `client.subscribe()` call until `onConnect` fires, via an inline `subscribeToConnectionState` listener that unsubscribes itself after one fire.

**Why:** without the deferral, calling `useStompSubscription` during initial page load (before the WS handshake completes) throws a runtime error in the `subscribe()` consumer, which crashes the React tree. The deferral is ~6 lines but load-bearing — do not "simplify" it away.

**How to apply:** any path that eagerly invokes `client.subscribe` must first check `client.connected`, and if false, defer via the state listener. This rule applies equally to Epic 04's auction-room subscription code.

## 14. Out Of Scope (Epic 04 Followups)

1. **Per-auction topic subscriptions** (`/topic/auction/{id}`). Naming convention is declared here; the actual subscriptions and `AuctionMessageHandler` live in Epic 04.
2. **`@MessageMapping` application handlers.** The `/app` application destination prefix is configured in `WebSocketConfig` but no `@MessageMapping` handlers exist yet. Epic 04 adds bid submission via `/app/bid`.
3. **Redis pub/sub session eviction on `tv` bump** (Q3d deferral). When bid placement flows through STOMP and the impact of a stale `tv` matters, Epic 04 adds Redis-driven session closure.
4. **Multi-instance STOMP relay** (RabbitMQ / ActiveMQ). Single-instance in-memory broker is sufficient for Phase 1.
5. **`WsTestController` removal.** The test harness ships with this task. A future task deletes `wstest/` package and `/dev/ws-test` page once Epic 04 auction-room subscriptions are stable enough to serve as the verification surface.
6. **Frontend WS layer security review for auth redirect UX.** Currently a `RefreshFailedError` during `beforeConnect` surfaces as a generic error badge. Epic 04 may want to redirect to `/login?next=` from the WS layer itself, similar to the HTTP interceptor's current redirect. This task does not block on it because no production surface consumes the WS layer yet.
7. **`subscribe()` deferral race hardening.** The deferred-attach pattern in §6.4 (`lib/ws/client.ts`) has a known edge case: if `onConnect → onWebSocketClose` fires in rapid succession (fast backend bounce), the state listener may see `"reconnecting"` mid-cycle and keep waiting through the reconnect. The subscription is not lost — it attaches on the next successful connect — but the first message is delayed by one reconnect cycle. Acceptable for this task's dev harness where reconnects are manual or rare. Epic 04's auction-room subscriptions will see flaky networks and should harden the re-attach strategy (e.g., re-attach on every connected transition with subscription dedup, instead of self-unsubscribing the state listener after one fire).

## 15. Acceptance Criteria Mapping

From `task-09-websocket-setup.md`:

| Brief AC                                                                 | Covered by                                                                                 |
|--------------------------------------------------------------------------|--------------------------------------------------------------------------------------------|
| WebSocket connection establishes between frontend and backend            | §11.2 test 1 (STOMP integration), §11.3 test 6 (onConnect transition), manual /dev/ws-test |
| Frontend can subscribe to a topic and receive messages                   | §11.2 test 1 (round-trip), §11.3 tests 1-2, §11.5 tests 1-3, manual /dev/ws-test           |
| Connection requires a valid JWT (unauthenticated connections rejected)  | §11.1 tests 2-3, 5-6 (interceptor), §11.2 tests 2-3 (integration)                          |
| Reconnection works after a brief disconnection                           | §11.3 tests 3-4 (grace/reconnect), §8 (reconnect flow), manual Force Disconnect button     |
| Test topic proves messages flow from backend to frontend in real-time    | §11.2 test 1 (the load-bearing integration test) + manual /dev/ws-test page                |

All five criteria are covered both by tests and by the manual verification harness.

## 16. Risks and Mitigations

| Risk                                                                                          | Mitigation                                                                                                                                                  |
|------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| SockJS handshake CORS error on first `/ws/info` XHR                                            | `WebSocketConfig.addEndpoint("/ws").setAllowedOrigins(allowedOrigin)` — §5.1. Tested implicitly in §11.2.                                                    |
| `vi.mock("@stomp/stompjs")` leaks between tests and pollutes module state                      | `__resetWsClientForTests()` in `beforeEach`. Mirrors existing session state reset pattern. Covered in §11.3 setup.                                          |
| Extracted `lib/auth/refresh.ts` breaks existing HTTP 401 canary                                | Run `api.401-interceptor.test.tsx` unchanged against the delegated implementation. The canary's contract is preserved because `handleUnauthorized` still catches refresh failure and performs the redirect. |
| Backend integration test is flaky under CI load (2s timeout on `queue.poll`)                   | Pick a generous timeout (5s) in CI; 2s is fine locally. Document in the plan task's TDD step. Trivially tunable.                                           |
| `beforeConnect` is an async callback and stompjs v7 may not await it in some edge paths        | v7.1+ fully awaits `beforeConnect`. Pinning `^7.1.1` in package.json guarantees the behavior. If a future upgrade breaks this, the canary will fail.       |
| Next.js 16 RSC boundary: client-only modules imported from `app/dev/ws-test/page.tsx`          | The page.tsx is a Server Component that does `notFound()` in prod and renders `<WsTestHarness />` otherwise. `WsTestHarness` is `"use client"`. Clean split. |
| `setAllowedOrigins` wildcard-vs-string pitfall (CORS + credentials = not-allowed wildcard)      | Use exact origin string from `application.yml`. Not a wildcard. See §5.1.                                                                                  |

## 17. Self-Review Notes

This section is the controller's own sanity check on the spec before handing off to `writing-plans`. It should be removed before commit if policy says so — leaving it here for now so the plan-writer can see the decisions at a glance.

- **Placeholder scan:** no TBDs, no "implement later" placeholders. Every section either has full code or a specific enough description that the plan-writer can produce full code.
- **Internal consistency:** §5 (backend modules), §6 (frontend modules), §11 (tests), §12 (file inventory) all list the same file paths. Cross-checked.
- **Scope check:** single subsystem (WebSocket infra), single concern (JWT-authenticated STOMP pipe + verification harness). Not decomposable — the backend broker, the frontend client, and the test harness are interdependent and must ship together to prove anything.
- **Ambiguity check:**
  - "How does the frontend detect unauthenticated-WS-error vs other-STOMP-error?" — unified via `ConnectionState`'s `error` variant with `detail` string. Dev harness shows the detail inline. Epic 04 followup §14.6 noted.
  - "Where does `configureRefresh` get called?" — `app/providers.tsx`, replacing existing `configureApiClient` call. Explicit in §12.
  - "Does `useStompSubscription` handle destination changes?" — yes, via the `[destination]` dep array. `[]` would be a bug. Explicit in §6.6.
