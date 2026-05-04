"use client";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { TagSelector } from "@/components/listing/TagSelector";
import { FilterSection } from "./FilterSection";
import { DistanceSearchBlock } from "./DistanceSearchBlock";
import type {
  AuctionSearchQuery,
  MaturityRating,
  ReserveStatusFilter,
  SnipeFilter,
  VerificationTier,
  TagsMode,
} from "@/types/search";

export type FilterMode = "immediate" | "staged";

export interface FilterSidebarContentProps {
  mode: FilterMode;
  query: AuctionSearchQuery;
  /** Fires on every change when {@code mode === "immediate"}. */
  onChange?: (next: AuctionSearchQuery) => void;
  /** Fires only when the Apply button is clicked when {@code mode === "staged"}. */
  onCommit?: (next: AuctionSearchQuery) => void;
  hiddenGroups?: Array<"distance" | "seller">;
  errorCode?: string;
}

const MATURITY_OPTIONS: MaturityRating[] = ["GENERAL", "MODERATE", "ADULT"];
const TIER_OPTIONS: Array<{ value: VerificationTier; label: string }> = [
  { value: "SCRIPT", label: "Script" },
  { value: "BOT", label: "Bot" },
  { value: "OWNERSHIP_TRANSFER", label: "Ownership transfer" },
];
const ENDING_OPTIONS: Array<{ value: number | undefined; label: string }> = [
  { value: undefined, label: "Any time" },
  { value: 1, label: "Within 1 hour" },
  { value: 6, label: "Within 6 hours" },
  { value: 24, label: "Within 24 hours" },
];
const RESERVE_OPTIONS: Array<{ value: ReserveStatusFilter; label: string }> = [
  { value: "all", label: "All" },
  { value: "reserve_met", label: "Reserve met" },
  { value: "reserve_not_met", label: "Reserve not met" },
  { value: "no_reserve", label: "No reserve" },
];
const SNIPE_OPTIONS: Array<{ value: SnipeFilter; label: string }> = [
  { value: "any", label: "Any" },
  { value: "true", label: "With snipe protection" },
  { value: "false", label: "Without" },
];

/**
 * Parse a number input into a non-negative integer or {@code undefined} for
 * empty / NaN. No upper bound — L$ sales regularly exceed L$1M and parcel
 * sizes can be as small as 4m×4m (16m²), so we don't impose arbitrary caps.
 */
function parseBound(raw: string): number | undefined {
  if (raw === "") return undefined;
  const n = Number(raw);
  if (!Number.isFinite(n)) return undefined;
  return Math.max(n, 0);
}

/**
 * Filter group composition for the browse sidebar. Two modes share the
 * same layout and field bindings:
 *
 *   - {@code immediate}: desktop sidebar. Every change fires {@code onChange}
 *     so the parent can push a new URL + re-query.
 *   - {@code staged}: inside the mobile BottomSheet. Local state accumulates
 *     changes; {@code onCommit} fires only when "Apply filters" is clicked.
 *     The parent remounts this component on sheet re-open
 *     (via {@code key={sheetOpen ? "open" : "closed"}}) so a
 *     close-without-apply path discards staged state.
 */
