"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { useToast } from "@/components/ui/Toast";
import {
  userApi,
  verificationApi,
  type UpdateProfileRequest,
} from "./api";

export const CURRENT_USER_KEY = ["currentUser"] as const;
export const VERIFICATION_ACTIVE_KEY = ["verification", "active"] as const;

export function useCurrentUser(options?: { refetchInterval?: number | false }) {
  const session = useAuth();
  return useQuery({
    queryKey: CURRENT_USER_KEY,
    queryFn: () => userApi.me(),
    enabled: session.status === "authenticated",
    staleTime: 60_000,
    gcTime: Number.POSITIVE_INFINITY,
    refetchInterval: options?.refetchInterval ?? false,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: true,
    retry: false,
  });
}

export function useUpdateProfile() {
  const queryClient = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: (body: UpdateProfileRequest) => userApi.updateMe(body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: CURRENT_USER_KEY });
      toast.success("Profile updated");
    },
    onError: (error) => {
      if (!(error instanceof ApiError && error.status === 400)) {
        toast.error("Failed to update profile");
      }
    },
  });
}

export function useUploadAvatar() {
  const queryClient = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: (file: File) => userApi.uploadAvatar(file),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: CURRENT_USER_KEY });
      toast.success("Avatar uploaded");
    },
    onError: (error) => {
      if (error instanceof ApiError && error.status === 413) {
        toast.error("Avatar must be 2MB or less");
      } else if (error instanceof ApiError && error.status === 400) {
        toast.error("Upload must be a JPEG, PNG, or WebP image");
      } else {
        toast.error("Failed to upload avatar");
      }
    },
  });
}

export function useUploadDefaultCover() {
  const queryClient = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: ({
      variant,
      file,
    }: {
      variant: "light" | "dark";
      file: File;
    }) => userApi.uploadDefaultCover(variant, file),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: CURRENT_USER_KEY });
      toast.success(
        variables.variant === "dark"
          ? "Dark cover updated"
          : "Light cover updated",
      );
    },
    onError: (error) => {
      if (error instanceof ApiError && error.status === 400) {
        toast.error("Upload must be a JPEG, PNG, or WebP image");
      } else {
        toast.error("Failed to upload cover image");
      }
    },
  });
}

export function useDeleteDefaultCover() {
  const queryClient = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: ({ variant }: { variant: "light" | "dark" }) =>
      userApi.deleteDefaultCover(variant),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: CURRENT_USER_KEY });
      toast.success(
        variables.variant === "dark"
          ? "Dark cover removed"
          : "Light cover removed",
      );
    },
    onError: () => {
      toast.error("Failed to remove cover image");
    },
  });
}

export function useActiveVerificationCode() {
  return useQuery({
    queryKey: VERIFICATION_ACTIVE_KEY,
    queryFn: async () => {
      try {
        return await verificationApi.active();
      } catch (error) {
        if (error instanceof ApiError && error.status === 404) {
          return null;
        }
        throw error;
      }
    },
    staleTime: 30_000,
  });
}

export function useGenerateVerificationCode() {
  const queryClient = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: () => verificationApi.generate(),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: VERIFICATION_ACTIVE_KEY });
      toast.success("New verification code generated");
    },
    onError: () => {
      toast.error("Failed to generate verification code");
    },
  });
}
