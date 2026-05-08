"use client";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { adminQueryKeys } from "@/lib/admin/queryKeys";
import {
  adminParcelTagsApi,
  type AdminParcelTagDto,
  type CreateParcelTagPayload,
  type UpdateParcelTagPayload,
} from "@/lib/admin/parcelTags";

export function useAdminParcelTags() {
  return useQuery<AdminParcelTagDto[]>({
    queryKey: adminQueryKeys.parcelTagsList(),
    queryFn: adminParcelTagsApi.list,
  });
}

export function useCreateParcelTag() {
  const qc = useQueryClient();
  return useMutation<AdminParcelTagDto, unknown, CreateParcelTagPayload>({
    mutationFn: adminParcelTagsApi.create,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminQueryKeys.parcelTagsList() });
      // Public catalogue cached separately via the parcel-tag selector — bust it
      // so newly-created tags appear without a hard refresh.
      qc.invalidateQueries({ queryKey: ["parcel-tags"] });
    },
  });
}

export function useUpdateParcelTag() {
  const qc = useQueryClient();
  return useMutation<
    AdminParcelTagDto,
    unknown,
    { code: string; body: UpdateParcelTagPayload }
  >({
    mutationFn: ({ code, body }) => adminParcelTagsApi.update(code, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminQueryKeys.parcelTagsList() });
      qc.invalidateQueries({ queryKey: ["parcel-tags"] });
    },
  });
}

export function useToggleParcelTagActive() {
  const qc = useQueryClient();
  return useMutation<AdminParcelTagDto, unknown, string>({
    mutationFn: (code) => adminParcelTagsApi.toggleActive(code),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminQueryKeys.parcelTagsList() });
      qc.invalidateQueries({ queryKey: ["parcel-tags"] });
    },
  });
}