export function FilterSidebarContent({
  mode,
  query,
  onChange,
  onCommit,
  hiddenGroups = [],
  errorCode,
}: FilterSidebarContentProps) {
  const [local, setLocal] = useState<AuctionSearchQuery>(query);
  const [storedQuery, setStoredQuery] = useState<AuctionSearchQuery>(query);

  // In immediate mode, keep local state in sync with the parent's query
  // (e.g. when the URL changes via back/forward). Staged mode also syncs
  // so that the remount-on-open trick seeds fresh state from the current
  // URL-derived query each time. Uses React's recommended derived-state
  // pattern (set during render guarded by reference equality) instead of
  // useEffect+setState, which trips react-hooks/set-state-in-effect.
  if (query !== storedQuery) {
    setStoredQuery(query);
    setLocal(query);
  }

  const update = (partial: Partial<AuctionSearchQuery>) => {
    const next: AuctionSearchQuery = { ...local, ...partial, page: 0 };
    setLocal(next);
    if (mode === "immediate") onChange?.(next);
  };

  const toggleMaturity = (m: MaturityRating) => {
    const current = local.maturity ?? [];
    const next = current.includes(m)
      ? current.filter((x) => x !== m)
      : [...current, m];
    update({ maturity: next.length > 0 ? next : undefined });
  };

  const toggleTier = (t: VerificationTier) => {
    const current = local.verificationTier ?? [];
    const next = current.includes(t)
      ? current.filter((x) => x !== t)
      : [...current, t];
    update({ verificationTier: next.length > 0 ? next : undefined });
  };

  return (
    <div className="flex flex-col gap-5">
      {!hiddenGroups.includes("distance") && (
        <FilterSection title="Distance search">
          <DistanceSearchBlock
            query={local}
            onChange={update}
            errorCode={errorCode}
          />
        </FilterSection>
      )}

      <FilterSection title="Price">
        <div className="flex items-center gap-2">
          <label className="flex flex-1 items-center gap-1 rounded border border-border-subtle bg-bg-subtle px-2 py-1 focus-within:border-border">
            <span className="text-[11px] font-medium text-fg-muted">L$</span>
            <input
              type="number"
              inputMode="numeric"
              min={0}
              placeholder="Min"
              aria-label="Minimum price L$"
              value={local.minPrice ?? ""}
              onChange={(e) =>
                update({ minPrice: parseBound(e.target.value) })
              }
              className="w-full bg-transparent text-sm outline-none"
            />
          </label>
          <span className="text-[11px] font-medium text-fg-muted">-</span>
          <label className="flex flex-1 items-center gap-1 rounded border border-border-subtle bg-bg-subtle px-2 py-1 focus-within:border-border">
            <span className="text-[11px] font-medium text-fg-muted">L$</span>
            <input
              type="number"
              inputMode="numeric"
              min={0}
              placeholder="Max"
              aria-label="Maximum price L$"
              value={local.maxPrice ?? ""}
              onChange={(e) =>
                update({ maxPrice: parseBound(e.target.value) })
              }
              className="w-full bg-transparent text-sm outline-none"
            />
          </label>
        </div>
      </FilterSection>

      <FilterSection title="Size">
        <div className="flex items-center gap-2">
          <label className="flex flex-1 items-center gap-1 rounded border border-border-subtle bg-bg-subtle px-2 py-1 focus-within:border-border">
            <input
              type="number"
              inputMode="numeric"
              min={0}
              placeholder="Min"
              aria-label="Minimum area sqm"
              value={local.minArea ?? ""}
              onChange={(e) =>
                update({ minArea: parseBound(e.target.value) })
              }
              className="w-full bg-transparent text-sm outline-none"
            />
            <span className="text-[11px] font-medium text-fg-muted">m²</span>
          </label>
          <span className="text-[11px] font-medium text-fg-muted">-</span>
          <label className="flex flex-1 items-center gap-1 rounded border border-border-subtle bg-bg-subtle px-2 py-1 focus-within:border-border">
            <input
              type="number"
              inputMode="numeric"
              min={0}
              placeholder="Max"
              aria-label="Maximum area sqm"
              value={local.maxArea ?? ""}
              onChange={(e) =>
                update({ maxArea: parseBound(e.target.value) })
              }
              className="w-full bg-transparent text-sm outline-none"
            />
            <span className="text-[11px] font-medium text-fg-muted">m²</span>
          </label>
        </div>
      </FilterSection>

      <FilterSection title="Maturity">
        {MATURITY_OPTIONS.map((m) => {
          const checked = local.maturity?.includes(m) ?? false;
          return (
            <label key={m} className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={checked}
                onChange={() => toggleMaturity(m)}
                className="size-4 accent-brand"
              />
              <span>{m.charAt(0) + m.slice(1).toLowerCase()}</span>
            </label>
          );
        })}
      </FilterSection>

      <FilterSection title="Parcel tags">
        <TagSelector
          value={local.tags ?? []}
          onChange={(next) =>
            update({ tags: next.length > 0 ? next : undefined })
          }
        />
        <div className="flex items-center gap-3 text-[11px] font-medium text-fg-muted">
          <span>Match</span>
          {(["or", "and"] as TagsMode[]).map((m) => (
            <label key={m} className="flex items-center gap-1">
              <input
                type="radio"
                name="tags-mode"
                checked={(local.tagsMode ?? "or") === m}
                onChange={() => update({ tagsMode: m === "or" ? undefined : m })}
                className="accent-brand"
              />
              <span>{m === "or" ? "Any" : "All"}</span>
            </label>
          ))}
        </div>
      </FilterSection>

      <FilterSection title="Reserve">
        {RESERVE_OPTIONS.map((o) => (
          <label key={o.value} className="flex items-center gap-2 text-sm">
            <input
              type="radio"
              name="reserve-status"
              checked={(local.reserveStatus ?? "all") === o.value}
              onChange={() =>
                update({
                  reserveStatus: o.value === "all" ? undefined : o.value,
                })
              }
              className="accent-brand"
            />
            <span>{o.label}</span>
          </label>
        ))}
      </FilterSection>

      <FilterSection title="Snipe protection">
        {SNIPE_OPTIONS.map((o) => (
          <label key={o.value} className="flex items-center gap-2 text-sm">
            <input
              type="radio"
              name="snipe-protection"
              checked={(local.snipeProtection ?? "any") === o.value}
              onChange={() =>
                update({
                  snipeProtection: o.value === "any" ? undefined : o.value,
                })
              }
              className="accent-brand"
            />
            <span>{o.label}</span>
          </label>
        ))}
      </FilterSection>

      <FilterSection title="Verification tier">
        {TIER_OPTIONS.map((t) => {
          const checked = local.verificationTier?.includes(t.value) ?? false;
          return (
            <label
              key={t.value}
              className="flex items-center gap-2 text-sm"
            >
              <input
                type="checkbox"
                checked={checked}
                onChange={() => toggleTier(t.value)}
                className="size-4 accent-brand"
              />
              <span>{t.label}</span>
            </label>
          );
        })}
      </FilterSection>

      <FilterSection title="Ending within">
        {ENDING_OPTIONS.map((o) => (
          <label
            key={o.label}
            className="flex items-center gap-2 text-sm"
          >
            <input
              type="radio"
              name="ending-within"
              checked={local.endingWithin === o.value}
              onChange={() => update({ endingWithin: o.value })}
              className="accent-brand"
            />
            <span>{o.label}</span>
          </label>
        ))}
      </FilterSection>

      {mode === "staged" && (
        <Button variant="primary" onClick={() => onCommit?.(local)}>
          Apply filters
        </Button>
      )}
    </div>
  );
}
