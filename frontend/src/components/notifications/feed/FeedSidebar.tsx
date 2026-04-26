"use client";
import type { ReactNode } from "react";
import { useUnreadCountBreakdown } from "@/hooks/notifications/useUnreadCount";
import { cn } from "@/lib/cn";
import type { NotificationGroup } from "@/lib/notifications/types";
import type { FilterMode } from "../FilterChips";

const SIDEBAR_GROUPS: { key: NotificationGroup; label: string }[] = [
  { key: "bidding", label: "Bidding" },
  { key: "auction_result", label: "Auctions" },
  { key: "escrow", label: "Escrow" },
  { key: "listing_status", label: "Listings" },
  { key: "reviews", label: "Reviews" },
];

export interface FeedSidebarProps {
  value: FilterMode;
  onChange: (next: FilterMode) => void;
}

export function FeedSidebar({ value, onChange }: FeedSidebarProps) {
  const breakdown = useUnreadCountBreakdown();
  const total = breakdown.data?.count ?? 0;
  const byGroup = breakdown.data?.byGroup ?? {};

  return (
    <aside className="w-[220px] shrink-0 bg-surface border border-outline rounded-xl py-3 h-fit sticky top-24">
      <SidebarSection label="View">
        <SidebarItem active={value === "all"} onClick={() => onChange("all")} count={total}>
          All
        </SidebarItem>
        <SidebarItem active={value === "unread"} onClick={() => onChange("unread")} count={total}>
          Unread
        </SidebarItem>
      </SidebarSection>
      <SidebarSection label="Group">
        {SIDEBAR_GROUPS.map(({ key, label }) => (
          <SidebarItem
            key={key}
            active={value === key}
            onClick={() => onChange(key)}
            count={byGroup[key] ?? 0}
          >
            {label}
          </SidebarItem>
        ))}
      </SidebarSection>
    </aside>
  );
}

function SidebarSection({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="mb-2">
      <div className="px-4 py-1.5 text-label-sm uppercase tracking-wide text-on-surface-variant font-semibold">
        {label}
      </div>
      <div className="flex flex-col">{children}</div>
    </div>
  );
}

function SidebarItem({ active, onClick, count, children }: {
  active: boolean; onClick: () => void; count?: number; children: ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        "flex items-center justify-between px-4 py-2 text-body-sm border-l-[3px]",
        active
          ? "bg-primary-container/40 text-on-surface font-semibold border-primary"
          : "text-on-surface-variant border-transparent hover:bg-surface-container"
      )}
    >
      <span>{children}</span>
      {count != null && count > 0 && (
        <span className={cn(
          "text-label-sm",
          active ? "text-primary font-semibold" : "text-on-surface-variant"
        )}>{count}</span>
      )}
    </button>
  );
}
