import { api } from "@/lib/api";
import type { ParcelDto } from "@/types/parcel";

/**
 * POST /api/v1/parcels/lookup — fetches (and caches server-side) SL parcel
 * metadata by UUID. Requires an authenticated and SL-verified caller, enforced
 * by ParcelController#requireVerified on the backend.
 *
 * Error statuses the UI must disambiguate (see ParcelLookupField):
 *   400 — malformed UUID (shape guard, bean validation failure)
 *   404 — SL World API could not resolve the parcel
 *   422 — resolved but outside Mainland continents (Phase 1 restriction)
 *   504 — SL World API unreachable or timed out
 */
export function lookupParcel(slParcelUuid: string): Promise<ParcelDto> {
  return api.post<ParcelDto>("/api/v1/parcels/lookup", { slParcelUuid });
}
