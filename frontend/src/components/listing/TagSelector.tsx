"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ChevronDown, ChevronUp } from "@/components/ui/icons";
import { cn } from "@/lib/cn";
import { listParcelTagGroups } from "@/lib/api/parcelTags";

export const PARCEL_TAGS_KEY = ["parcel-tags"] as const;

export interface TagSelectorProps {
  value: string[];
  onChange: (next: string[]) => void;
  /** Defaults to the backend AuctionCreateRequest cap of 10. */
  maxSelections?: number;
  disabled?: boolean;
}

/**
 * Categorized chip multi-select. Shared by the Create/Edit listing flow
 * (Task 8) and the Epic 07 browse filters (once built) — that's why the
 * fetched catalogue is cached in React Query with a long staleTime.
 *
 * Categories collapse/expand per user click; the initial render has all
 * categories expanded. Selecting past {@link maxSelections} silently
 * ignores the click — the counter at the bottom tells the seller they've
 * hit the cap.
 */
export function TagSelector({
  value,
  onChange,
  maxSelections = 10,
  disabled = false,
}: TagSelectorProps) {
  const { data, isLoading, isError } = useQuery({
    queryKey: PARCEL_TAGS_KEY,
    queryFn: listParcelTagGroups,
    staleTime: 60 * 60 * 1000,
    gcTime: 60 * 60 * 1000,
  });
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});

  if (isLoading) {
    return (
      <p className="text-body-sm text-on-surface-variant">Loading tags…</p>
    );
  }
  if (isError || !data) {
    return (
      <p className="text-body-sm text-error">
        Couldn&apos;t load tag catalogue. Try again later.
      </p>
    );
  }

  function toggle(code: string) {
    if (disabled) return;
    if (value.includes(code)) {
      onChange(value.filter((c) => c !== code));
      return;
    }
    if (value.length >= maxSelections) return;
    onChange([...value, code]);
  }

  return (
    <div className="flex flex-col gap-3">
      {data.map((group) => {
        const open = !collapsed[group.category];
        return (
          <div
            key={group.category}
            className="rounded-default border border-outline-variant"
          >
            <button
              type="button"
              aria-expanded={open}
              className="flex w-full items-center justify-between px-3 py-2 text-label-lg text-on-surface"
              onClick={() =>
                setCollapsed((s) => ({ ...s, [group.category]: open }))
              }
            >
              <span>{group.category}</span>
              {open ? (
                <ChevronUp className="size-4" aria-hidden="true" />
              ) : (
                <ChevronDown className="size-4" aria-hidden="true" />
              )}
            </button>
            {open && (
              <div className="flex flex-wrap gap-2 border-t border-outline-variant p-3">
                {group.tags.map((tag) => {
                  const selected = value.includes(tag.code);
                  const capped =
                    !selected && value.length >= maxSelections;
                  return (
                    <button
                      key={tag.code}
                      type="button"
                      aria-pressed={selected}
                      disabled={disabled || capped}
                      onClick={() => toggle(tag.code)}
                      className={cn(
                        "rounded-full border px-3 py-1 text-label-md transition-colors",
                        selected
                          ? "border-primary bg-primary text-on-primary"
                          : "border-outline-variant bg-surface-container-low text-on-surface",
                        (disabled || capped) &&
                          "cursor-not-allowed opacity-60",
                      )}
                    >
                      {tag.label}
                    </button>
                  );
                })}
              </div>
            )}
          </div>
        );
      })}
      <p className="text-body-sm text-on-surface-variant">
        {value.length}/{maxSelections} selected
      </p>
    </div>
  );
}
