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

import { describe, it, expect, beforeEach } from "vitest";
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
      http.get("*/api/v1/users/me", () => {
        protectedCallCount++;
        if (protectedCallCount === 1) {
          return HttpResponse.json(
            { code: "AUTH_TOKEN_EXPIRED", status: 401 },
            { status: 401 }
          );
        }
        return HttpResponse.json({ id: 1, email: "test@example.com" });
      }),
      http.post("*/api/v1/auth/refresh", () => {
        refreshCallCount++;
        return HttpResponse.json({
          accessToken: "fresh-access-token",
          user: { id: 1, email: "test@example.com", displayName: null, slAvatarUuid: null, verified: false },
        });
      })
    );

    const result = await api.get<{ id: number; email: string }>("/api/v1/users/me");

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
      http.get("*/api/v1/users/me", () => {
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
      http.post("*/api/v1/auth/refresh", () => {
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
      api.get("/api/v1/users/me"),
      api.get("/api/v1/users/me"),
      api.get("/api/v1/users/me"),
    ]);

    expect(refreshCallCount).toBe(1); // ONE refresh, not three
    expect(protectedCallCount).toBe(6); // Three initial 401s + three retries
    expect(results).toHaveLength(3);
    expect(getAccessToken()).toBe("fresh-access-token");
  });

  it("clears session and redirects to /login on failed refresh", async () => {
    server.use(
      http.get("*/api/v1/users/me", () =>
        HttpResponse.json({ code: "AUTH_TOKEN_EXPIRED", status: 401 }, { status: 401 })
      ),
      http.post("*/api/v1/auth/refresh", () =>
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

    await expect(api.get("/api/v1/users/me")).rejects.toThrow();

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
