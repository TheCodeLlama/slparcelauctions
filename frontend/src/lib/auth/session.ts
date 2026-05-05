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
