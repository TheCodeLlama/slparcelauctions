"use client";

import { useQuery } from "@tanstack/react-query";
import { getUnreadCount } from "@/lib/notifications/api";
import { notificationKeys } from "@/lib/notifications/queryKeys";
import { useCurrentUser } from "@/lib/user";

export function useUnreadCount() {
  const { data: user } = useCurrentUser();
  return useQuery({
    queryKey: notificationKeys.unreadCount(),
    queryFn: () => getUnreadCount(),
    initialData: user ? { count: user.unreadNotificationCount } : undefined,
    staleTime: 60_000,
    enabled: !!user,
  });
}

export function useUnreadCountBreakdown() {
  return useQuery({
    queryKey: notificationKeys.unreadCountBreakdown(),
    queryFn: () => getUnreadCount("group"),
    staleTime: 30_000,
  });
}
