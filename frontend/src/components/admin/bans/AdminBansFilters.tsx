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
      <div className="flex items-center gap-2 border-b border-border-subtle">
        {STATUS_TABS.map((tab) => (
          <button
            key={tab.value}
            type="button"
            onClick={() => onStatusChange(tab.value)}
            data-testid={`tab-${tab.value}`}
            className={cn(
              "px-4 py-2 text-xs font-medium border-b-2 -mb-px transition-colors",
              status === tab.value
                ? "border-brand text-brand"
                : "border-transparent text-fg-muted hover:text-fg"
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
              "px-3 py-1.5 rounded-full text-[11px] font-medium transition-colors",
              typeFilter === pill.value
                ? "bg-info-bg text-info font-medium"
                : "bg-bg-muted text-fg-muted hover:bg-bg-hover"
            )}
          >
            {pill.label}
          </button>
        ))}
      </div>
    </div>
  );
}
