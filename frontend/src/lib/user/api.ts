import { api } from "@/lib/api";

export type CurrentUser = {
  id: number;
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
  createdAt: string;
  updatedAt: string;
};

export type PublicUserProfile = {
  id: number;
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
  publicProfile: (id: number) =>
    api.get<PublicUserProfile>(`/api/v1/users/${id}`),
};

export const verificationApi = {
  active: () => api.get<ActiveCodeResponse>("/api/v1/verification/active"),
  generate: () => api.post<GenerateCodeResponse>("/api/v1/verification/generate"),
};
