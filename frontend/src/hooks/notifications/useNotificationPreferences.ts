"use client";

import { useQuery } from "@tanstack/react-query";
import { getNotificationPreferences } from "@/lib/notifications/preferencesApi";
import { notificationKeys } from "@/lib/notifications/queryKeys";

export function useNotificationPreferences() {
  return useQuery({
    queryKey: notificationKeys.preferences(),
    queryFn: () => getNotificationPreferences(),
    staleTime: 60_000,
  });
}
