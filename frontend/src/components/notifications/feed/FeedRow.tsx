"use client";
import { NotificationDropdownRow } from "../NotificationDropdownRow";
import type { NotificationDto } from "@/lib/notifications/types";

export function FeedRow({ notification }: { notification: NotificationDto }) {
  return <NotificationDropdownRow notification={notification} variant="feed" />;
}
