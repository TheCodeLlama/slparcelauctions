"use client";
import { useEffect, useId, useRef, useState } from "react";
import { RangeSlider } from "@/components/ui/RangeSlider";
import { useRegionSuggest } from "@/hooks/useRegionSuggest";
import { cn } from "@/lib/cn";
import type { AuctionSearchQuery } from "@/types/search";

export interface DistanceSearchBlockProps {
  query: AuctionSearchQuery;
  onChange: (partial: Partial<AuctionSearchQuery>) => void;
  /**
   * The latest search error code from the parent's fetch. When it matches
   * one of the REGION_* codes the block renders an inline error beneath
   * the text input — no separate validation round-trip. Still useful for
   * a direct-URL {@code ?near_region=junk} that bypasses the picker.
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
 * Region-name autocomplete + optional distance slider. As the user
 * types, a debounced call to the region-only suggest endpoint
 * (resolvable distance anchors only) fills an accessible
 * combobox/listbox. The region value is committed to the parent query
 * ONLY on an explicit suggestion-select (click or keyboard) — never on
 * a raw typed string. Every committed value is therefore a verbatim
 * name from the {@code regions} table, so the backend never 400s the
 * search. Blurring with un-selected text reverts the input to the last
 * committed region (no doomed search fires). Clearing the field still
 * drops the filter on blur. Distance slider is disabled until a region
 * is set — the backend rejects {@code distance} without
 * {@code near_region}.
 *
 * The backend's REGION_NOT_FOUND / REGION_LOOKUP_UNAVAILABLE codes
 * still surface inline here when the parent passes {@code errorCode}
 * (e.g. a hand-edited {@code ?near_region=} URL that skipped the
 * picker).
 */
export function DistanceSearchBlock({
  query,
  onChange,
  errorCode,
  className,
}: DistanceSearchBlockProps) {
  const regionId = useId();
  const listboxId = useId();
  const containerRef = useRef<HTMLDivElement>(null);
  const [localRegion, setLocalRegion] = useState(query.nearRegion ?? "");
  // The query string that drives the suggest fetch. Decoupled from
  // localRegion so selecting a suggestion (which sets localRegion) does
  // not immediately re-open the listbox with a fresh fetch.
  const [searchTerm, setSearchTerm] = useState("");
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);

  const { data, isFetching } = useRegionSuggest(searchTerm);
  const suggestions = data?.regions ?? [];

