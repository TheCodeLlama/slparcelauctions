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
