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
