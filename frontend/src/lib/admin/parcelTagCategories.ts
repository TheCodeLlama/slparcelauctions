import { api } from "@/lib/api";

export interface AdminParcelTagCategoryDto {
  code: string;
  label: string;
  description: string | null;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateParcelTagCategoryPayload {
  code: string;
  label: string;
  description?: string;
}

export interface UpdateParcelTagCategoryPayload {
  label?: string;
  description?: string;
}

export const adminParcelTagCategoriesApi = {
  list: () => api.get<AdminParcelTagCategoryDto[]>("/api/v1/admin/parcel-tag-categories"),
  create: (body: CreateParcelTagCategoryPayload) =>
    api.post<AdminParcelTagCategoryDto>("/api/v1/admin/parcel-tag-categories", body),
  update: (code: string, body: UpdateParcelTagCategoryPayload) =>
    api.patch<AdminParcelTagCategoryDto>(
      `/api/v1/admin/parcel-tag-categories/${code}`,
      body,
    ),
  toggleActive: (code: string) =>
    api.post<AdminParcelTagCategoryDto>(
      `/api/v1/admin/parcel-tag-categories/${code}/toggle-active`,
    ),
};
