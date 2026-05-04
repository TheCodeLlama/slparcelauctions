"use client";
import { useState } from "react";
import { useNotifications } from "@/hooks/notifications/useNotifications";
import { useMarkAllRead } from "@/hooks/notifications/useMarkAllRead";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { Pagination } from "@/components/ui/Pagination";
import { FeedRow } from "./FeedRow";
import type { NotificationGroup } from "@/lib/notifications/types";

export interface FeedListProps {
  group?: NotificationGroup;
  unreadOnly: boolean;
}

export function FeedList({ group, unreadOnly }: FeedListProps) {
  const [page, setPage] = useState(0);
  const list = useNotifications({ group, unreadOnly, page, size: 20 });
  const markAllRead = useMarkAllRead();

  const markLabel = group
    ? `Mark ${group.replace(/_/g, " ")} read`
    : "Mark all read";

  return (
    <div className="flex-1">
      <div className="flex justify-end mb-3">
        <button
          type="button"
          onClick={() => markAllRead.mutate(group)}
          className="text-xs font-medium text-brand hover:underline"
        >
          {markLabel}
        </button>
      </div>

      <div className="bg-bg border border-border rounded-xl overflow-hidden">
        {list.isPending ? (
          <div className="p-12 flex justify-center"><LoadingSpinner /></div>
        ) : list.data?.content.length === 0 ? (
          <div className="p-12 text-center text-sm text-fg-muted">
            {unreadOnly ? "No unread notifications in this view." : "No notifications yet."}
          </div>
        ) : (
          list.data?.content.map((n) => <FeedRow key={n.publicId} notification={n} />)
        )}
      </div>

      {list.data && list.data.totalPages > 1 && (
        <div className="mt-4 flex justify-center">
          <Pagination
            page={page}
            totalPages={list.data.totalPages}
            onPageChange={setPage}
          />
        </div>
      )}
    </div>
  );
}
