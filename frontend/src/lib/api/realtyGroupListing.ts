import { api } from "@/lib/api";
import type { ListingEligibleGroup } from "@/types/realty";

/**
 * Fetch the groups in which the authenticated caller holds {@code CREATE_LISTING}
 * (or is leader). Drives the ListAsGroupPicker on the auction-create wizard.
 *
 * Backend: {@code GET /api/v1/realty/me/listing-eligible-groups}
 */
export function fetchListingEligibleGroups(): Promise<ListingEligibleGroup[]> {
  return api.get<ListingEligibleGroup[]>("/api/v1/realty/me/listing-eligible-groups");
}
