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
  trimmed: string;
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

export function SearchResultsList({ state, trimmed }: SearchResultsListProps) {
  const { data, isLoading, isError } = state;

  if (trimmed.length < 2) {
    return <ComboboxOptions static className="py-2" />;
  }
  if (isError) {
    return (
      <ComboboxOptions
        static
        className="py-6 text-center text-sm text-fg-muted"
      >
        Search is unavailable right now.
      </ComboboxOptions>
    );
  }
  if (isLoading || !data) {
    return (
      <ComboboxOptions static className="py-2">
        <Skeleton />
        <Skeleton />
        <Skeleton />
        <Skeleton />
      </ComboboxOptions>
    );
  }
  const empty = data.listings.length === 0 && data.regions.length === 0;
  if (empty) {
    return (
      <ComboboxOptions
        static
        className="py-6 text-center text-sm text-fg-muted"
      >
        No matches for &ldquo;{trimmed}&rdquo;.
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
        <ComboboxOption
          value={{ kind: "browse", q: trimmed } satisfies SearchSelection}
          className={({ focus }) =>
            cn(
              "cursor-pointer border-t border-border px-3 py-2 text-sm text-brand",
              focus && "bg-bg-muted",
            )
          }
        >
          See all {data.totalListings} results for &ldquo;{trimmed}&rdquo; →
        </ComboboxOption>
      ) : null}
    </ComboboxOptions>
  );
}
