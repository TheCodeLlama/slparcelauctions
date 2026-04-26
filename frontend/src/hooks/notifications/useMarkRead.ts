"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { markRead } from "@/lib/notifications/api";
import { notificationKeys } from "@/lib/notifications/queryKeys";
import type { Page } from "@/types/page";
import type { NotificationDto } from "@/lib/notifications/types";

export function useMarkRead() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => markRead(id),
    onMutate: async (id) => {
      await qc.cancelQueries({ queryKey: notificationKeys.all });
      const prevCount = qc.getQueryData<{ count: number }>(notificationKeys.unreadCount());
      qc.setQueriesData<Page<NotificationDto>>(
        { queryKey: [...notificationKeys.all, "list"] },
        (data) =>
          data
            ? {
                ...data,
                content: data.content.map((n) =>
                  n.id === id ? { ...n, read: true } : n
                ),
              }
            : data
      );
      if (prevCount) {
        qc.setQueryData(notificationKeys.unreadCount(), {
          count: Math.max(0, prevCount.count - 1),
        });
      }
      return { prevCount };
    },
    onError: (_err, _id, ctx) => {
      if (ctx?.prevCount) {
        qc.setQueryData(notificationKeys.unreadCount(), ctx.prevCount);
      }
      qc.invalidateQueries({ queryKey: notificationKeys.all });
    },
  });
}
