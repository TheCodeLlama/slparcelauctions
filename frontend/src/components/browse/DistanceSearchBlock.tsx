"use client";
import { useEffect, useId, useState } from "react";
import { RangeSlider } from "@/components/ui/RangeSlider";
import { cn } from "@/lib/cn";
import type { AuctionSearchQuery } from "@/types/search";

export interface DistanceSearchBlockProps {
  query: AuctionSearchQuery;
  onChange: (partial: Partial<AuctionSearchQuery>) => void;
  /**
   * The latest search error code from the parent's fetch. When it matches
   * one of the REGION_* codes the block renders an inline error beneath
   * the text input — no separate validation round-trip.
   */
  errorCode?: string;
  className?: string;
}

const MIN_DISTANCE = 0;
const MAX_DISTANCE = 20;

const REGION_ERROR_MESSAGES: Record<string, string> = {
  REGION_NOT_FOUND: "Couldn't locate that region. Check the spelling.",
  REGION_LOOKUP_UNAVAILABLE:
    "Region lookup is temporarily unavailable. Try again in a moment.",
};

/**
 * Region-name input + optional distance slider. The region value is only
 * committed to the parent query on a deliberate action (input blur or
 * Enter), never on every keystroke. The backend rejects unknown region
 * names with HTTP 400, so committing each partial keystroke ("D", "Da",
 * "Da Boom") used to 400-storm the search and crash /browse; deferring
 * the commit to blur/Enter means the search only runs once the user has
 * finished typing. Clearing the field still commits an empty value
 * (drops the filter) on blur/Enter. Distance slider is disabled until a
 * non-empty region is set — the backend rejects {@code distance} without
 * {@code near_region}.
 *
 * Client-side autocomplete is deferred (see DEFERRED_WORK entry "Region
 * autocomplete for DistanceSearchBlock"). The backend's
 * REGION_NOT_FOUND / REGION_LOOKUP_UNAVAILABLE codes surface inline here
 * when the parent passes them via {@code errorCode}.
 */
export function DistanceSearchBlock({
  query,
  onChange,
  errorCode,
  className,
}: DistanceSearchBlockProps) {
  const regionId = useId();
  const [localRegion, setLocalRegion] = useState(query.nearRegion ?? "");

  // Re-seed local state whenever the parent query changes (e.g. back-button
  // restores a prior URL, "Clear all" resets the query, etc.). External-state
  // sync into a controlled local input — the setState-in-effect warning
  // doesn't apply to this shape.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- seeding local input value from parent-owned query when the URL changes externally (back/forward, Clear all).
    setLocalRegion(query.nearRegion ?? "");
  }, [query.nearRegion]);

  // Commit the typed region to the parent query. Only ever called from a
  // deliberate action (blur or Enter) — never per keystroke — so a partial
  // region name can't 400-storm the backend search.
  const commitRegion = (value: string) => {
    const trimmed = value.trim();
    const next = trimmed === "" ? undefined : trimmed;
    // No-op if the committed value already matches the parent query —
    // avoids a redundant search/URL replace when the user blurs without
    // having changed anything.
    if (next === (query.nearRegion ?? undefined)) return;
    // When the region is cleared, also drop the distance — the backend
    // rejects distance without near_region anyway.
    onChange(next === undefined ? { nearRegion: undefined, distance: undefined } : { nearRegion: next });
  };

  const onInput = (value: string) => {
    // Local-only: typing never commits. The search fires on blur/Enter.
    setLocalRegion(value);
  };

  const onBlur = () => {
    commitRegion(localRegion);
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter") {
      e.preventDefault();
      commitRegion(localRegion);
    }
  };

  const distanceEnabled = Boolean(query.nearRegion);
  const distance = query.distance ?? MAX_DISTANCE;
  const errorMessage = errorCode ? REGION_ERROR_MESSAGES[errorCode] : undefined;

  return (
    <div className={cn("flex flex-col gap-3", className)}>
      <div className="flex flex-col gap-1">
        <label
          htmlFor={regionId}
          className="text-xs font-medium text-fg-muted"
        >
          Region name
        </label>
        <input
          id={regionId}
          type="text"
          value={localRegion}
          onChange={(e) => onInput(e.target.value)}
          onBlur={onBlur}
          onKeyDown={onKeyDown}
          placeholder="e.g. Tula"
          aria-invalid={errorMessage ? true : undefined}
          className="h-10 rounded-lg bg-bg-subtle text-fg placeholder:text-fg-muted px-3 ring-1 ring-transparent focus:bg-surface-raised focus:outline-none focus:ring-2 focus:ring-brand"
        />
        {errorMessage && (
          <p role="alert" className="text-xs text-danger">
            {errorMessage}
          </p>
        )}
      </div>
      <div className="flex flex-col gap-1">
        <div className="flex items-center justify-between text-xs font-medium text-fg-muted">
          <span>Within</span>
          <span>{distance} regions</span>
        </div>
        <div className={cn(!distanceEnabled && "pointer-events-none opacity-50")}>
          <RangeSlider
            min={MIN_DISTANCE}
            max={MAX_DISTANCE}
            step={1}
            value={[0, distance]}
            onChange={([, hi]) =>
              onChange({ distance: hi === MAX_DISTANCE ? undefined : hi })
            }
            ariaLabel={["Minimum distance (regions)", "Maximum distance (regions)"]}
          />
        </div>
      </div>
    </div>
  );
}
