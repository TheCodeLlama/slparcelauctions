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
 * Single-fire gate that the API client awaits on every non-bootstrap request.
 *
 * <p>Before this gate existed, the access token bootstrap (POST
 * {@code /api/v1/auth/refresh}, fired by {@code useAuth}) ran in parallel with
 * any other {@code useQuery} hooks on the page. A hook that fired its fetch
 * before the bootstrap resolved would call {@code getAccessToken()} while the
 * token was still {@code null}, send the request without an
 * {@code Authorization} header, and get the anonymous-view response from the
 * backend's privacy gate (commission rates / joinedAt / permissions all
 * nulled). The cached anonymous response would then persist for the rest of
 * the session.
 *
 * <p>The gate fixes this once for every caller: {@link awaitAuthReady}
 * returns a promise that resolves the moment the bootstrap settles (success
 * or 401), so a hook's first fetch is guaranteed to read the post-bootstrap
 * token value.
 *
 * <p><b>Default state:</b> resolved. So Jest/Vitest tests (which use a test
 * render wrapper that seeds {@code SESSION_QUERY_KEY} directly and never
 * runs the bootstrap query) don't hang on every {@code api.get}. Production
 * flips the gate to pending via {@link beginAuthBootstrap} called from
 * {@code app/providers.tsx}; the bootstrap calls {@link markAuthReady} in
 * {@code finally} to close the gate again.
 */
let authReady: Promise<void> = Promise.resolve();
let resolveAuthReady: () => void = () => {};

export function awaitAuthReady(): Promise<void> {
  return authReady;
}

/**
 * Open the gate. Called once on client mount from {@code app/providers.tsx};
 * idempotent against double-calls because we only reopen if the gate is
 * currently resolved — once {@link bootstrapSession} is in flight we don't
 * want a re-mount of {@code <Providers>} (HMR, etc.) to clobber the pending
 * promise mid-bootstrap.
 */
export function beginAuthBootstrap(): void {
  authReady = new Promise<void>((resolve) => {
    resolveAuthReady = resolve;
  });
}

/**
 * Close the gate. Called from {@code bootstrapSession}'s {@code finally} so
 * the gate resolves on success AND on 401. Tests that seed the auth state
 * directly (and never run the bootstrap) don't need to call this — the gate
 * starts resolved.
 */
export function markAuthReady(): void {
  resolveAuthReady();
}

/**
 * Frontend-side projection of the backend's `UserResponse` (Task 01-07 §3).
 * Only the fields the frontend actually uses are projected here; the backend
 * returns more fields (bio, profilePicUrl, slUsername, etc.) which are
 * available on `response.user` if a future component needs them.
 */
export type AuthUser = {
  publicId: string;
  username: string;
  email: string | null;
  displayName: string | null;
  slAvatarUuid: string | null;
  verified: boolean;
  role: "USER" | "ADMIN";
};

/**
 * Discriminated union for the three states the auth layer can be in. The
 * `useAuth()` hook returns this shape; consumers branch on `status`.
 */
export type AuthSession =
  | { status: "loading"; user: null }
  | { status: "authenticated"; user: AuthUser }
  | { status: "unauthenticated"; user: null };
