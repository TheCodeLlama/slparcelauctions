import type { QueryClient } from "@tanstack/react-query";
import { getAccessToken, setAccessToken } from "@/lib/auth/session";

let queryClientRef: QueryClient | null = null;
let inFlightRefresh: Promise<void> | null = null;

/**
 * Wires the API client to the app's QueryClient. Called once at app mount from
 * `app/providers.tsx`. Storing the QueryClient reference at module scope lets
 * the 401 interceptor update the session query cache on failed refresh without
 * React lifecycle access.
 *
 * See FOOTGUNS §F.3.
 */
export function configureApiClient(queryClient: QueryClient): void {
  queryClientRef = queryClient;
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
 * Handles a 401 response from a non-auth path by attempting a token refresh
 * and retrying the original request.
 *
 * Concurrent-stampede protection: if multiple requests 401 at the same time,
 * they all await the same single refresh promise. Only the creator of that
 * promise clears `inFlightRefresh` (inside the IIFE's try/finally), not every
 * awaiter. This avoids a race where multiple awaiters null out the ref before
 * all callers have awaited it.
 *
 * See FOOTGUNS §F.4.
 */
async function handleUnauthorized<T>(path: string, options: RequestOptions): Promise<T> {
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
          const problem: ProblemDetail = { status: 401, title: "Session expired" };
          throw new ApiError(problem);
        }

        const refreshBody = (await refreshed.json()) as { accessToken: string; user: unknown };
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
  // isRetry=true prevents infinite refresh loops on a subsequent 401.
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
    // Auth-path 401s (login, refresh, etc.) are real failures the caller must
    // see. Non-auth-path 401s trigger the refresh-and-retry flow.
    return handleUnauthorized<T>(path, { body, headers, params, ...rest });
  }

  if (!response.ok) {
    let problem: ProblemDetail;
    try {
      problem = (await response.json()) as ProblemDetail;
    } catch {
      // Backend returned a non-JSON error body (proxy 502, network blip).
      // Synthesize a minimal ProblemDetail so callers see a consistent shape.
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
