// frontend/src/lib/auth/api.ts
import { api } from "@/lib/api";
import type { AuthUser } from "./session";

/**
 * Typed wrappers for the 5 backend auth endpoints from Task 01-07.
 *
 * Each wrapper is a thin call to the shared `api` client; centralizing the
 * request/response shapes here keeps the hooks file focused on TanStack Query
 * orchestration.
 */

export type AuthResponse = {
  accessToken: string;
  user: AuthUser;
};

export type RegisterRequest = {
  email: string;
  password: string;
  displayName: string | null;
};

export type LoginRequest = {
  email: string;
  password: string;
};

export const authApi = {
  register: (body: RegisterRequest) =>
    api.post<AuthResponse>("/api/v1/auth/register", body),

  login: (body: LoginRequest) =>
    api.post<AuthResponse>("/api/v1/auth/login", body),

  refresh: () =>
    api.post<AuthResponse>("/api/v1/auth/refresh"),

  logout: () =>
    api.post<void>("/api/v1/auth/logout"),

  logoutAll: () =>
    api.post<void>("/api/v1/auth/logout-all"),
};
