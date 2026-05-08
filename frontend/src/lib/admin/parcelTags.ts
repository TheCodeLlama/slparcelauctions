import { api } from "@/lib/api";

export interface AdminParcelTagCategoryRef {
  code: string;
  label: string;
  active: boolean;
}

export interface AdminParcelTagDto {
  code: string;
  label: string;
  category: AdminParcelTagCategoryRef;
  description: string | null;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateParcelTagPayload {
  code: string;
  label: string;
  categoryCode: string;
  description?: string;
}

export interface UpdateParcelTagPayload {
  label?: string;
  categoryCode?: string;
  description?: string;
}

export const adminParcelTagsApi = {
  list: () => api.get<AdminParcelTagDto[]>("/api/v1/admin/parcel-tags"),
  create: (body: CreateParcelTagPayload) =>
    api.post<AdminParcelTagDto>("/api/v1/admin/parcel-tags", body),
  update: (code: string, body: UpdateParcelTagPayload) =>
    api.patch<AdminParcelTagDto>(`/api/v1/admin/parcel-tags/${code}`, body),
  toggleActive: (code: string) =>
    api.post<AdminParcelTagDto>(
      `/api/v1/admin/parcel-tags/${code}/toggle-active`,
    ),
};
