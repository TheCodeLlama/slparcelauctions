"use client";

import { useQuery } from "@tanstack/react-query";
import { searchSuggestApi } from "@/lib/api/search-suggest";
import { useDebouncedValue } from "./useDebouncedValue";

export const REGION_SUGGEST_KEY = ["region-suggest"] as const;

/**
 * Typeahead query for the Browse near_region autocomplete. Mirrors
 * {@link useSearchSuggest}: debounces 250ms so the React Query key
 * stabilizes per "settled" input (no fetch per keystroke) and gates on
 * length >= 2 (the backend re-checks). Hits the region-only suggest
 * variant so every result is a resolvable distance anchor.
 */
export function useRegionSuggest(rawQuery: string) {
  const debounced = useDebouncedValue(rawQuery, 250);
  const trimmed = debounced.trim();
  return useQuery({
    queryKey: [...REGION_SUGGEST_KEY, trimmed],
    queryFn: () => searchSuggestApi.regionSuggest(trimmed),
    enabled: trimmed.length >= 2,
    staleTime: 30_000,
    gcTime: 5 * 60_000,
    retry: false,
  });
}
