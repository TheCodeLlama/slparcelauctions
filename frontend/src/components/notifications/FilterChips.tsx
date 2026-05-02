"use client";
import type { ReactNode } from "react";
import { cn } from "@/lib/cn";
import type { NotificationGroup } from "@/lib/notifications/types";

export type FilterMode = "all" | "unread" | NotificationGroup;

export interface FilterChipsProps {
  value: FilterMode;
  onChange: (next: FilterMode) => void;
  unreadCount?: number;
  groupCounts?: Partial<Record<NotificationGroup, number>>;
  showGroups?: boolean;
}

const GROUP_LABELS: Record<NotificationGroup, string> = {
  bidding: "Bidding",
  auction_result: "Auctions",
  escrow: "Escrow",
  listing_status: "Listings",
  reviews: "Reviews",
  realty_group: "Realty",
  marketing: "Marketing",
  system: "System",
};

export function FilterChips({ value, onChange, unreadCount, groupCounts, showGroups }: FilterChipsProps) {
  return (
    <div className="flex flex-wrap gap-2 items-center">
      <Chip active={value === "all"} onClick={() => onChange("all")}>All</Chip>
      <Chip active={value === "unread"} onClick={() => onChange("unread")}>
        Unread{unreadCount != null && ` (${unreadCount})`}
      </Chip>
      {showGroups && Object.entries(GROUP_LABELS)
        .filter(([g]) => g !== "marketing" && g !== "system" && g !== "realty_group")
        .map(([g, label]) => (
          <Chip key={g} active={value === g} onClick={() => onChange(g as NotificationGroup)}>
            {label}
            {groupCounts?.[g as NotificationGroup] != null && ` (${groupCounts[g as NotificationGroup]})`}
          </Chip>
        ))}
    </div>
  );
}

function Chip({ active, onClick, children }: { active: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        "px-3 py-1 rounded-full text-xs font-medium border transition-colors",
        active
          ? "bg-brand text-white border-brand"
          : "bg-bg text-fg border-border hover:bg-bg-muted"
      )}
    >
      {children}
    </button>
  );
}
