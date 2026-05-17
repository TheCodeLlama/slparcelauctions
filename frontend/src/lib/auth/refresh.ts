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
let inFlightRefresh: Promise<RefreshResult> | null = null;

const BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const SESSION_QUERY_KEY = ["auth", "session"] as const;

type RefreshResponse = { accessToken: string; user: AuthUser };

/**
 * Resolved value of {@link ensureFreshAccessToken}. Callers that only need
 * the access token (401 interceptor, STOMP {@code beforeConnect}) can
 * destructure {@code accessToken}; the {@code useAuth} bootstrap also
 * needs {@code user} to populate the session cache when running in a
 * context where {@link configureRefresh} hasn't been wired up (e.g.
 * test wrappers that build their own {@code QueryClient}).
 */
export interface RefreshResult {
  accessToken: string;
  user: AuthUser;
}

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
 * in-flight promise. Returns the new access token and the user payload.
 * On failure, clears the in-memory access token and the session query
 * cache, then throws RefreshFailedError.
 *
 * Callers that need redirect-to-login behavior (HTTP 401 interceptor) do so
 * after catching RefreshFailedError.
 */
export async function ensureFreshAccessToken(): Promise<RefreshResult> {
  if (inFlightRefresh) return inFlightRefresh;

  inFlightRefresh = (async () => {
    try {
      const response = await fetch(`${BASE_URL}/api/v1/auth/refresh`, {
        method: "POST",
        credentials: "include",
      });

      if (!response.ok) {
        setAccessToken(null);
        if (queryClientRef) {
          // The refresh cookie is invalid / revoked / expired — session is
          // dead. Wipe every cached query before re-establishing the null
          // auth entry so per-user caches (currentUser, wallet, ledger,
          // dashboard, etc.) don't survive into the unauthenticated state.
          queryClientRef.clear();
          queryClientRef.setQueryData(SESSION_QUERY_KEY, null);
        }
        throw new RefreshFailedError(response.status);
      }

      const body = (await response.json()) as RefreshResponse;
      setAccessToken(body.accessToken);
      if (queryClientRef) {
        queryClientRef.setQueryData(SESSION_QUERY_KEY, body.user);
      }
      return { accessToken: body.accessToken, user: body.user };
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
