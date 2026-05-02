"use client";
import { useState } from "react";
import { Disclosure, DisclosureButton, DisclosurePanel } from "@headlessui/react";
import Link from "next/link";
import { FilterIcon, Settings } from "@/components/ui/icons";
import { FeedSidebar } from "./FeedSidebar";
import { FeedList } from "./FeedList";
import type { FilterMode } from "../FilterChips";

export function FeedShell() {
  const [filter, setFilter] = useState<FilterMode>("all");

  const group = (filter === "all" || filter === "unread") ? undefined : filter;
  const unreadOnly = filter === "unread";

  return (
    <div className="mx-auto max-w-6xl px-4 py-8">
      <div className="flex items-center justify-between mb-2">
        <h1 className="text-xl font-bold tracking-tight font-display font-bold">Notifications</h1>
        <Link
          href="/settings/notifications"
          className="p-2 text-fg-muted hover:text-fg rounded-md hover:bg-bg-muted"
          aria-label="Notification settings"
        >
          <Settings className="size-5" />
        </Link>
      </div>
      <p className="text-xs text-fg-muted mb-6">
        Activity from your bids, listings, and account.
      </p>

      {/* Mobile filter drawer — below md breakpoint only */}
      <Disclosure as="div" className="md:hidden mb-4">
        <DisclosureButton className="flex items-center gap-2 px-4 py-2 border border-border rounded-md text-sm">
          <FilterIcon className="size-4" />
          Filters
        </DisclosureButton>
        <DisclosurePanel className="mt-2">
          <FeedSidebar value={filter} onChange={setFilter} />
        </DisclosurePanel>
      </Disclosure>

      {/* Desktop two-column layout */}
      <div className="hidden md:grid md:grid-cols-[220px_1fr] gap-6">
        <FeedSidebar value={filter} onChange={setFilter} />
        <FeedList group={group} unreadOnly={unreadOnly} />
      </div>

      {/* Mobile main column (sidebar is in the Disclosure above) */}
      <div className="md:hidden">
        <FeedList group={group} unreadOnly={unreadOnly} />
      </div>
    </div>
  );
}
