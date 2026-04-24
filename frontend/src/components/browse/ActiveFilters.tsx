"use client";
import { ActiveFilterBadge } from "@/components/ui/ActiveFilterBadge";
import { defaultAuctionSearchQuery } from "@/lib/search/url-codec";
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
  1: "Ending within 1h",
  6: "Ending within 6h",
  24: "Ending within 24h",
};

/**
 * Build the chip list by diffing the current {@code query} against the
 * baseline {@link defaultAuctionSearchQuery}. Fixed filters (e.g.
 * {@code sellerId} on the seller-listings page) are omitted — they're
 * implicit on that surface and removing them would break the page's
 * invariant.
 */
function buildChips(query: AuctionSearchQuery): Chip[] {
  const chips: Chip[] = [];
  if (query.region) {
    chips.push({
      key: "region",
      label: `Region: ${query.region}`,
      remove: (q) => ({ ...q, region: undefined }),
    });
  }
  if (query.minPrice !== undefined || query.maxPrice !== undefined) {
    const lo = query.minPrice ?? 0;
    const hi = query.maxPrice ?? "max";
    chips.push({
      key: "price",
      label: `Price: ${lo}-${hi}`,
      remove: (q) => ({ ...q, minPrice: undefined, maxPrice: undefined }),
    });
  }
  if (query.minArea !== undefined || query.maxArea !== undefined) {
    const lo = query.minArea ?? 512;
    const hi = query.maxArea ?? "max";
    chips.push({
      key: "area",
      label: `Size: ${lo}-${hi}`,
      remove: (q) => ({ ...q, minArea: undefined, maxArea: undefined }),
    });
  }
  if (query.maturity?.length) {
    chips.push({
      key: "maturity",
      label: `Maturity: ${query.maturity.join(", ")}`,
      remove: (q) => ({ ...q, maturity: undefined }),
    });
  }
  if (query.tags?.length) {
    const mode = query.tagsMode && query.tagsMode !== "or" ? ` (${query.tagsMode})` : "";
    chips.push({
      key: "tags",
      label: `Tags: ${query.tags.join(", ")}${mode}`,
      remove: (q) => ({ ...q, tags: undefined, tagsMode: undefined }),
    });
  }
  if (query.reserveStatus && query.reserveStatus !== "all") {
    chips.push({
      key: "reserveStatus",
      label: `Reserve: ${query.reserveStatus.replace(/_/g, " ")}`,
      remove: (q) => ({ ...q, reserveStatus: undefined }),
    });
  }
  if (query.snipeProtection && query.snipeProtection !== "any") {
    chips.push({
      key: "snipeProtection",
      label: `Snipe protection: ${query.snipeProtection}`,
      remove: (q) => ({ ...q, snipeProtection: undefined }),
    });
  }
  if (query.verificationTier?.length) {
    chips.push({
      key: "verificationTier",
      label: `Verification: ${query.verificationTier.join(", ")}`,
      remove: (q) => ({ ...q, verificationTier: undefined }),
    });
  }
  if (query.endingWithin !== undefined) {
    chips.push({
      key: "endingWithin",
      label: ENDING_WITHIN_LABEL[query.endingWithin] ?? `Ending within ${query.endingWithin}h`,
      remove: (q) => ({ ...q, endingWithin: undefined }),
    });
  }
  if (query.nearRegion) {
    const distance = query.distance !== undefined ? ` (≤${query.distance} regions)` : "";
    chips.push({
      key: "nearRegion",
      label: `Near: ${query.nearRegion}${distance}`,
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
  const chips = buildChips(query);
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
          className="text-body-sm text-primary hover:underline"
        >
          Clear all
        </button>
      </div>
    </div>
  );
}
