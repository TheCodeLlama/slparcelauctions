"use client";

import { cn } from "@/lib/cn";
import type { AdminRealtyGroupsStatusFilter } from "@/types/realty";

type Props = {
  status: AdminRealtyGroupsStatusFilter;
  onStatusChange: (status: AdminRealtyGroupsStatusFilter) => void;
  search: string;
  onSearchChange: (search: string) => void;
};

const STATUSES: { value: AdminRealtyGroupsStatusFilter; label: string }[] = [
  { value: "active", label: "Active" },
  { value: "dissolved", label: "Dissolved" },
  { value: "all", label: "All" },
];

/**
 * Filter row for `/admin/realty-groups`. Three status chips + a free-text
 * search input bound to the URL (the parent page handles URL writes).
 */
export function AdminRealtyGroupsFilterBar({
  status,
  onStatusChange,
  search,
  onSearchChange,
}: Props) {
  return (
    <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
      <div
        className="flex items-center gap-1"
        role="radiogroup"
        aria-label="Status filter"
      >
        {STATUSES.map((s) => {
          const active = s.value === status;
          return (
            <button
              key={s.value}
              type="button"
              role="radio"
              aria-checked={active}
              onClick={() => onStatusChange(s.value)}
              data-testid={`admin-realty-status-${s.value}`}
              className={cn(
                "px-3 py-1.5 text-xs rounded-lg",
                active
                  ? "bg-brand text-white"
                  : "bg-bg-subtle text-fg hover:bg-bg-muted",
              )}
            >
              {s.label}
            </button>
          );
        })}
      </div>
      <input
        type="search"
        value={search}
        onChange={(e) => onSearchChange(e.target.value)}
        placeholder="Search by name..."
        data-testid="admin-realty-search"
        className="w-full sm:w-64 rounded-lg bg-bg-subtle text-fg placeholder:text-fg-muted px-3 py-2 text-sm ring-1 ring-transparent focus:outline-none focus:ring-brand"
      />
    </div>
  );
}
