"use client";

import { ComboboxOption, ComboboxOptions } from "@headlessui/react";
import type { UseQueryResult } from "@tanstack/react-query";
import { cn } from "@/lib/cn";
import type { SuggestResponse } from "@/lib/api/search-suggest";
import { SearchResultRow } from "./SearchResultRow";

export type SearchSelection =
  | { kind: "listing"; id: string }
  | { kind: "region"; name: string }
  | { kind: "browse"; q: string };

export interface SearchResultsListProps {
  state: Pick<
    UseQueryResult<SuggestResponse>,
    "data" | "isLoading" | "isError" | "isFetching"
  >;
  /** Debounced + trimmed query — drives skeletons / results / empty
   *  copy. Lags the input by up to 250ms (debounce settle). */
  trimmed: string;
  /** Live trimmed query — drives the BrowseOption value so a user can
   *  Enter immediately after typing without waiting for the debounce
   *  to settle. */
  liveTrimmed: string;
}

function GroupHeader({ label }: { label: string }) {
  return (
    <div className="px-3 pt-3 pb-1 text-xs font-semibold uppercase tracking-wider text-fg-muted">
      {label}
    </div>
  );
}

function Skeleton() {
  return (
    <div className="px-3 py-2">
      <div className="h-10 w-full animate-pulse rounded-md bg-bg-muted" />
    </div>
  );
}

export function SearchResultsList({
  state,
  trimmed,
  liveTrimmed,
}: SearchResultsListProps) {
  const { data, isLoading, isError } = state;

  // BrowseOption is keyed off the LIVE input so Enter routes to
  // /browse?q=… immediately even before the 250ms debounce settles.
  // Other option values (skeletons / results) lag on `trimmed` because
  // they reflect what the backend last returned for.
  const browse = liveTrimmed.length >= 2 ? (
    <BrowseOption trimmed={liveTrimmed} />
  ) : null;

  if (liveTrimmed.length < 2) {
    return <ComboboxOptions static className="py-2" />;
  }
  if (isError) {
    return (
      <ComboboxOptions static className="py-2">
        <div className="px-3 py-4 text-center text-sm text-fg-muted">
          Search is unavailable right now.
        </div>
        {browse}
      </ComboboxOptions>
    );
  }
  if (isLoading || !data || trimmed !== liveTrimmed) {
    return (
      <ComboboxOptions static className="py-2">
        <Skeleton />
        <Skeleton />
        <Skeleton />
        <Skeleton />
        {browse}
      </ComboboxOptions>
    );
  }
  const empty = data.listings.length === 0 && data.regions.length === 0;
  if (empty) {
    return (
      <ComboboxOptions static className="py-2">
        <div className="px-3 py-4 text-center text-sm text-fg-muted">
          No matches for &ldquo;{trimmed}&rdquo;.
        </div>
        {browse}
      </ComboboxOptions>
    );
  }
  return (
    <ComboboxOptions static className="py-2 max-h-[440px] overflow-y-auto">
      {data.listings.length > 0 ? (
        <>
          <GroupHeader label="Listings" />
          {data.listings.map((l) => (
            <ComboboxOption
              key={l.publicId}
              value={
                { kind: "listing", id: l.publicId } satisfies SearchSelection
              }
              className={({ focus }) =>
                cn("cursor-pointer", focus && "bg-bg-muted")
              }
            >
              <SearchResultRow.Listing listing={l} />
            </ComboboxOption>
          ))}
        </>
      ) : null}
      {data.regions.length > 0 ? (
        <>
          <GroupHeader label="Regions" />
          {data.regions.map((r) => (
            <ComboboxOption
              key={r.name}
              value={
                { kind: "region", name: r.name } satisfies SearchSelection
              }
              className={({ focus }) =>
                cn("cursor-pointer", focus && "bg-bg-muted")
              }
            >
              <SearchResultRow.Region region={r} />
            </ComboboxOption>
          ))}
        </>
      ) : null}
      {data.totalListings > data.listings.length ? (
        <BrowseOption
          trimmed={liveTrimmed}
          label={`See all ${data.totalListings} results for "${trimmed}" →`}
        />
      ) : (
        browse
      )}
    </ComboboxOptions>
  );
}

/**
 * The "browse" sentinel option that always exists once the user has
 * typed >=2 chars. Always being present means Headless UI Combobox's
 * native Enter handling auto-selects it whenever no other option is
 * active — no manual bare-Enter detection needed.
 */
function BrowseOption({
  trimmed,
  label,
}: {
  trimmed: string;
  label?: string;
}) {
  return (
    <ComboboxOption
      value={{ kind: "browse", q: trimmed } satisfies SearchSelection}
      className={({ focus }) =>
        cn(
          "cursor-pointer border-t border-border px-3 py-2 text-sm text-brand",
          focus && "bg-bg-muted",
        )
      }
    >
      {label ?? `Search /browse for "${trimmed}" →`}
    </ComboboxOption>
  );
}
