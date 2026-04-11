/**
 * Stub auth hook. Returns an unauthenticated session.
 *
 * Replaced in Task 01-08 with a real JWT-backed implementation that
 * reads from localStorage and exposes login/logout mutations. Callers
 * MUST treat the return shape as the contract — do not add fields
 * here without updating the replacement in 01-08.
 */

export type AuthUser = {
  id: number;
  email: string;
  displayName: string;
  slAvatarUuid: string | null;
  verified: boolean;
};

export type AuthSession =
  | { status: "loading"; user: null }
  | { status: "authenticated"; user: AuthUser }
  | { status: "unauthenticated"; user: null };

export function useAuth(): AuthSession {
  return { status: "unauthenticated", user: null };
}
