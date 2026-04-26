"use client";

import { useQuery } from "@tanstack/react-query";
import { listNotifications, type ListNotificationsParams } from "@/lib/notifications/api";
import { notificationKeys } from "@/lib/notifications/queryKeys";

export function useNotifications(params: ListNotificationsParams = {}) {
  return useQuery({
    queryKey: notificationKeys.list({
      group: params.group,
      unreadOnly: params.unreadOnly,
      page: params.page,
      size: params.size,
    }),
    queryFn: () => listNotifications(params),
    staleTime: 30_000,
  });
}
