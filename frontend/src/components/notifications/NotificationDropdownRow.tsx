"use client";
import type { MouseEvent } from "react";
import { useRouter } from "next/navigation";
import { categoryConfigOrFallback } from "@/lib/notifications/categoryMap";
import { useMarkRead } from "@/hooks/notifications/useMarkRead";
import { cn } from "@/lib/cn";
import type { NotificationDto } from "@/lib/notifications/types";
import { formatRelativeTime } from "@/lib/time/relativeTime";

export interface NotificationDropdownRowProps {
  notification: NotificationDto;
  onClose?: () => void;
  variant?: "dropdown" | "feed";
}

export function NotificationDropdownRow({ notification: n, onClose, variant = "dropdown" }: NotificationDropdownRowProps) {
  const router = useRouter();
  const config = categoryConfigOrFallback(n.category);
  const Icon = config.icon;
  const markRead = useMarkRead();

  const handleClickRow = () => {
    if (!n.read) markRead.mutate(n.id);
    const href = config.deeplink(n.data as never);
    router.push(href);
    onClose?.();
  };

  const handleClickAction = (e: MouseEvent) => {
    e.stopPropagation();
    if (!config.action) return;
    if (!n.read) markRead.mutate(n.id);
    router.push(config.action.href(n.data as never));
    onClose?.();
  };

  return (
    <button
      type="button"
      onClick={handleClickRow}
      className={cn(
        "w-full flex gap-3 text-left transition-colors",
        variant === "dropdown" ? "px-4 py-3" : "px-4 py-4",
        !n.read ? "bg-primary-container/40 hover:bg-primary-container/60" : "hover:bg-surface-container"
      )}
      aria-label={`${n.title}${n.read ? "" : ", unread"}`}
    >
      <div className={cn(
        "shrink-0 size-8 rounded-md flex items-center justify-center",
        config.iconBgClass
      )}>
        <Icon className="size-4" />
      </div>
      <div className="flex-1 min-w-0">
        <div className="text-body-sm font-semibold text-on-surface truncate">{n.title}</div>
        <div className={cn(
          "text-body-xs text-on-surface-variant",
          variant === "dropdown" ? "line-clamp-2" : "line-clamp-3"
        )}>{n.body}</div>
        <div className="text-label-sm text-on-surface-variant mt-1">
          {formatRelativeTime(n.updatedAt)}
        </div>
        {config.action && (
          <button
            type="button"
            onClick={handleClickAction}
            className="mt-2 px-3 py-1 rounded-md text-label-md border border-outline text-primary hover:bg-primary-container/40"
          >
            {config.action.label} →
          </button>
        )}
      </div>
      {!n.read && <div className="shrink-0 mt-2 size-2 rounded-full bg-primary" />}
    </button>
  );
}
