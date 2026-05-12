import { api } from "@/lib/api";
import type { ListingEligibleGroup } from "@/types/realty";

/**
 * Fetch the groups in which the authenticated caller holds {@code CREATE_LISTING}
 * (or is leader) AND that have at least one verified SL-group registration whose
 * SL group is the current SL owner of {@code slParcelUuid}. Drives the
 * ListAsGroupPicker on the auction-create wizard.
 *
 * <p>Backend: {@code GET /api/v1/realty/me/listing-eligible-groups?slParcelUuid=...}
 *
 * <p>The {@code slParcelUuid} filter is mandatory from Realty Groups: E
 * onwards — the listing flow only knows what to offer once the parcel is
 * selected, since the SL-group ownership check requires the parcel UUID.
 */
export function fetchListingEligibleGroups(
  slParcelUuid: string,
): Promise<ListingEligibleGroup[]> {
  return api.get<ListingEligibleGroup[]>(
    "/api/v1/realty/me/listing-eligible-groups",
    { params: { slParcelUuid } },
  );
}
