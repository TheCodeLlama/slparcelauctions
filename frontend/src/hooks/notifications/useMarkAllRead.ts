"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { markAllRead } from "@/lib/notifications/api";
import { notificationKeys } from "@/lib/notifications/queryKeys";
import type { NotificationGroup } from "@/lib/notifications/types";

export function useMarkAllRead() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (group?: NotificationGroup) => markAllRead(group),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: notificationKeys.all });
    },
  });
}
