import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { Popover, PopoverButton } from "@headlessui/react";
import { server } from "@/test/msw/server";
import { notificationHandlers, clearNotifications } from "@/test/msw/handlers";
import type { NotificationDto } from "@/lib/notifications/types";
import type { Page } from "@/types/page";
import { NotificationDropdown } from "./NotificationDropdown";

// PopoverPanel requires a parent Popover — wrap with a button to open it
function DropdownWrapper({ onClose }: { onClose?: () => void }) {
  return (
    <Popover>
      {({ close }) => (
        <>
          <PopoverButton data-testid="open-btn">Open</PopoverButton>
          <NotificationDropdown onClose={onClose ?? close} />
        </>
      )}
    </Popover>
  );
}

async function openDropdown() {
  await userEvent.click(screen.getByTestId("open-btn"));
}

// ---------------------------------------------------------------------------
// Mocks
// ---------------------------------------------------------------------------

const markAllReadMutate = vi.fn();
vi.mock("@/hooks/notifications/useMarkAllRead", () => ({
  useMarkAllRead: () => ({ mutate: markAllReadMutate, isPending: false }),
}));

const unreadCountData = vi.hoisted(() => ({ count: 0 }));
vi.mock("@/hooks/notifications/useUnreadCount", () => ({
  useUnreadCount: () => ({ data: { count: unreadCountData.count }, isPending: false }),
}));

type NotifParams = { unreadOnly?: boolean; size?: number };
const notifParamsCapture = vi.hoisted(() => ({ value: {} as NotifParams }));
const notifListData = vi.hoisted(() => ({ content: [] as NotificationDto[], isPending: false }));

vi.mock("@/hooks/notifications/useNotifications", () => ({
  useNotifications: (params: NotifParams) => {
    notifParamsCapture.value = params;
    const page: Page<NotificationDto> = {
      content: notifListData.content,
      totalElements: notifListData.content.length,
      totalPages: 1,
      number: 0,
      size: 20,
    };
    return { data: page, isPending: notifListData.isPending };
  },
}));

function makeNotification(partial: Partial<NotificationDto> = {}): NotificationDto {
  return {
    id: Math.floor(Math.random() * 10000),
    category: "OUTBID",
    group: "bidding",
    title: "You were outbid",
    body: "Someone bid higher.",
    data: { auctionId: 10 },
    read: false,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    ...partial,
  };
}

describe("NotificationDropdown", () => {
  beforeEach(() => {
    clearNotifications();
    markAllReadMutate.mockClear();
    unreadCountData.count = 0;
    notifListData.content = [];
    notifListData.isPending = false;
    notifParamsCapture.value = {};
    server.use(...notificationHandlers);
  });

  it("renders 10 latest notifications after opening", async () => {
    const notifications = Array.from({ length: 10 }, (_, i) =>
      makeNotification({ id: i + 1, title: `Notification ${i + 1}` })
    );
    notifListData.content = notifications;

    renderWithProviders(<DropdownWrapper />);
    await openDropdown();

    for (let i = 1; i <= 10; i++) {
      expect(screen.getByText(`Notification ${i}`)).toBeInTheDocument();
    }
  });

  it("Unread filter chip passes unreadOnly=true to useNotifications", async () => {
    unreadCountData.count = 2;
    renderWithProviders(<DropdownWrapper />);
    await openDropdown();

    const unreadChip = screen.getByRole("button", { name: /Unread/ });
    await userEvent.click(unreadChip);

    await waitFor(() => {
      expect(notifParamsCapture.value.unreadOnly).toBe(true);
    });
  });

  it("'Mark all read' button calls the mutation", async () => {
    renderWithProviders(<DropdownWrapper />);
    await openDropdown();

    const btn = screen.getByRole("button", { name: "Mark all read" });
    await userEvent.click(btn);

    expect(markAllReadMutate).toHaveBeenCalledTimes(1);
    expect(markAllReadMutate).toHaveBeenCalledWith(undefined);
  });

  it("renders a 'View all notifications' link pointing to /notifications", async () => {
    renderWithProviders(<DropdownWrapper />);
    await openDropdown();

    const link = screen.getByRole("link", { name: "View all notifications" });
    expect(link).toHaveAttribute("href", "/notifications");
  });

  it("shows empty state message when there are no notifications", async () => {
    notifListData.content = [];

    renderWithProviders(<DropdownWrapper />);
    await openDropdown();

    expect(screen.getByText("No notifications yet")).toBeInTheDocument();
  });

  it("shows 'No unread notifications' message when filtered to unread and list is empty", async () => {
    notifListData.content = [];
    renderWithProviders(<DropdownWrapper />);
    await openDropdown();

    const unreadChip = screen.getByRole("button", { name: /Unread/ });
    await userEvent.click(unreadChip);

    await waitFor(() => {
      expect(screen.getByText("No unread notifications")).toBeInTheDocument();
    });
  });
});
