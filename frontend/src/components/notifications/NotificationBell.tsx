"use client";
import { Popover, PopoverButton } from "@headlessui/react";
import { Bell } from "@/components/ui/icons";
import { useUnreadCount } from "@/hooks/notifications/useUnreadCount";
import { useAuth } from "@/lib/auth";
import { NotificationDropdown } from "./NotificationDropdown";
import { cn } from "@/lib/cn";

export function NotificationBell() {
  const { status } = useAuth();
  const unread = useUnreadCount();
  if (status !== "authenticated") return null;

  const count = unread.data?.count ?? 0;
  const display = count > 99 ? "99+" : String(count);

  return (
    <Popover className="relative">
      {({ close }) => (
        <>
          <PopoverButton
            className={cn(
              "relative size-10 rounded-md flex items-center justify-center",
              "text-fg hover:bg-bg-muted transition-colors"
            )}
            aria-label={count > 0 ? `Notifications (${count} unread)` : "Notifications"}
          >
            <Bell className="size-5" />
            {count > 0 && (
              <span
                className="absolute -top-1 -right-1 min-w-[18px] h-[18px] px-1 rounded-full bg-danger text-white text-[10px] font-bold flex items-center justify-center border-2 border-bg"
                aria-hidden
              >
                {display}
              </span>
            )}
          </PopoverButton>
          <NotificationDropdown onClose={close} />
        </>
      )}
    </Popover>
  );
}
