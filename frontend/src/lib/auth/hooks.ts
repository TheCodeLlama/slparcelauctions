// frontend/src/lib/auth/hooks.ts
"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { authApi, type LoginRequest, type RegisterRequest } from "./api";
import { setAccessToken } from "./session";
import type { AuthSession, AuthUser } from "./session";

const SESSION_QUERY_KEY = ["auth", "session"] as const;

/**
 * Bootstrap the session by calling POST /api/v1/auth/refresh.
 *
 * The side effect (calling setAccessToken) lives INSIDE queryFn, NOT in
 * onSuccess. TanStack Query's first subscriber receives queryFn's return
 * value directly — onSuccess only fires on subsequent subscribers and
 * refetches. If we put setAccessToken in onSuccess, the first useAuth() call
 * would get the user but the access token ref would stay null until the next
 * refetch (which may never happen with staleTime: Infinity).
 *
 * See FOOTGUNS §F.2.
 */
async function bootstrapSession(): Promise<AuthUser> {
  const response = await authApi.refresh();
  setAccessToken(response.accessToken);
  return response.user;
}

/**
 * Returns the current auth session as a discriminated union.
 *
 * The three Query states map to the three AuthSession states:
 *   - isPending → { status: "loading", user: null }
 *   - isError   → { status: "unauthenticated", user: null }
 *   - success   → { status: "authenticated", user }
 *
 * The first call to useAuth() in the React tree triggers the bootstrap query
 * automatically — no dedicated AuthProvider is needed. Header's call to
 * useAuth() on every page mount is what kicks off the bootstrap.
 *
 * The query config uses three non-default flags, all load-bearing:
 *   - staleTime: Infinity — don't auto-refetch on every useAuth() call
 *   - gcTime: Infinity — never garbage-collect (Header may unmount briefly)
 *   - retry: false — a 401 on bootstrap is legitimate unauthenticated state
 *
 * The `query.data == null` guard (loose equality, intentional) catches the
 * case where the 401 interceptor calls setQueryData(["auth","session"], null)
 * after a failed refresh. In that state isPending and isError are both false,
 * but data is null — returning "authenticated" with null user would violate
 * the discriminated union.
 *
 * See FOOTGUNS §F.5.
 */
export function useAuth(): AuthSession {
  const query = useQuery({
    queryKey: SESSION_QUERY_KEY,
    queryFn: bootstrapSession,
    staleTime: Infinity,
    gcTime: Infinity,
    retry: false,
    refetchOnWindowFocus: false,
  });

  if (query.isPending) return { status: "loading", user: null };
  if (query.isError) return { status: "unauthenticated", user: null };
  if (query.data == null) return { status: "unauthenticated", user: null };
  return { status: "authenticated", user: query.data };
}

/**
 * Login mutation. On success, sets the access token and updates the session
 * query cache directly via setQueryData (NOT invalidateQueries — that would
 * trigger a wasted /refresh round-trip immediately after login).
 *
 * See FOOTGUNS §F.12.
 */
export function useLogin() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: LoginRequest) => authApi.login(body),
    onSuccess: (response) => {
      setAccessToken(response.accessToken);
      queryClient.setQueryData(SESSION_QUERY_KEY, response.user);
    },
  });
}

/**
 * Register mutation. Same orchestration as login — backend returns the same
 * AuthResponse shape on 201 Created.
 */
export function useRegister() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: RegisterRequest) => authApi.register(body),
    onSuccess: (response) => {
      setAccessToken(response.accessToken);
      queryClient.setQueryData(SESSION_QUERY_KEY, response.user);
    },
  });
}

/**
 * Logout mutation. Uses onSettled (NOT onSuccess) — clears local state even
 * if the network call fails. Logout is idempotent and a user clicking "Sign
 * Out" expects to be logged out regardless of whether the backend acknowledged
 * the POST.
 *
 * Uses setQueryData(null) only (no removeQueries) for consistency with the
 * 401 interceptor's pattern. The null guard in useAuth handles the null state.
 *
 * See FOOTGUNS §F.11.
 */
export function useLogout() {
  const queryClient = useQueryClient();
  const router = useRouter();
  return useMutation({
    mutationFn: () => authApi.logout(),
    onSettled: () => {
      setAccessToken(null);
      queryClient.setQueryData(SESSION_QUERY_KEY, null);
      router.push("/");
    },
  });
}

/**
 * LogoutAll mutation. Always refreshes BEFORE calling logout-all to handle
 * the "user's access token is already expired" edge case. Refresh is cheap
 * (~100ms); the alternative is failing on expired tokens and forcing the user
 * to re-login first.
 *
 * Uses setQueryData(null) only (no removeQueries) for consistency with the
 * 401 interceptor's pattern.
 *
 * Per Task 01-07 §15 frontend handoff note: this hook ships now (for use in
 * a future account-settings page) but is NOT surfaced in the Header dropdown.
 *
 * See FOOTGUNS §F.10.
 */
export function useLogoutAll() {
  const queryClient = useQueryClient();
  const router = useRouter();
  return useMutation({
    mutationFn: async () => {
      const refreshed = await authApi.refresh();
      setAccessToken(refreshed.accessToken);
      return authApi.logoutAll();
    },
    onSettled: () => {
      setAccessToken(null);
      queryClient.setQueryData(SESSION_QUERY_KEY, null);
      router.push("/");
    },
  });
}

/**
 * Forgot-password mutation.
 *
 * STUB: no backend password-reset endpoint exists yet. This mutationFn fakes
 * a ~300ms delay and always resolves successfully so the UI can display its
 * success state. NO EMAIL IS ACTUALLY SENT.
 *
 * When the real endpoint ships:
 *   1. Replace this body with `authApi.forgotPassword(email)` (and add the
 *      wrapper to lib/auth/api.ts).
 *   2. Remove the [STUB] indicator from ForgotPasswordForm's success state.
 *   3. Remove the inline comment in ForgotPasswordPage.
 *   4. Update the brief at docs/implementation/epic-01/task-08-frontend-auth.md
 *      to remove the "UI only" caveat.
 *
 * This four-step swap is the cost of shipping a UI without its backend.
 */
export function useForgotPassword() {
  return useMutation({
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    mutationFn: async (_forgotEmail: string) => {
      await new Promise((resolve) => setTimeout(resolve, 300));
      return { success: true };
    },
  });
}
