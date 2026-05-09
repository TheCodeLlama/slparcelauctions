"use client";

import { useQuery } from "@tanstack/react-query";
import { searchSuggestApi } from "@/lib/api/search-suggest";
import { useDebouncedValue } from "./useDebouncedValue";

export const SEARCH_SUGGEST_KEY = ["search-suggest"] as const;

/**
 * Typeahead query for the header search overlay. Debounces 250ms so
 * the React Query key stabilizes per "settled" input — prevents a
 * fetch per keystroke. Gates on length >= 2 to skip the trivial
 * "user just opened the box" case (the backend re-checks).
 */
export function useSearchSuggest(rawQuery: string) {
  const debounced = useDebouncedValue(rawQuery, 250);
  const trimmed = debounced.trim();
  return useQuery({
    queryKey: [...SEARCH_SUGGEST_KEY, trimmed],
    queryFn: () => searchSuggestApi.suggest(trimmed),
    enabled: trimmed.length >= 2,
    staleTime: 30_000,
    gcTime: 5 * 60_000,
    retry: false,
  });
}
