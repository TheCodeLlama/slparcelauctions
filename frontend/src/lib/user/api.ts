import { api } from "@/lib/api";

export type CurrentUser = {
  publicId: string;
  username: string;
  email: string;
  displayName: string | null;
  bio: string | null;
  profilePicUrl: string | null;
  /**
   * Relative URL to the user's default cover image, or null when unset.
   * Backed by a public proxy endpoint so {@code <img src>} renders without
   * the JWT. Auto-inserted as the first photo of every new listing the
   * user creates after setting it.
   */
  defaultCoverUrl: string | null;
  slAvatarUuid: string | null;
  slAvatarName: string | null;
  slUsername: string | null;
  slDisplayName: string | null;
  slBornDate: string | null;
  slPayinfo: number | null;
  verified: boolean;
  verifiedAt: string | null;
  emailVerified: boolean;
  /**
   * Forced post-verify onboarding step #1: true once the user uploads an
   * avatar, picks their SL profile photo, or skips. Drives the
   * (onboarded) layout redirect to /dashboard/avatar.
   */
  avatarStepCompleted: boolean;
  /**
   * Forced post-verify onboarding step #2: true once the user saves a
   * display name or skips. Drives the (onboarded) layout redirect to
   * /dashboard/display-name.
   */
  displayNameStepCompleted: boolean;
  notifyEmail: Record<string, unknown>;
  notifySlIm: Record<string, unknown>;
  /**
   * Outstanding L$ penalty balance owed by this seller (Epic 08 sub-spec 2
   * §7.2). Sourced from {@code User.penaltyBalanceOwed}; defaults to 0 for
   * sellers with no offenses on the ladder. Cleared by walk-in payment at
   * any SLParcels terminal — there is no "Pay now" button on the web side.
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
  uploadAvatarBlob: (blob: Blob) => {
    // Cropper output is WebP; backend ImageUploadValidator decodes via
    // Scrimage (dwebp). Wire format is the same regardless of extension —
    // both fields are illustrative for any server-side logging.
    const file = new File([blob], "avatar.webp", { type: blob.type || "image/webp" });
    return userApi.uploadAvatar(file);
  },
  publicProfile: (publicId: string) =>
    api.get<PublicUserProfile>(`/api/v1/users/${publicId}`),
  deleteSelf: (password: string): Promise<void> =>
    api.delete("/api/v1/users/me", { body: { password } }),
  uploadDefaultCover: (file: File) => {
    const form = new FormData();
    form.append("file", file);
    return api.put<DefaultCoverDto>("/api/v1/users/me/default-cover", form);
  },
  deleteDefaultCover: (): Promise<void> =>
    api.delete("/api/v1/users/me/default-cover"),
};

export type DefaultCoverDto = {
  url: string;
  contentType: string;
  sizeBytes: number;
};

export const onboardingApi = {
  skipAvatar: () =>
    api.post<CurrentUser>("/api/v1/users/me/onboarding/avatar/skip"),
  setDisplayName: (displayName: string | null) =>
    api.post<CurrentUser>("/api/v1/users/me/onboarding/display-name", {
      displayName,
    }),
  /**
   * Fetches the user's SL profile photo bytes through the backend proxy.
   * Returns null when the backend says 404 (no SL avatar UUID set, no
   * profile photo, or scrape failed). Any other non-OK status throws.
   *
   * <p>Uses {@code fetch} directly because {@code api.get} parses JSON;
   * we need the raw response so we can read it as a Blob and feed it to
   * the cropper via {@code URL.createObjectURL}.
   */
  fetchSlProfilePhoto: async (): Promise<Blob | null> => {
    const { getAccessToken } = await import("@/lib/auth/session");
    const token = getAccessToken();
    const base = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";
    const res = await fetch(`${base}/api/v1/users/me/onboarding/sl-profile-photo`, {
      credentials: "include",
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    if (res.status === 404) return null;
    if (!res.ok) {
      throw new Error(`Failed to fetch SL profile photo (${res.status})`);
    }
    return await res.blob();
  },
};

export const verificationApi = {
  active: () => api.get<ActiveCodeResponse>("/api/v1/verification/active"),
  generate: () => api.post<GenerateCodeResponse>("/api/v1/verification/generate"),
};
