// frontend/src/test/msw/handlers.ts
import { http, HttpResponse } from "msw";
import { mockUser, mockAuthResponse } from "./fixtures";

/**
 * Named handler factories for every auth endpoint, plus a `defaultHandlers`
 * export used by `vitest.setup.ts` to seed the server with the "logged out"
 * baseline.
 *
 * Per-test overrides:
 *   server.use(authHandlers.loginSuccess())
 *   server.use(authHandlers.registerEmailExists())
 *
 * Handler factories take optional fixture parameters (e.g., a custom user) so
 * tests can specialize without rebuilding the response from scratch.
 */
export const authHandlers = {
  // Default bootstrap state: no session cookie → 401 AUTH_TOKEN_MISSING.
  refreshUnauthenticated: () =>
    http.post("*/api/auth/refresh", () =>
      HttpResponse.json(
        {
          type: "https://slpa.example/problems/auth/token-missing",
          title: "Authentication required",
          status: 401,
          detail: "Authentication is required to access this resource.",
          code: "AUTH_TOKEN_MISSING",
        },
        { status: 401 }
      )
    ),

  refreshSuccess: (user = mockUser) =>
    http.post("*/api/auth/refresh", () =>
      HttpResponse.json(mockAuthResponse(user), {
        status: 200,
        headers: {
          "Set-Cookie": "refreshToken=fake-refresh; HttpOnly; Path=/api/auth; SameSite=Lax",
        },
      })
    ),

  registerSuccess: (user = mockUser) =>
    http.post("*/api/auth/register", () =>
      HttpResponse.json(mockAuthResponse(user), {
        status: 201,
        headers: {
          "Set-Cookie": "refreshToken=fake-refresh; HttpOnly; Path=/api/auth; SameSite=Lax",
        },
      })
    ),

  registerEmailExists: () =>
    http.post("*/api/auth/register", () =>
      HttpResponse.json(
        {
          type: "https://slpa.example/problems/auth/email-exists",
          title: "Email already registered",
          status: 409,
          detail: "An account with that email already exists.",
          code: "AUTH_EMAIL_EXISTS",
        },
        { status: 409 }
      )
    ),

  registerValidationError: (errors: { field: string; message: string }[]) =>
    http.post("*/api/auth/register", () =>
      HttpResponse.json(
        {
          type: "https://slpa.example/problems/validation",
          title: "Validation failed",
          status: 400,
          detail: `Request contains ${errors.length} invalid field(s).`,
          code: "VALIDATION_FAILED",
          errors,
        },
        { status: 400 }
      )
    ),

  loginSuccess: (user = mockUser) =>
    http.post("*/api/auth/login", () =>
      HttpResponse.json(mockAuthResponse(user), {
        status: 200,
        headers: {
          "Set-Cookie": "refreshToken=fake-refresh; HttpOnly; Path=/api/auth; SameSite=Lax",
        },
      })
    ),

  loginInvalidCredentials: () =>
    http.post("*/api/auth/login", () =>
      HttpResponse.json(
        {
          type: "https://slpa.example/problems/auth/invalid-credentials",
          title: "Invalid credentials",
          status: 401,
          detail: "Email or password is incorrect.",
          code: "AUTH_INVALID_CREDENTIALS",
        },
        { status: 401 }
      )
    ),

  logoutSuccess: () =>
    http.post("*/api/auth/logout", () =>
      new HttpResponse(null, { status: 204 })
    ),

  logoutAllSuccess: () =>
    http.post("*/api/auth/logout-all", () =>
      new HttpResponse(null, { status: 204 })
    ),
};

/**
 * Default handlers registered at server startup. Establishes the "no session"
 * baseline so tests that don't explicitly authenticate get the unauthenticated
 * bootstrap path automatically.
 */
export const defaultHandlers = [authHandlers.refreshUnauthenticated()];
