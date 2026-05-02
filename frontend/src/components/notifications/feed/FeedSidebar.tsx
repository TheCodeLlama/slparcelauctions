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
    <aside className="w-[220px] shrink-0 bg-bg border border-border rounded-xl py-3 h-fit sticky top-24">
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
      <div className="px-4 py-1.5 text-[11px] font-medium uppercase tracking-wide text-fg-muted font-semibold">
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
        "flex items-center justify-between px-4 py-2 text-xs border-l-[3px]",
        active
          ? "bg-brand-soft/40 text-fg font-semibold border-brand"
          : "text-fg-muted border-transparent hover:bg-bg-muted"
      )}
    >
      <span>{children}</span>
      {count != null && count > 0 && (
        <span className={cn(
          "text-[11px] font-medium",
          active ? "text-brand font-semibold" : "text-fg-muted"
        )}>{count}</span>
      )}
    </button>
  );
}
