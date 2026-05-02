"use client";
import { useQuery } from "@tanstack/react-query";
import { ActiveFilterBadge } from "@/components/ui/ActiveFilterBadge";
import { defaultAuctionSearchQuery } from "@/lib/search/url-codec";
import { listParcelTagGroups } from "@/lib/api/parcelTags";
import { PARCEL_TAGS_KEY } from "@/components/listing/TagSelector";
import type { AuctionSearchQuery } from "@/types/search";

export interface ActiveFiltersProps {
  query: AuctionSearchQuery;
  onChange: (next: AuctionSearchQuery) => void;
  /**
   * Partial filters that are "pinned" by the surrounding page — e.g. the
   * {@code sellerId} on /users/{id}/listings. Pinned filters never appear
   * as removable chips and "Clear all" preserves them.
   */
  fixedFilters?: Partial<AuctionSearchQuery>;
  className?: string;
}

type Chip = {
  key: string;
  label: string;
  remove: (q: AuctionSearchQuery) => AuctionSearchQuery;
};

const ENDING_WITHIN_LABEL: Record<number, string> = {
  1: "Ends in 1h",
  6: "Ends in 6h",
  24: "Ends in 24h",
};

/**
 * Title-case an enum-ish token for chip display: {@code "NOT_MET"} →
 * {@code "Not Met"}, {@code "classic"} → {@code "Classic"}. Splits on
 * underscores and whitespace so multi-word values stay readable.
 */
function titleCase(s: string): string {
  return s
    .toLowerCase()
    .replace(/_/g, " ")
    .replace(/\b\w/g, (c) => c.toUpperCase());
}

/**
 * Build the chip list by diffing the current {@code query} against the
 * baseline {@link defaultAuctionSearchQuery}. Fixed filters (e.g.
 * {@code sellerId} on the seller-listings page) are omitted — they're
 * implicit on that surface and removing them would break the page's
 * invariant.
 *
 * {@code tagLabelByCode} maps canonical tag codes ("CORNER_LOT") to their
 * display labels ("Corner Lot"). Falls back to the code if the catalogue
 * hasn't loaded yet or a code is missing.
 */
function buildChips(
  query: AuctionSearchQuery,
  tagLabelByCode: Map<string, string>,
): Chip[] {
  const chips: Chip[] = [];
  if (query.region) {
    chips.push({
      key: "region",
      label: query.region,
      remove: (q) => ({ ...q, region: undefined }),
    });
  }
  if (query.minPrice !== undefined || query.maxPrice !== undefined) {
    let label: string;
    if (query.minPrice !== undefined && query.maxPrice === undefined) {
      label = `L$${query.minPrice}+`;
    } else if (query.minPrice === undefined && query.maxPrice !== undefined) {
      label = `L$0–${query.maxPrice}`;
    } else {
      label = `L$${query.minPrice}–${query.maxPrice}`;
    }
    chips.push({
      key: "price",
      label,
      remove: (q) => ({ ...q, minPrice: undefined, maxPrice: undefined }),
    });
  }
  if (query.minArea !== undefined || query.maxArea !== undefined) {
    let label: string;
    if (query.minArea !== undefined && query.maxArea === undefined) {
      label = `${query.minArea} m²+`;
    } else if (query.minArea === undefined && query.maxArea !== undefined) {
      label = `0–${query.maxArea} m²`;
    } else {
      label = `${query.minArea}–${query.maxArea} m²`;
    }
    chips.push({
      key: "area",
      label,
      remove: (q) => ({ ...q, minArea: undefined, maxArea: undefined }),
    });
  }
  if (query.maturity?.length) {
    chips.push({
      key: "maturity",
      label: query.maturity.map(titleCase).join(", "),
      remove: (q) => ({ ...q, maturity: undefined }),
    });
  }
  if (query.tags?.length) {
    for (const code of query.tags) {
      const label = tagLabelByCode.get(code) ?? code;
      chips.push({
        key: `tags:${code}`,
        label,
        remove: (q) => {
          const nextTags = (q.tags ?? []).filter((t) => t !== code);
          const empty = nextTags.length === 0;
          return {
            ...q,
            tags: empty ? undefined : nextTags,
            // Mode is only meaningful with 2+ tags; drop it when the
            // last tag goes so the URL stays at the canonical baseline.
            tagsMode: empty ? undefined : q.tagsMode,
          };
        },
      });
    }
    if (query.tagsMode === "and" && query.tags.length >= 2) {
      chips.push({
        key: "tagsMode",
        label: "Match all",
        remove: (q) => ({ ...q, tagsMode: undefined }),
      });
    }
  }
  if (query.reserveStatus && query.reserveStatus !== "all") {
    chips.push({
      key: "reserveStatus",
      label: titleCase(query.reserveStatus),
      remove: (q) => ({ ...q, reserveStatus: undefined }),
    });
  }
  if (query.snipeProtection && query.snipeProtection !== "any") {
    chips.push({
      key: "snipeProtection",
      label: titleCase(query.snipeProtection),
      remove: (q) => ({ ...q, snipeProtection: undefined }),
    });
  }
  if (query.verificationTier?.length) {
    chips.push({
      key: "verificationTier",
      label: query.verificationTier.map(titleCase).join(", "),
      remove: (q) => ({ ...q, verificationTier: undefined }),
    });
  }
  if (query.endingWithin !== undefined) {
    chips.push({
      key: "endingWithin",
      label: ENDING_WITHIN_LABEL[query.endingWithin] ?? `Ends in ${query.endingWithin}h`,
      remove: (q) => ({ ...q, endingWithin: undefined }),
    });
  }
  if (query.nearRegion) {
    const distance = query.distance !== undefined ? ` (≤${query.distance} regions)` : "";
    chips.push({
      key: "nearRegion",
      label: `${query.nearRegion}${distance}`,
      remove: (q) => ({ ...q, nearRegion: undefined, distance: undefined }),
    });
  }
  return chips;
}

/**
 * Row of removable chips above the results grid. Empty when no filters
 * differ from the default baseline. "Clear all" resets to
 * {@link defaultAuctionSearchQuery} while preserving any
 * {@code fixedFilters} (e.g. the {@code sellerId} pinned by
 * /users/{id}/listings).
 */
export function ActiveFilters({
  query,
  onChange,
  fixedFilters,
  className,
}: ActiveFiltersProps) {
  // Shares cache with TagSelector (same query key + long staleTime); no
  // extra network hit unless the catalogue query hasn't been fetched yet.
  const { data: tagGroups } = useQuery({
    queryKey: PARCEL_TAGS_KEY,
    queryFn: listParcelTagGroups,
    staleTime: 60 * 60 * 1000,
    gcTime: 60 * 60 * 1000,
    enabled: Boolean(query.tags?.length),
  });
  const tagLabelByCode = new Map<string, string>(
    (tagGroups ?? []).flatMap((g) => g.tags.map((t) => [t.code, t.label])),
  );
  const chips = buildChips(query, tagLabelByCode);
  if (chips.length === 0) return null;

  return (
    <div className={className}>
      <div className="flex flex-wrap items-center gap-2">
        {chips.map((c) => (
          <ActiveFilterBadge
            key={c.key}
            label={c.label}
            onRemove={() => onChange({ ...c.remove(query), page: 0 })}
          />
        ))}
        <button
          type="button"
          onClick={() =>
            onChange({ ...defaultAuctionSearchQuery, ...(fixedFilters ?? {}) })
          }
          className="text-xs text-brand hover:underline"
        >
          Clear all
        </button>
      </div>
    </div>
  );
}
