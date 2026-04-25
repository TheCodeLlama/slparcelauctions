// frontend/src/test/msw/fixtures.ts
import type { AuthUser } from "@/lib/auth/session";
import type { CurrentUser, PublicUserProfile } from "@/lib/user/api";

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

export const mockUnverifiedCurrentUser: CurrentUser = {
  id: 42,
  email: "unverified@example.com",
  displayName: "Test User",
  bio: null,
  profilePicUrl: null,
  slAvatarUuid: null,
  slAvatarName: null,
  slUsername: null,
  slDisplayName: null,
  slBornDate: null,
  slPayinfo: null,
  verified: false,
  verifiedAt: null,
  emailVerified: true,
  notifyEmail: {},
  notifySlIm: {},
  // Listing-suspension defaults — a clean account has no pending penalty,
  // no timed suspension, and is not banned. Tests that exercise the
  // SuspensionBanner / cancel-modal ban-precedence paths override these
  // per-test (Epic 08 sub-spec 2 §7.2).
  penaltyBalanceOwed: 0,
  listingSuspensionUntil: null,
  bannedFromListing: false,
  createdAt: "2026-04-01T10:00:00Z",
  updatedAt: "2026-04-01T10:00:00Z",
};

export const mockVerifiedCurrentUser: CurrentUser = {
  ...mockUnverifiedCurrentUser,
  id: 42,
  displayName: "Verified Tester",
  bio: "Auction enthusiast",
  profilePicUrl: "/api/v1/users/42/avatar/large",
  slAvatarUuid: "11111111-1111-1111-1111-111111111111",
  slAvatarName: "TesterBot Resident",
  slUsername: "testerbot.resident",
  slDisplayName: "TesterBot",
  slBornDate: "2011-03-15",
  slPayinfo: 2,
  verified: true,
  verifiedAt: "2026-04-14T12:00:00Z",
  updatedAt: "2026-04-14T12:00:00Z",
};

export const mockPublicProfile: PublicUserProfile = {
  id: 42,
  displayName: "Verified Tester",
  bio: "Auction enthusiast",
  profilePicUrl: "/api/v1/users/42/avatar/large",
  slAvatarUuid: "11111111-1111-1111-1111-111111111111",
  slAvatarName: "TesterBot Resident",
  slUsername: "testerbot.resident",
  slDisplayName: "TesterBot",
  verified: true,
  avgSellerRating: 4.7,
  avgBuyerRating: null,
  totalSellerReviews: 12,
  totalBuyerReviews: 0,
  completedSales: 8,
  createdAt: "2026-04-01T10:00:00Z",
};

export const mockNewSellerPublicProfile: PublicUserProfile = {
  ...mockPublicProfile,
  id: 43,
  displayName: "New Seller",
  avgSellerRating: null,
  totalSellerReviews: 0,
  completedSales: 0,
};

export const mockUnverifiedPublicProfile: PublicUserProfile = {
  ...mockPublicProfile,
  id: 44,
  displayName: "Unverified Tester",
  verified: false,
  slAvatarUuid: null,
  slAvatarName: null,
  slUsername: null,
  slDisplayName: null,
};

export const mockValidationProblemDetail = {
  status: 400,
  title: "Bad Request",
  detail: "Validation failed",
  errors: { displayName: "must not be blank" },
};

export const mockUploadTooLargeProblemDetail = {
  status: 413,
  title: "Payload Too Large",
  detail: "Avatar must be 2MB or less",
};

export const mockUnsupportedFormatProblemDetail = {
  status: 400,
  title: "Bad Request",
  detail: "Upload must be a JPEG, PNG, or WebP image",
};

export const mockUserNotFoundProblemDetail = {
  status: 404,
  title: "Not Found",
  detail: "User not found",
};

export const mockVerificationNotFoundProblemDetail = {
  status: 404,
  title: "Not Found",
  detail: "No active verification code",
};

export const mockAlreadyVerifiedProblemDetail = {
  status: 409,
  title: "Conflict",
  detail: "User is already verified",
};
