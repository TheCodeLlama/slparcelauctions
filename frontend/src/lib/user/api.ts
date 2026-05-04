import { api } from "@/lib/api";

export type CurrentUser = {
  publicId: string;
  email: string;
  displayName: string | null;
  bio: string | null;
  profilePicUrl: string | null;
  slAvatarUuid: string | null;
  slAvatarName: string | null;
  slUsername: string | null;
  slDisplayName: string | null;
  slBornDate: string | null;
  slPayinfo: number | null;
  verified: boolean;
  verifiedAt: string | null;
  emailVerified: boolean;
  notifyEmail: Record<string, unknown>;
  notifySlIm: Record<string, unknown>;
  /**
   * Outstanding L$ penalty balance owed by this seller (Epic 08 sub-spec 2
   * §7.2). Sourced from {@code User.penaltyBalanceOwed}; defaults to 0 for
   * sellers with no offenses on the ladder. Cleared by walk-in payment at
   * any SLPA terminal — there is no "Pay now" button on the web side.
   */
  penaltyBalanceOwed: number;
  /**
   * UTC timestamp at which the seller's listing privileges automatically
   * resume, or {@code null} if no timed suspension is active. Independent
   * of {@code penaltyBalanceOwed} — a seller can owe L$ without being
   * timed-suspended (PENALTY rung) and vice-versa (PENALTY_AND_30D after
   * payment clears).
   */
  listingSuspensionUntil: string | null;
  /**
   * {@code true} once the seller hits the fourth cancel-with-bids offense.
   * Permanent (subject to admin reversal); a banned seller's listing
   * wizard is gated server-side on this flag and the dashboard surfaces
   * the {@code SuspensionBanner} permanent variant.
   */
  bannedFromListing: boolean;
  /**
   * Unread notification count seeded on the /me response so the bell badge
   * is available immediately on page load without a separate round-trip.
   * Kept live by the WS notification stream thereafter.
   */
  unreadNotificationCount: number;
  createdAt: string;
  updatedAt: string;
};

export type PublicUserProfile = {
  publicId: string;
  displayName: string | null;
  bio: string | null;
  profilePicUrl: string | null;
  slAvatarUuid: string | null;
  slAvatarName: string | null;
  slUsername: string | null;
  slDisplayName: string | null;
  verified: boolean;
  avgSellerRating: number | null;
  avgBuyerRating: number | null;
  totalSellerReviews: number;
  totalBuyerReviews: number;
  completedSales: number;
  createdAt: string;
};

export type UpdateProfileRequest = {
  displayName?: string;
  bio?: string;
};

export type ActiveCodeResponse = {
  code: string;
  expiresAt: string;
};

export type GenerateCodeResponse = {
  code: string;
  expiresAt: string;
};

export const userApi = {
  me: () => api.get<CurrentUser>("/api/v1/users/me"),
  updateMe: (body: UpdateProfileRequest) =>
    api.put<CurrentUser>("/api/v1/users/me", body),
  uploadAvatar: (file: File) => {
    const form = new FormData();
    form.append("file", file);
    return api.post<CurrentUser>("/api/v1/users/me/avatar", form);
  },
  publicProfile: (publicId: string) =>
    api.get<PublicUserProfile>(`/api/v1/users/${publicId}`),
  deleteSelf: (password: string): Promise<void> =>
    api.delete("/api/v1/users/me", { body: { password } }),
};

export const verificationApi = {
  active: () => api.get<ActiveCodeResponse>("/api/v1/verification/active"),
  generate: () => api.post<GenerateCodeResponse>("/api/v1/verification/generate"),
};
