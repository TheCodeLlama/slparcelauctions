"use client";
import { cn } from "@/lib/cn";
import type { FraudFlagListStatus } from "@/lib/admin/types";

const STATUS_PILLS: Array<{ value: FraudFlagListStatus; label: string }> = [
  { value: "open", label: "Open" },
  { value: "resolved", label: "Resolved" },
  { value: "all", label: "All" },
];

type Props = {
  status: FraudFlagListStatus;
  onStatusChange: (s: FraudFlagListStatus) => void;
};

export function FraudFlagFilters({ status, onStatusChange }: Props) {
  return (
    <div className="flex items-center gap-2" role="group" aria-label="Flag status filter">
      {STATUS_PILLS.map((pill) => (
        <button
          key={pill.value}
          type="button"
          onClick={() => onStatusChange(pill.value)}
          data-testid={`status-pill-${pill.value}`}
          className={cn(
            "px-3 py-1.5 rounded-full text-[11px] font-medium transition-colors",
            status === pill.value
              ? "bg-info-bg text-info-flat font-medium"
              : "bg-bg-muted text-fg-muted hover:bg-bg-hover"
          )}
        >
          {pill.label}
        </button>
      ))}
    </div>
  );
}
