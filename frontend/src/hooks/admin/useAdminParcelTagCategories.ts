"use client";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { adminQueryKeys } from "@/lib/admin/queryKeys";
import {
  adminParcelTagCategoriesApi,
  type AdminParcelTagCategoryDto,
  type CreateParcelTagCategoryPayload,
  type UpdateParcelTagCategoryPayload,
} from "@/lib/admin/parcelTagCategories";

export function useAdminParcelTagCategories() {
  return useQuery<AdminParcelTagCategoryDto[]>({
    queryKey: adminQueryKeys.parcelTagCategoriesList(),
    queryFn: adminParcelTagCategoriesApi.list,
  });
}

export function useCreateParcelTagCategory() {
  const qc = useQueryClient();
  return useMutation<AdminParcelTagCategoryDto, unknown, CreateParcelTagCategoryPayload>({
    mutationFn: adminParcelTagCategoriesApi.create,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminQueryKeys.parcelTagCategoriesList() });
      // Tag list groups by category label — invalidate so disabled/renamed
      // categories propagate to the tags surface and the public catalogue.
      qc.invalidateQueries({ queryKey: adminQueryKeys.parcelTagsList() });
      qc.invalidateQueries({ queryKey: ["parcel-tags"] });
    },
  });
}

export function useUpdateParcelTagCategory() {
  const qc = useQueryClient();
  return useMutation<
    AdminParcelTagCategoryDto,
    unknown,
    { code: string; body: UpdateParcelTagCategoryPayload }
  >({
    mutationFn: ({ code, body }) => adminParcelTagCategoriesApi.update(code, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminQueryKeys.parcelTagCategoriesList() });
      qc.invalidateQueries({ queryKey: adminQueryKeys.parcelTagsList() });
      qc.invalidateQueries({ queryKey: ["parcel-tags"] });
    },
  });
}

export function useToggleParcelTagCategoryActive() {
  const qc = useQueryClient();
  return useMutation<AdminParcelTagCategoryDto, unknown, string>({
    mutationFn: (code) => adminParcelTagCategoriesApi.toggleActive(code),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminQueryKeys.parcelTagCategoriesList() });
      qc.invalidateQueries({ queryKey: adminQueryKeys.parcelTagsList() });
      qc.invalidateQueries({ queryKey: ["parcel-tags"] });
    },
  });
}
