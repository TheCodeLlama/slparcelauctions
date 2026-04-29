"use client";

import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseQueryResult,
} from "@tanstack/react-query";
import { useMemo } from "react";
import { usePathname, useSearchParams } from "next/navigation";
import { ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { useToast } from "@/components/ui/Toast";
import {
  fetchSavedAuctions,
  fetchSavedIds,
  saveAuction,
  unsaveAuction,
} from "@/lib/api/saved";
import { canonicalKey } from "@/lib/search/canonical-key";
import type {
  AuctionSearchQuery,
  SavedIdsResponse,
  SearchResponse,
} from "@/types/search";

export const SAVED_IDS_KEY = ["saved", "ids"] as const;
export const SAVED_AUCTIONS_BASE_KEY = ["saved", "auctions"] as const;

/**
 * Produce the React Query key for a given saved-auctions query. The
 * canonical-key hash collapses two queries with the same filter set
 * (different object key order, different array order) to the same cache
 * entry so flipping back-and-forth between filters doesn't thrash the
 * cache.
 */
export function savedAuctionsQueryKey(
  query: AuctionSearchQuery,
): readonly unknown[] {
  return [...SAVED_AUCTIONS_BASE_KEY, canonicalKey(query)] as const;
}

const EMPTY_IDS: SavedIdsResponse = { ids: [] };

/**
 * Returns the full set of saved auction IDs for the current user.
 *
 * <p>When the caller is unauthenticated, returns {@code { ids: empty Set,
 * isSaved: () => false, isLoading: false }} synchronously — no network
 * round-trip, no pending state. Every ListingCard heart on the page can
 * safely call this hook without a guard.
 *
 * <p>Within an authenticated session the cache entry is {@code staleTime:
 * Infinity} — the set rarely changes, and any mutation (save/unsave) seeds
 * the next read via optimistic update or {@code onSettled} invalidation.
 */
export function useSavedIds(): {
  ids: Set<number>;
  isSaved: (id: number) => boolean;
  isLoading: boolean;
} {
  const session = useAuth();
  const authed = session.status === "authenticated";
  const query = useQuery<SavedIdsResponse>({
    queryKey: SAVED_IDS_KEY,
    queryFn: fetchSavedIds,
    enabled: authed,
    staleTime: Number.POSITIVE_INFINITY,
    gcTime: Number.POSITIVE_INFINITY,
    retry: false,
    refetchOnWindowFocus: false,
  });

  // Memoize on the query.data reference — React Query v5 preserves the
  // same array reference across refetches that return equal content, so this
  // avoids rebuilding the Set on every ListingCard render (up to 500 saves
  // x every mounted card) when nothing changed upstream.
  const data = query.data ?? EMPTY_IDS;
  const ids = useMemo(() => new Set<number>(data.ids), [data]);

  if (!authed) {
    return {
      ids: new Set<number>(),
      isSaved: () => false,
      isLoading: false,
    };
  }

  return {
    ids,
    isSaved: (id: number) => ids.has(id),
    isLoading: query.isLoading,
  };
}

/**
 * React Query mutation for toggling the saved state of an auction.
 *
 * <p>Unauthenticated callers receive a {@code toast.warning} with a
 * Sign in action that routes back to the current page on completion.
 *
 * <p>Authenticated callers get an optimistic update to the
 * {@code ["saved", "ids"]} cache entry. On error the optimistic value is
 * rolled back and a targeted toast is surfaced:
 *   - 409 {@code SAVED_LIMIT_REACHED} → "Curator Tray is full (500 saved)…"
 *   - 403 {@code CANNOT_SAVE_PRE_ACTIVE} → "This auction isn't available…"
 *   - 404 → "That auction no longer exists." + invalidate both caches.
 *
 * <p>{@code onSettled} invalidates BOTH {@code ["saved", "ids"]} AND
 * {@code ["saved", "auctions"]} with {@code refetchType: "active"} so a
 * closed Curator Tray doesn't background-fetch the tray's paged list
 * every heart toggle.
 */
export function useToggleSaved(): {
  toggle: (id: number) => Promise<void>;
  isPending: boolean;
} {
  const session = useAuth();
  const authed = session.status === "authenticated";
  const queryClient = useQueryClient();
  const toast = useToast();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  type ToggleVariables = { id: number; wasSaved: boolean };

  const mutation = useMutation<
    { op: "save" | "unsave"; id: number },
    unknown,
    ToggleVariables,
    { previous?: SavedIdsResponse }
  >({
    // Decision of save vs. unsave is captured in the {@code toggle} wrapper
    // below (inside the {@code wasSaved} flag on the variables payload) —
    // {@code onMutate} runs BEFORE {@code mutationFn}, so reading the cache
    // here would always see the already-flipped optimistic state.
    mutationFn: async ({ id, wasSaved }) => {
      if (wasSaved) {
        await unsaveAuction(id);
        return { op: "unsave", id };
      }
      await saveAuction(id);
      return { op: "save", id };
    },
    onMutate: async ({ id, wasSaved }) => {
      await queryClient.cancelQueries({ queryKey: SAVED_IDS_KEY });
      const previous =
        queryClient.getQueryData<SavedIdsResponse>(SAVED_IDS_KEY);
      const currentIds = previous?.ids ?? [];
      const nextIds = wasSaved
        ? currentIds.filter((x) => x !== id)
        : currentIds.includes(id)
          ? currentIds
          : [...currentIds, id];
      queryClient.setQueryData<SavedIdsResponse>(SAVED_IDS_KEY, {
        ids: nextIds,
      });
      return { previous };
    },
    onError: (error, _variables, context) => {
      if (context?.previous !== undefined) {
        queryClient.setQueryData<SavedIdsResponse>(
          SAVED_IDS_KEY,
          context.previous,
        );
      }
      if (error instanceof ApiError) {
        const code =
          typeof error.problem?.code === "string"
            ? (error.problem.code as string)
            : undefined;
        if (error.status === 409 && code === "SAVED_LIMIT_REACHED") {
          toast.error(
            "Curator Tray is full (500 saved). Remove some to add more.",
          );
          return;
        }
        if (error.status === 403 && code === "CANNOT_SAVE_PRE_ACTIVE") {
          toast.error("This auction isn't available to save yet.");
          return;
        }
        if (error.status === 404) {
          toast.error("That auction no longer exists.");
          queryClient.invalidateQueries({
            queryKey: SAVED_IDS_KEY,
            refetchType: "active",
          });
          queryClient.invalidateQueries({
            queryKey: SAVED_AUCTIONS_BASE_KEY,
            refetchType: "active",
          });
          return;
        }
      }
      toast.error("Couldn't update your saved parcels. Try again.");
    },
    onSettled: () => {
      // refetchType: "active" skips queries whose observers are unmounted
      // (e.g. the Curator Tray drawer when it is closed) so closed surfaces
      // don't background-fetch after every heart toggle.
      queryClient.invalidateQueries({
        queryKey: SAVED_IDS_KEY,
        refetchType: "active",
      });
      queryClient.invalidateQueries({
        queryKey: SAVED_AUCTIONS_BASE_KEY,
        refetchType: "active",
      });
    },
  });

  const toggle = async (id: number): Promise<void> => {
    if (!authed) {
      const next =
        typeof window !== "undefined"
          ? pathname +
            (searchParams && searchParams.toString()
              ? `?${searchParams.toString()}`
              : "")
          : "/";
      toast.warning({
        title: "Sign in to save parcels",
        action: {
          label: "Sign in",
          onClick: () => {
            if (typeof window !== "undefined") {
              window.location.assign(
                `/login?next=${encodeURIComponent(next)}`,
              );
            }
          },
        },
      });
      return;
    }
    // Snapshot BEFORE onMutate flips the cache so mutationFn can decide
    // save vs. unsave from the authoritative pre-mutation state.
    const snapshot = queryClient.getQueryData<SavedIdsResponse>(SAVED_IDS_KEY);
    const wasSaved = (snapshot?.ids ?? []).includes(id);
    await mutation.mutateAsync({ id, wasSaved }).catch(() => {
      // Errors are surfaced via the onError toast path; swallow the
      // rejection here so consumers (ListingCard heart onClick) don't
      // have to wrap every call in a try/catch.
    });
  };

  return { toggle, isPending: mutation.isPending };
}

/**
 * Paginated read of the saved-auctions list used by both the Curator Tray
 * drawer and the {@code /saved} page. Uses the same
 * {@link AuctionSearchQuery} shape as the browse search so the URL codec
 * and canonical-key hash are shared.
 *
 * <p>{@code staleTime: 0} — the tray is the source of truth for the
 * current saved set and users expect a heart-click to reflect immediately.
 */
export function useSavedAuctions(
  query: AuctionSearchQuery,
): UseQueryResult<SearchResponse> {
  const session = useAuth();
  const authed = session.status === "authenticated";
  return useQuery<SearchResponse>({
    queryKey: savedAuctionsQueryKey(query),
    queryFn: () => fetchSavedAuctions(query),
    enabled: authed,
    staleTime: 0,
  });
}
