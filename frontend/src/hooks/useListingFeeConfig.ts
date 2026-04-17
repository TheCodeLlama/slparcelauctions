"use client";

import { useQuery } from "@tanstack/react-query";
import { getListingFeeConfig } from "@/lib/api/config";

export const LISTING_FEE_CONFIG_KEY = ["config", "listing-fee"] as const;

/**
 * Reads the current listing-fee amount from the backend. Used by the
 * Activate panel and the "platform cost" badge on the public browse page.
 *
 * The fee only changes with a backend restart, so we cache it for an hour
 * — far longer than a typical session — to keep the listing flow snappy.
 */
export function useListingFeeConfig() {
  return useQuery({
    queryKey: LISTING_FEE_CONFIG_KEY,
    queryFn: getListingFeeConfig,
    staleTime: 60 * 60 * 1000,
    gcTime: 60 * 60 * 1000,
  });
}
