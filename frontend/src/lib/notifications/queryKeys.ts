import type { NotificationGroup } from "./types";

type ListFilters = {
  group?: NotificationGroup;
  unreadOnly?: boolean;
  page?: number;
  size?: number;
};

export const notificationKeys = {
  all: ["notifications"] as const,
  list: (filters?: ListFilters) =>
    [...notificationKeys.all, "list", filters ?? {}] as const,
  unreadCount: () => [...notificationKeys.all, "unreadCount"] as const,
  unreadCountBreakdown: () =>
    [...notificationKeys.all, "unreadCount", "breakdown"] as const,
  preferences: () => [...notificationKeys.all, "preferences"] as const,
};
