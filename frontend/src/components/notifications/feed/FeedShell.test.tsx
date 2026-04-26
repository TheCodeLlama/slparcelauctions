import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import type { NotificationDto } from "@/lib/notifications/types";
import type { Page } from "@/types/page";
import { FeedShell } from "./FeedShell";

// ---------------------------------------------------------------------------
// Mocks
// ---------------------------------------------------------------------------

const markAllReadMutate = vi.fn();
vi.mock("@/hooks/notifications/useMarkAllRead", () => ({
  useMarkAllRead: () => ({ mutate: markAllReadMutate, isPending: false }),
}));

vi.mock("@/hooks/notifications/useUnreadCount", () => ({
  useUnreadCountBreakdown: () => ({
    data: { count: 2, byGroup: { bidding: 2 } },
    isPending: false,
  }),
}));

type NotifParams = { group?: string; unreadOnly?: boolean; page?: number };
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

// Headless UI Disclosure requires a DOM that handles focus — mock router is not needed
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn() }),
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

describe("FeedShell", () => {
  beforeEach(() => {
    markAllReadMutate.mockClear();
    notifListData.content = [];
    notifListData.isPending = false;
    notifParamsCapture.value = {};
  });

  it("renders desktop grid with sidebar and main column", () => {
    renderWithProviders(<FeedShell />);

    const grid = document.querySelector(".md\\:grid");
    expect(grid).toBeInTheDocument();
    // The desktop sidebar lives inside the grid; the mobile drawer is md:hidden
    const mobileTrigger = document.querySelector(".md\\:hidden");
    expect(mobileTrigger).toBeInTheDocument();
  });

  it("mobile filter drawer trigger button is rendered", () => {
    renderWithProviders(<FeedShell />);

    const filterBtn = screen.getByRole("button", { name: /Filters/i });
    expect(filterBtn).toBeInTheDocument();
  });

  it("clicking sidebar group item updates FeedList group param", async () => {
    notifListData.content = [makeNotification({ group: "escrow", category: "ESCROW_FUNDED" as never })];
    renderWithProviders(<FeedShell />);

    // The desktop sidebar has "Escrow" button — there are two FeedSidebar instances (desktop + mobile drawer)
    const escrowBtns = screen.getAllByRole("button", { name: /Escrow/i });
    // Click the first one (desktop sidebar is rendered in the grid)
    await userEvent.click(escrowBtns[0]);

    await waitFor(() => {
      expect(notifParamsCapture.value.group).toBe("escrow");
    });
  });

  it("clicking Unread sidebar item sets unreadOnly=true on FeedList", async () => {
    renderWithProviders(<FeedShell />);

    const unreadBtns = screen.getAllByRole("button", { name: /Unread/i });
    await userEvent.click(unreadBtns[0]);

    await waitFor(() => {
      expect(notifParamsCapture.value.unreadOnly).toBe(true);
    });
  });
});
