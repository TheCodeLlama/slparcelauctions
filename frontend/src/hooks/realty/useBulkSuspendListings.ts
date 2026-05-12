"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { realtyGroupBulkListingsApi } from "@/lib/api/realtyGroupBulkListings";
import type {
  BulkReinstateListingsRequest,
  BulkSuspendListingsRequest,
} from "@/types/realty";

/**
 * Query key for the bulk-listings cascade view. Co-located so the
 * Bulk-Listings tab + reinstate mutation share one tuple. Note: the
 * underlying data lives on the listing-suspensions surface (not a
 * dedicated endpoint) — this key exists to drive cache invalidation
 * after suspend-all / reinstate-all runs.
 */
export function realtyGroupBulkListingsKey(groupPublicId: string) {
  return ["realty", "admin", "bulk-listings", groupPublicId] as const;
}

/**
 * Cascade a force-suspend across every active listing on the group. May
 * optionally tie the cascade back to a parent
 * {@link RealtyGroupSuspension} via {@code groupSuspensionPublicId}.
 *
 * <p>Invalidates the bulk-listings view (and the auctions search keys if
 * the admin is currently viewing a listing table) — but TanStack's
 * tree-prefix invalidation cleans them up via the higher-level
 * {@code ["realty","admin"]} ancestor key.
 */
export function useBulkSuspendListings(groupPublicId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: BulkSuspendListingsRequest) =>
      realtyGroupBulkListingsApi.suspendAll(groupPublicId, body),
    onSuccess: () => {
      qc.invalidateQueries({
        queryKey: realtyGroupBulkListingsKey(groupPublicId),
      });
    },
  });
}

/**
 * Cascade-lift every bulk-cause suspension on the group. Returns the
 * count of reinstated rows.
 */
export function useBulkReinstateListings(groupPublicId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: BulkReinstateListingsRequest) =>
      realtyGroupBulkListingsApi.reinstateAll(groupPublicId, body),
    onSuccess: () => {
      qc.invalidateQueries({
        queryKey: realtyGroupBulkListingsKey(groupPublicId),
      });
    },
  });
}
