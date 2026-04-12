/**
 * Auth hook entrypoint. Replaced in full by Task 01-08 Task 17 with a real
 * JWT-backed implementation. For now, re-exports the stub so that components
 * importing from `@/lib/auth` continue to resolve while the auth library is
 * being built out across Phase B.
 */
import type { AuthSession } from "./session";
export type { AuthUser, AuthSession } from "./session";

export function useAuth(): AuthSession {
  return { status: "unauthenticated", user: null };
}
