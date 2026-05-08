import { api } from "@/lib/api";

export interface AdminParcelTagDto {
  code: string;
  label: string;
  category: string;
  description: string | null;
  sortOrder: number;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateParcelTagPayload {
  code: string;
  label: string;
  category: string;
  description?: string;
  sortOrder?: number;
}

export interface UpdateParcelTagPayload {
  label?: string;
  category?: string;
  description?: string;
  sortOrder?: number;
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
