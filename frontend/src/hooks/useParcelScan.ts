"use client";

import { useQuery } from "@tanstack/react-query";
import { getParcelScan } from "@/lib/api/parcelScan";
import type { ParcelScanResponse } from "@/types/auction";

/**
 * Query-key factory. Scoped by auction publicId so two open auction
 * detail pages don't collide in the cache.
 */
export function parcelScanKey(publicId: string): readonly unknown[] {
  return ["auction", publicId, "parcel-scan"] as const;
}

/**
 * React Query wrapper around {@link getParcelScan}. Rasters are immutable
 * per-auction per the parcel-scanner spec, so {@code staleTime} and
 * {@code gcTime} are set to Infinity -- a re-mount within the same React
 * Query cache lifetime never re-fetches.
 *
 * {@code data: null} signals "no scan available" (404 from the endpoint);
 * the {@code ParcelMap} component branches on that to render nothing.
 */
export function useParcelScan(publicId: string) {
  return useQuery<ParcelScanResponse | null>({
    queryKey: parcelScanKey(publicId),
    queryFn: () => getParcelScan(publicId),
    staleTime: Infinity,
    gcTime: Infinity,
  });
}