  // Re-seed local state whenever the parent query changes (back/forward
  // restores a prior URL, "Clear all" resets, etc.). External-state sync
  // into a controlled input — the setState-in-effect warning doesn't
  // apply to this shape.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- seeding local input value from parent-owned query when the URL changes externally (back/forward, Clear all).
    setLocalRegion(query.nearRegion ?? "");
  }, [query.nearRegion]);

  useEffect(() => {
    function handlePointerDown(e: MouseEvent) {
      if (
        containerRef.current &&
        !containerRef.current.contains(e.target as Node)
      ) {
        setOpen(false);
      }
    }
    document.addEventListener("mousedown", handlePointerDown);
    return () => document.removeEventListener("mousedown", handlePointerDown);
  }, []);

  const listboxOpen = open && searchTerm.trim().length >= 2 && suggestions.length > 0;

  // Commit a resolvable region (or clear). next === undefined drops the
  // filter (and distance — the backend rejects distance without
  // near_region). No-op if it already matches the parent query.
  const commit = (value: string | undefined) => {
    const next = value && value.trim() !== "" ? value : undefined;
    if (next === (query.nearRegion ?? undefined)) return;
    onChange(
      next === undefined
        ? { nearRegion: undefined, distance: undefined }
        : { nearRegion: next },
    );
  };

  const selectSuggestion = (name: string) => {
    setLocalRegion(name);
    setSearchTerm("");
    setOpen(false);
    setActiveIndex(-1);
    commit(name);
  };

  const onInput = (value: string) => {
    // Local-only while typing. Never commits a raw typed string —
    // committing only happens on explicit suggestion-select.
    setLocalRegion(value);
    setSearchTerm(value);
    setActiveIndex(-1);
    setOpen(true);
  };

  const onBlur = () => {
    // Cleared input drops the filter. Otherwise an un-selected typed
    // value reverts to the last committed region — never fires a
    // doomed (unresolvable) search.
    if (localRegion.trim() === "") {
      setSearchTerm("");
      commit(undefined);
      return;
    }
    setLocalRegion(query.nearRegion ?? "");
    setSearchTerm("");
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      if (!listboxOpen) {
        setOpen(true);
        return;
      }
      setActiveIndex((i) => (i + 1) % suggestions.length);
      return;
    }
    if (e.key === "ArrowUp") {
      e.preventDefault();
      if (!listboxOpen) return;
      setActiveIndex((i) =>
        i <= 0 ? suggestions.length - 1 : i - 1,
      );
      return;
    }
    if (e.key === "Enter") {
      e.preventDefault();
      if (listboxOpen && activeIndex >= 0 && suggestions[activeIndex]) {
        selectSuggestion(suggestions[activeIndex].name);
      }
      // Enter with no active suggestion is a deliberate no-op: a raw
      // typed value is never committed (it could be unresolvable).
      return;
    }
    if (e.key === "Escape") {
      setOpen(false);
      setActiveIndex(-1);
    }
  };

  const distanceEnabled = Boolean(query.nearRegion);
  const distance = query.distance ?? MAX_DISTANCE;
  const errorMessage = errorCode ? REGION_ERROR_MESSAGES[errorCode] : undefined;
  const activeOptionId =
    listboxOpen && activeIndex >= 0
      ? `${listboxId}-opt-${activeIndex}`
      : undefined;

  return (
    <div className={cn("flex flex-col gap-3", className)}>
      <div className="flex flex-col gap-1">
        <label
          htmlFor={regionId}
          className="text-xs font-medium text-fg-muted"
        >
          Region name
        </label>
        <div ref={containerRef} className="relative">
          <input
            id={regionId}
            type="text"
            role="combobox"
            aria-expanded={listboxOpen}
            aria-controls={listboxId}
            aria-autocomplete="list"
            aria-activedescendant={activeOptionId}
            autoComplete="off"
            value={localRegion}
            onChange={(e) => onInput(e.target.value)}
            onFocus={() => setOpen(true)}
            onBlur={onBlur}
            onKeyDown={onKeyDown}
            placeholder="e.g. Tula"
            aria-invalid={errorMessage ? true : undefined}
            className="h-10 w-full rounded-lg bg-bg-subtle text-fg placeholder:text-fg-muted px-3 ring-1 ring-transparent focus:bg-surface-raised focus:outline-none focus:ring-2 focus:ring-brand"
          />
          {isFetching && searchTerm.trim().length >= 2 && (
            <span
              aria-hidden="true"
              className="absolute right-3 top-1/2 -translate-y-1/2 text-[11px] text-fg-muted"
            >
              ...
            </span>
          )}
          {listboxOpen && (
            <ul
              id={listboxId}
              role="listbox"
              aria-label="Region suggestions"
              className="absolute z-50 left-0 right-0 mt-1 max-h-60 overflow-auto rounded-lg bg-bg-subtle border border-border-subtle shadow-md"
            >
              {suggestions.map((region, i) => (
                <li
                  key={region.name}
                  id={`${listboxId}-opt-${i}`}
                  role="option"
                  aria-selected={i === activeIndex}
                  // onMouseDown (not onClick) so the selection lands
                  // before the input's blur reverts the value.
                  onMouseDown={(e) => {
                    e.preventDefault();
                    selectSuggestion(region.name);
                  }}
                  onMouseEnter={() => setActiveIndex(i)}
                  className={cn(
                    "cursor-pointer px-3 py-2 text-sm text-fg transition-colors",
                    i === activeIndex ? "bg-bg-muted" : "hover:bg-bg-muted",
                  )}
                >
                  {region.name}
                </li>
              ))}
            </ul>
          )}
        </div>
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
