"use client";

import { useEffect, useRef } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useStompSubscription, useConnectionState } from "@/lib/ws/hooks";
import { useAuth } from "@/lib/auth";
import { useToast } from "@/components/ui/Toast";
import { notificationKeys } from "@/lib/notifications/queryKeys";
import { categoryConfigOrFallback } from "@/lib/notifications/categoryMap";
import type { NotificationsEnvelope, AccountEnvelope, NotificationDto } from "@/lib/notifications/types";
import type { Page } from "@/types/page";

export function useNotificationStream(): void {
  const { status } = useAuth();
  const qc = useQueryClient();
  const toast = useToast();
  const connectionState = useConnectionState();
  const wasReconnectingRef = useRef<boolean>(false);

  const enabled = status === "authenticated";

  useStompSubscription<NotificationsEnvelope>(
    enabled ? "/user/queue/notifications" : "",
    (env) => handleNotificationsEnvelope(env, qc, toast),
  );

  useStompSubscription<AccountEnvelope>(
    enabled ? "/user/queue/account" : "",
    (env) => handleAccountEnvelope(env, qc),
  );

  // On reconnect, invalidate to reconcile any missed WS messages.
  // Mirrors the pattern in AuctionDetailClient: track whether we were ever
  // non-connected, then invalidate on the next "connected" edge.
  useEffect(() => {
    const cs = connectionState.status;
    if (cs === "connected" && wasReconnectingRef.current) {
      qc.invalidateQueries({ queryKey: notificationKeys.list() });
      qc.invalidateQueries({ queryKey: notificationKeys.unreadCount() });
      wasReconnectingRef.current = false;
    }
    if (cs === "reconnecting" || cs === "error") {
      wasReconnectingRef.current = true;
    }
  }, [connectionState.status, qc]);
}

function handleNotificationsEnvelope(
  env: NotificationsEnvelope,
  qc: ReturnType<typeof useQueryClient>,
  toast: ReturnType<typeof useToast>,
): void {
  if (env.type === "NOTIFICATION_UPSERTED") {
    const n = env.notification;
    const config = categoryConfigOrFallback(n.category);

    qc.setQueriesData<Page<NotificationDto>>(
      { queryKey: [...notificationKeys.all, "list"] },
      (data) => {
        if (!data) return data;
        const existingIdx = data.content.findIndex((x) => x.id === n.id);
        if (existingIdx >= 0) {
          const updated = [...data.content];
          updated[existingIdx] = n;
          return { ...data, content: updated };
        }
        return {
          ...data,
          content: [n, ...data.content],
          totalElements: data.totalElements + 1,
        };
      }
    );

    // Only increment unread count on insert, not update.
    if (!env.isUpdate) {
      const prev = qc.getQueryData<{ count: number }>(notificationKeys.unreadCount());
      if (prev) {
        qc.setQueryData(notificationKeys.unreadCount(), { count: prev.count + 1 });
      }
      qc.invalidateQueries({ queryKey: notificationKeys.unreadCount() });
      qc.invalidateQueries({ queryKey: notificationKeys.unreadCountBreakdown() });
    }

    toast.upsert(`notif-${n.id}`, config.toastVariant, {
      title: n.title,
      description: n.body,
      action: config.action
        ? {
            label: config.action.label,
            onClick: () => {
              window.location.href = config.action!.href(n.data);
            },
          }
        : undefined,
    });
    return;
  }

  if (env.type === "READ_STATE_CHANGED") {
    qc.invalidateQueries({ queryKey: notificationKeys.list() });
    qc.invalidateQueries({ queryKey: notificationKeys.unreadCount() });
    qc.invalidateQueries({ queryKey: notificationKeys.unreadCountBreakdown() });
  }
}

function handleAccountEnvelope(
  env: AccountEnvelope,
  qc: ReturnType<typeof useQueryClient>,
): void {
  if (env.type === "PENALTY_CLEARED") {
    qc.invalidateQueries({ queryKey: ["currentUser"] });
  }
}
