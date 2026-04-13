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

  it("single call hits /api/v1/auth/refresh once and returns the fresh token", async () => {
    let refreshCount = 0;
    server.use(
      http.post("*/api/v1/auth/refresh", () => {
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
      http.post("*/api/v1/auth/refresh", async () => {
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
      http.post("*/api/v1/auth/refresh", () => {
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
