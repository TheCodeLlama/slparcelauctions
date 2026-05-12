"use client";
import { useInfiniteQuery } from "@tanstack/react-query";
import { getGroupLedger } from "@/lib/api/realtyGroupWallet";

/**
 * Infinite-scroll hook for the group ledger. Pages are cursor-based:
 * the backend returns entries sorted {@code created_at DESC}, and the
 * next cursor is the {@code createdAt} of the last entry in the page.
 *
 * @param publicId  Group public UUID.
 * @param pageSize  Entries per page (default 50; backend clamps to 100).
 */
export function useGroupLedger(publicId: string, pageSize = 50) {
  return useInfiniteQuery({
    queryKey: ["realty", "group", publicId, "ledger", pageSize],
    queryFn: ({ pageParam }: { pageParam?: string }) =>
      getGroupLedger(publicId, pageParam, pageSize),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) =>
      last.length === pageSize ? last[last.length - 1].createdAt : undefined,
  });
}
