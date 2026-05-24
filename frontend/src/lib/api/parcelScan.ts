import { apiUrl } from "@/lib/api/url";
import type { ParcelScanResponse } from "@/types/auction";

/**
 * Fetch the parcel-scan rasters for an auction. Returns null on 404
 * (no scan rows yet, scan failed, or parcelScanIncluded=false on the
 * auction). Throws on any other non-2xx response so React Query
 * surfaces it as an error.
 *
 * The endpoint is public (no auth required) and served with
 * Cache-Control: public, immutable, max-age=365d, so a re-mount within
 * the same browser session is free. Raw fetch is used intentionally
 * here rather than the authenticated api wrapper -- this is an
 * unauthenticated, cache-friendly public read and does not need the
 * auth-refresh interceptor.
 */
export async function getParcelScan(
  publicId: string,
): Promise<ParcelScanResponse | null> {
  const r = await fetch(apiUrl(`/api/v1/auctions/${publicId}/parcel-scan`)!);
  if (r.status === 404) return null;
  if (!r.ok) {
    throw new Error(`getParcelScan ${publicId} failed: ${r.status}`);
  }
  return (await r.json()) as ParcelScanResponse;
}
