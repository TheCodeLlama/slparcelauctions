import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import type { NotificationDto } from "@/lib/notifications/types";
import { NotificationDropdownRow } from "./NotificationDropdownRow";

// ---------------------------------------------------------------------------
// Router mock
// ---------------------------------------------------------------------------
const mockPush = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: mockPush }),
}));

// ---------------------------------------------------------------------------
// useMarkRead mock
// ---------------------------------------------------------------------------
const markReadMutate = vi.fn();
vi.mock("@/hooks/notifications/useMarkRead", () => ({
  useMarkRead: () => ({ mutate: markReadMutate }),
}));

function makeNotification(partial: Partial<NotificationDto> = {}): NotificationDto {
  return {
    id: 1,
    category: "OUTBID",
    group: "bidding",
    title: "You were outbid",
    body: "Someone bid higher.",
    data: { auctionId: 42 },
    read: false,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    ...partial,
  };
}

describe("NotificationDropdownRow", () => {
  beforeEach(() => {
    mockPush.mockClear();
    markReadMutate.mockClear();
  });

  it("clicking the row navigates to the category deeplink and marks the notification read", async () => {
    const n = makeNotification({ id: 5, read: false, data: { auctionId: 42 } });
    const onClose = vi.fn();
    renderWithProviders(<NotificationDropdownRow notification={n} onClose={onClose} />);

    const row = screen.getByRole("button", { name: /You were outbid/ });
    await userEvent.click(row);

    // OUTBID deeplink is /auction/{auctionId}
    expect(mockPush).toHaveBeenCalledWith("/auction/42");
    expect(markReadMutate).toHaveBeenCalledWith(5);
    expect(onClose).toHaveBeenCalled();
  });

  it("clicking the inline action button uses the action href (not the row deeplink)", async () => {
    // OUTBID action href is /auction/{id}#bid-panel, deeplink is /auction/{id}
    const n = makeNotification({ id: 7, read: false, data: { auctionId: 99 } });
    renderWithProviders(<NotificationDropdownRow notification={n} />);

    // The action button text is "Place a new bid →"
    const actionBtn = screen.getByRole("button", { name: /Place a new bid/ });
    await userEvent.click(actionBtn);

    expect(mockPush).toHaveBeenCalledWith("/auction/99#bid-panel");
    // The row deeplink (/auction/99) was NOT called
    expect(mockPush).not.toHaveBeenCalledWith("/auction/99");
  });

  it("unknown category falls back to Bell icon + /notifications deeplink", async () => {
    // Cast to any to force an unmapped category through the fallback
    const n = makeNotification({ category: "UNKNOWN_FUTURE_CATEGORY" as never, data: {} });
    renderWithProviders(<NotificationDropdownRow notification={n} />);

    const row = screen.getByRole("button", { name: /You were outbid/ });
    await userEvent.click(row);

    // categoryConfigOrFallback returns deeplink: () => `/notifications` for unknown
    expect(mockPush).toHaveBeenCalledWith("/notifications");
  });

  it("unread row has aria-label suffixed with ', unread' and renders the unread dot", () => {
    const n = makeNotification({ read: false });
    renderWithProviders(<NotificationDropdownRow notification={n} />);

    const row = screen.getByRole("button", { name: /You were outbid, unread/ });
    expect(row).toBeInTheDocument();
    // The unread dot is a div with a rounded-full bg-brand class
    expect(row.querySelector(".bg-brand.rounded-full")).toBeInTheDocument();
  });

  it("read row has no ', unread' suffix and no unread dot", () => {
    const n = makeNotification({ read: true });
    renderWithProviders(<NotificationDropdownRow notification={n} />);

    const row = screen.getByRole("button", { name: "You were outbid" });
    expect(row).toBeInTheDocument();
    expect(row.querySelector(".bg-brand.rounded-full")).not.toBeInTheDocument();
  });
});
