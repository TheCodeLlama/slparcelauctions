"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { putNotificationPreferences } from "@/lib/notifications/preferencesApi";
import { notificationKeys } from "@/lib/notifications/queryKeys";
import type { PreferencesDto } from "@/lib/notifications/preferencesTypes";
import { useToast } from "@/components/ui/Toast";

export function useUpdateNotificationPreferences() {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: (payload: PreferencesDto) => putNotificationPreferences(payload),
    onMutate: async (payload) => {
      await qc.cancelQueries({ queryKey: notificationKeys.preferences() });
      const prev = qc.getQueryData<PreferencesDto>(notificationKeys.preferences());
      qc.setQueryData(notificationKeys.preferences(), payload);
      return { prev };
    },
    onError: (_err, _vars, ctx) => {
      if (ctx?.prev) {
        qc.setQueryData(notificationKeys.preferences(), ctx.prev);
      }
      toast.error("Couldn't save preferences");
    },
    onSettled: () => {
      qc.invalidateQueries({ queryKey: notificationKeys.preferences() });
      qc.invalidateQueries({ queryKey: ["currentUser"] });
    },
  });
}
