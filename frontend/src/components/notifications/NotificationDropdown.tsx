"use client";
import { useState } from "react";
import { PopoverPanel } from "@headlessui/react";
import Link from "next/link";
import { useNotifications } from "@/hooks/notifications/useNotifications";
import { useUnreadCount } from "@/hooks/notifications/useUnreadCount";
import { useMarkAllRead } from "@/hooks/notifications/useMarkAllRead";
import { NotificationDropdownRow } from "./NotificationDropdownRow";
import { FilterChips, type FilterMode } from "./FilterChips";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { Settings } from "@/components/ui/icons";

export interface NotificationDropdownProps {
  onClose: () => void;
}

export function NotificationDropdown({ onClose }: NotificationDropdownProps) {
  const [filter, setFilter] = useState<FilterMode>("all");
  const unreadCount = useUnreadCount();
  const list = useNotifications({
    unreadOnly: filter === "unread",
    size: 10,
  });
  const markAllRead = useMarkAllRead();

  return (
    <PopoverPanel
      anchor={{ to: "bottom end", gap: 8 }}
      className="w-[440px] max-h-[520px] flex flex-col bg-bg border border-border rounded-xl shadow-elevation-3 overflow-hidden z-50"
    >
      <header className="flex items-center justify-between px-4 py-3 border-b border-border-subtle">
        <div>
          <h2 className="text-sm font-semibold text-fg">Notifications</h2>
          <div className="mt-2">
            <FilterChips
              value={filter}
              onChange={setFilter}
              unreadCount={unreadCount.data?.count}
            />
          </div>
        </div>
        <button
          type="button"
          onClick={() => markAllRead.mutate(undefined)}
          className="text-xs font-medium text-brand hover:underline shrink-0 ml-4"
        >
          Mark all read
        </button>
      </header>

      <div className="flex-1 overflow-y-auto">
        {list.isPending ? (
          <div className="p-8 flex justify-center"><LoadingSpinner /></div>
        ) : list.data?.content.length === 0 ? (
          <div className="p-8 text-center text-sm text-fg-muted">
            {filter === "unread" ? "No unread notifications" : "No notifications yet"}
          </div>
        ) : (
          list.data?.content.map((n) => (
            <NotificationDropdownRow key={n.id} notification={n} onClose={onClose} />
          ))
        )}
      </div>

      <footer className="border-t border-border-subtle px-4 py-2 flex items-center justify-between bg-bg-muted">
        <Link
          href="/notifications"
          onClick={onClose}
          className="text-xs font-medium text-brand hover:underline"
        >
          View all notifications
        </Link>
        <Link
          href="/settings/notifications"
          onClick={onClose}
          className="text-fg-muted hover:text-fg"
          aria-label="Notification settings"
        >
          <Settings className="size-4" />
        </Link>
      </footer>
    </PopoverPanel>
  );
}
