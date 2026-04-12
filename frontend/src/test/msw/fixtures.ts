// frontend/src/test/msw/fixtures.ts
import type { AuthUser } from "@/lib/auth/session";

/**
 * Default mock user used by handlers and tests. Override per-test by passing a
 * custom user to the handler factories in `handlers.ts`.
 */
export const mockUser: AuthUser = {
  id: 42,
  email: "test@example.com",
  displayName: null,
  slAvatarUuid: null,
  verified: false,
};

/**
 * Default mock authenticated response shape — matches the backend's `AuthResponse`
 * record (Task 01-07: `{ accessToken: string, user: UserResponse }`).
 */
export function mockAuthResponse(user: AuthUser = mockUser) {
  return {
    accessToken: "mock-access-token-jwt",
    user,
  };
}
