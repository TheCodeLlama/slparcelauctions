"use client";
import { useQuery } from "@tanstack/react-query";
import { fetchListingEligibleGroups } from "@/lib/api/realtyGroupListing";

export function useListingEligibleGroups() {
  return useQuery({
    queryKey: ["realty", "me", "listing-eligible-groups"],
    queryFn: fetchListingEligibleGroups,
  });
}
