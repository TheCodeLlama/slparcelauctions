import { api } from "@/lib/api";
import type { ParcelTagGroupDto } from "@/types/parcelTag";

/**
 * GET /api/v1/parcel-tags — returns the full active tag catalogue grouped
 * by category. Public endpoint (no auth required) — both the anonymous
 * Epic 07 browse filters and the authenticated listing-wizard TagSelector
 * consume this list, so React Query callers should use a long staleTime
 * (the catalogue changes rarely).
 */
export function listParcelTagGroups(): Promise<ParcelTagGroupDto[]> {
  return api.get<ParcelTagGroupDto[]>("/api/v1/parcel-tags");
}
