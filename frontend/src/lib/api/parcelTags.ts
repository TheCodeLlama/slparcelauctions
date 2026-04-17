import { api } from "@/lib/api";
import type { ParcelTagGroupDto } from "@/types/parcelTag";

/**
 * GET /api/v1/parcel-tags — returns the full active tag catalogue grouped
 * by category. Public to any authenticated user; the TagSelector and the
 * Epic 07 browse filters both consume this list, so React Query callers
 * should use a long staleTime (the catalogue changes rarely).
 */
export function listParcelTagGroups(): Promise<ParcelTagGroupDto[]> {
  return api.get<ParcelTagGroupDto[]>("/api/v1/parcel-tags");
}
