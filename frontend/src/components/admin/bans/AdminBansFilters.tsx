"use client";
import { cn } from "@/lib/cn";
import type { BanType } from "@/lib/admin/types";

export type BanListStatus = "active" | "history";
export type BanTypeFilter = BanType | "ALL";

const STATUS_TABS: Array<{ value: BanListStatus; label: string }> = [
  { value: "active", label: "Active" },
  { value: "history", label: "History" },
];

const TYPE_PILLS: Array<{ value: BanTypeFilter; label: string }> = [
  { value: "ALL", label: "All" },
  { value: "IP", label: "IP" },
  { value: "AVATAR", label: "Avatar" },
  { value: "BOTH", label: "Both" },
];

type Props = {
  status: BanListStatus;
  typeFilter: BanTypeFilter;
  onStatusChange: (s: BanListStatus) => void;
  onTypeChange: (t: BanTypeFilter) => void;
};

export function AdminBansFilters({ status, typeFilter, onStatusChange, onTypeChange }: Props) {
  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-2 border-b border-outline-variant">
        {STATUS_TABS.map((tab) => (
          <button
            key={tab.value}
            type="button"
            onClick={() => onStatusChange(tab.value)}
            data-testid={`tab-${tab.value}`}
            className={cn(
              "px-4 py-2 text-label-md font-medium border-b-2 -mb-px transition-colors",
              status === tab.value
                ? "border-primary text-primary"
                : "border-transparent text-on-surface-variant hover:text-on-surface"
            )}
          >
            {tab.label}
          </button>
        ))}
      </div>

      <div className="flex items-center gap-2" role="group" aria-label="Ban type filter">
        {TYPE_PILLS.map((pill) => (
          <button
            key={pill.value}
            type="button"
            onClick={() => onTypeChange(pill.value)}
            data-testid={`type-pill-${pill.value}`}
            className={cn(
              "px-3 py-1.5 rounded-full text-label-sm transition-colors",
              typeFilter === pill.value
                ? "bg-secondary-container text-on-secondary-container font-medium"
                : "bg-surface-container text-on-surface-variant hover:bg-surface-container-high"
            )}
          >
            {pill.label}
          </button>
        ))}
      </div>
    </div>
  );
}
