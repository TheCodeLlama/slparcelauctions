"use client";
import { useQuery } from "@tanstack/react-query";
import { fetchListingEligibleGroups } from "@/lib/api/realtyGroupListing";

/**
 * Fetch the realty groups in which the caller can publish a listing for
 * the given SL parcel. The {@code slParcelUuid} filter is required from
 * Realty Groups: E onwards — the backend only returns groups that have at
 * least one verified SL group whose SL group is the current SL owner of
 * the parcel.
 *
 * <p>The query is disabled while {@code slParcelUuid} is empty/undefined
 * (no parcel selected yet on the wizard). Callers should render an empty
 * picker until then.
 */
export function useListingEligibleGroups(slParcelUuid: string | undefined) {
  return useQuery({
    queryKey: ["realty", "me", "listing-eligible-groups", slParcelUuid ?? ""],
    queryFn: () => fetchListingEligibleGroups(slParcelUuid!),
    enabled: !!slParcelUuid,
  });
}
