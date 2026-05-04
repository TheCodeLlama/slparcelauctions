import { api } from "@/lib/api";
import type { Page } from "@/types/page";
import type { NotificationDto, NotificationGroup, UnreadCountResponse } from "./types";

export interface ListNotificationsParams {
  group?: NotificationGroup;
  unreadOnly?: boolean;
  page?: number;
  size?: number;
}

export async function listNotifications(
  params: ListNotificationsParams = {}
): Promise<Page<NotificationDto>> {
  const search = new URLSearchParams();
  if (params.group) search.set("group", params.group.toUpperCase());
  if (params.unreadOnly) search.set("unreadOnly", "true");
  if (params.page !== undefined) search.set("page", String(params.page));
  if (params.size !== undefined) search.set("size", String(params.size));
  const query = search.toString();
  return api.get<Page<NotificationDto>>(
    `/api/v1/notifications${query ? "?" + query : ""}`
  );
}

export async function getUnreadCount(breakdown?: "group"): Promise<UnreadCountResponse> {
  const query = breakdown ? "?breakdown=group" : "";
  return api.get<UnreadCountResponse>(`/api/v1/notifications/unread-count${query}`);
}

export async function markRead(publicId: string): Promise<void> {
  await api.put(`/api/v1/notifications/${publicId}/read`);
}

export async function markAllRead(group?: NotificationGroup): Promise<{ markedRead: number }> {
  const query = group ? "?group=" + group.toUpperCase() : "";
  return api.put<{ markedRead: number }>(`/api/v1/notifications/read-all${query}`);
}
