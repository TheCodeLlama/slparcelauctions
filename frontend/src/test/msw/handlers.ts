// frontend/src/test/msw/handlers.ts
import { http, HttpResponse } from "msw";
import {
  mockUser,
  mockAuthResponse,
  mockUnverifiedCurrentUser,
  mockVerifiedCurrentUser,
  mockPublicProfile,
  mockValidationProblemDetail,
  mockUploadTooLargeProblemDetail,
  mockUnsupportedFormatProblemDetail,
  mockUserNotFoundProblemDetail,
  mockVerificationNotFoundProblemDetail,
  mockAlreadyVerifiedProblemDetail,
} from "./fixtures";
import type { CurrentUser, PublicUserProfile, UpdateProfileRequest } from "@/lib/user/api";

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
    http.post("*/api/v1/auth/refresh", () =>
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
    http.post("*/api/v1/auth/refresh", () =>
      HttpResponse.json(mockAuthResponse(user), {
        status: 200,
        headers: {
          "Set-Cookie": "refreshToken=fake-refresh; HttpOnly; Path=/api/v1/auth; SameSite=Lax",
        },
      })
    ),

  registerSuccess: (user = mockUser) =>
    http.post("*/api/v1/auth/register", () =>
      HttpResponse.json(mockAuthResponse(user), {
        status: 201,
        headers: {
          "Set-Cookie": "refreshToken=fake-refresh; HttpOnly; Path=/api/v1/auth; SameSite=Lax",
        },
      })
    ),

  registerEmailExists: () =>
    http.post("*/api/v1/auth/register", () =>
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
    http.post("*/api/v1/auth/register", () =>
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
    http.post("*/api/v1/auth/login", () =>
      HttpResponse.json(mockAuthResponse(user), {
        status: 200,
        headers: {
          "Set-Cookie": "refreshToken=fake-refresh; HttpOnly; Path=/api/v1/auth; SameSite=Lax",
        },
      })
    ),

  loginInvalidCredentials: () =>
    http.post("*/api/v1/auth/login", () =>
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
    http.post("*/api/v1/auth/logout", () =>
      new HttpResponse(null, { status: 204 })
    ),

  logoutAllSuccess: () =>
    http.post("*/api/v1/auth/logout-all", () =>
      new HttpResponse(null, { status: 204 })
    ),
};

export const userHandlers = {
  meUnverified: (user: CurrentUser = mockUnverifiedCurrentUser) =>
    http.get("*/api/v1/users/me", () => HttpResponse.json(user)),

  meVerified: (user: CurrentUser = mockVerifiedCurrentUser) =>
    http.get("*/api/v1/users/me", () => HttpResponse.json(user)),

  meError: () =>
    http.get("*/api/v1/users/me", () =>
      HttpResponse.json(
        { status: 500, title: "Internal Server Error" },
        { status: 500 }
      )
    ),

  updateMeSuccess: (base: CurrentUser = mockVerifiedCurrentUser) =>
    http.put("*/api/v1/users/me", async ({ request }) => {
      const body = (await request.json()) as UpdateProfileRequest;
      return HttpResponse.json({
        ...base,
        displayName: body.displayName ?? base.displayName,
        bio: body.bio ?? base.bio,
        updatedAt: new Date().toISOString(),
      });
    }),

  updateMeValidationError: () =>
    http.put("*/api/v1/users/me", () =>
      HttpResponse.json(mockValidationProblemDetail, { status: 400 })
    ),

  uploadAvatarSuccess: (user: CurrentUser = mockVerifiedCurrentUser) =>
    http.post("*/api/v1/users/me/avatar", () =>
      HttpResponse.json({ ...user, updatedAt: new Date().toISOString() })
    ),

  uploadAvatarOversized: () =>
    http.post("*/api/v1/users/me/avatar", () =>
      HttpResponse.json(mockUploadTooLargeProblemDetail, { status: 413 })
    ),

  uploadAvatarUnsupportedFormat: () =>
    http.post("*/api/v1/users/me/avatar", () =>
      HttpResponse.json(mockUnsupportedFormatProblemDetail, { status: 400 })
    ),

  publicProfileSuccess: (profile: PublicUserProfile = mockPublicProfile) =>
    http.get("*/api/v1/users/:id", () => HttpResponse.json(profile)),

  publicProfileNotFound: () =>
    http.get("*/api/v1/users/:id", () =>
      HttpResponse.json(mockUserNotFoundProblemDetail, { status: 404 })
    ),
};

export const savedHandlers = {
  idsEmpty: () =>
    http.get("*/api/v1/me/saved/ids", () =>
      HttpResponse.json({ ids: [] as number[] }),
    ),

  idsPopulated: (ids: number[]) =>
    http.get("*/api/v1/me/saved/ids", () => HttpResponse.json({ ids })),

  auctionsEmpty: () =>
    http.get("*/api/v1/me/saved/auctions", () =>
      HttpResponse.json({
        content: [],
        page: 0,
        size: 24,
        totalElements: 0,
        totalPages: 0,
        first: true,
        last: true,
      }),
    ),

  saveSuccess: (auctionId: number) =>
    http.post("*/api/v1/me/saved", () =>
      HttpResponse.json(
        { auctionId, savedAt: "2026-04-23T00:00:00Z" },
        { status: 201 },
      ),
    ),

  saveLimitReached: () =>
    http.post("*/api/v1/me/saved", () =>
      HttpResponse.json(
        { status: 409, code: "SAVED_LIMIT_REACHED", title: "Limit reached" },
        { status: 409 },
      ),
    ),

  savePreActive: () =>
    http.post("*/api/v1/me/saved", () =>
      HttpResponse.json(
        {
          status: 403,
          code: "CANNOT_SAVE_PRE_ACTIVE",
          title: "Not available",
        },
        { status: 403 },
      ),
    ),

  unsaveSuccess: () =>
    http.delete("*/api/v1/me/saved/:id", () =>
      new HttpResponse(null, { status: 204 }),
    ),
};

export const verificationHandlers = {
  activeNone: () =>
    http.get("*/api/v1/verification/active", () =>
      HttpResponse.json(mockVerificationNotFoundProblemDetail, { status: 404 })
    ),

  activeExists: (code = "123456", expiresAt = "2026-04-14T21:00:00Z") =>
    http.get("*/api/v1/verification/active", () =>
      HttpResponse.json({ code, expiresAt })
    ),

  generateSuccess: (code = "654321", expiresAt = "2026-04-14T21:15:00Z") =>
    http.post("*/api/v1/verification/generate", () =>
      HttpResponse.json({ code, expiresAt })
    ),

  generateAlreadyVerified: () =>
    http.post("*/api/v1/verification/generate", () =>
      HttpResponse.json(mockAlreadyVerifiedProblemDetail, { status: 409 })
    ),
};

/**
 * Default handlers registered at server startup. Establishes the "no session"
 * baseline so tests that don't explicitly authenticate get the unauthenticated
 * bootstrap path automatically.
 */
export const defaultHandlers = [authHandlers.refreshUnauthenticated()];
