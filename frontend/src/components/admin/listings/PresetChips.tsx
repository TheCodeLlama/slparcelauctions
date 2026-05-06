"use client";
import type { AdminListingsFilters, AuctionStatus } from "@/lib/admin/types";

export type Preset = {
  key: string;
  label: string;
  statuses: AuctionStatus[];
  sort: AdminListingsFilters["sort"];
};

type Props = {
  presets: Preset[];
  current: AdminListingsFilters;
  onPick: (preset: Preset | null) => void;
};

/**
 * Returns true if the current filter+sort matches the preset exactly.
 * Status comparison is set-equality regardless of order.
 */
function isActive(p: Preset, f: AdminListingsFilters): boolean {
  const a = new Set(p.statuses);
  const b = new Set(f.statuses ?? []);
  if (a.size !== b.size) return false;
  for (const v of a) if (!b.has(v)) return false;
  return f.sort.column === p.sort.column && f.sort.direction === p.sort.direction;
}

export function PresetChips({ presets, current, onPick }: Props) {
  if (presets.length === 0) return null;
  return (
    <div className="flex flex-wrap gap-2" data-testid="preset-chips">
      {presets.map((p) => {
        const active = isActive(p, current);
        return (
          <button
            key={p.key}
            type="button"
            onClick={() => onPick(active ? null : p)}
            data-testid={`preset-${p.key}`}
            aria-pressed={active}
            className={
              "inline-flex items-center px-3 py-1 rounded-full text-[11px] font-medium transition " +
              (active
                ? "bg-brand text-white"
                : "bg-bg-subtle text-fg-muted hover:bg-bg-muted hover:text-fg")
            }
          >
            {p.label}
          </button>
        );
      })}
    </div>
  );
}
