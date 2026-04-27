import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { UserActionsRail } from "./UserActionsRail";
import type { AdminUserDetail } from "@/lib/admin/types";

vi.mock("next/navigation", () => ({
  usePathname: vi.fn(() => "/admin/users/1"),
  useRouter: () => ({
    push: vi.fn(),
    replace: vi.fn(),
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  useSearchParams: vi.fn(() => new URLSearchParams()),
}));

function makeUser(overrides: Partial<AdminUserDetail> = {}): AdminUserDetail {
  return {
    id: 1,
    email: "user@example.com",
    displayName: "Test User",
    slAvatarUuid: "11111111-2222-3333-4444-555555555555",
    slDisplayName: "TestUser Resident",
    role: "USER",
    verified: true,
    verifiedAt: "2026-04-14T12:00:00Z",
    createdAt: "2026-04-01T10:00:00Z",
    completedSales: 5,
    cancelledWithBids: 0,
    escrowExpiredUnfulfilled: 0,
    dismissedReportsCount: 0,
    penaltyBalanceOwed: 0,
    listingSuspensionUntil: null,
    bannedFromListing: false,
    activeBan: null,
    ...overrides,
  };
}

describe("UserActionsRail", () => {
  it("shows Promote button when role is USER", () => {
    const user = makeUser({ role: "USER" });
    renderWithProviders(<UserActionsRail user={user} onRefresh={vi.fn()} />);
    expect(screen.getByTestId("promote-btn")).toBeInTheDocument();
    expect(screen.queryByTestId("demote-btn")).not.toBeInTheDocument();
  });

  it("shows Demote button when role is ADMIN", () => {
    const user = makeUser({ role: "ADMIN" });
    renderWithProviders(<UserActionsRail user={user} onRefresh={vi.fn()} />);
    expect(screen.getByTestId("demote-btn")).toBeInTheDocument();
    expect(screen.queryByTestId("promote-btn")).not.toBeInTheDocument();
  });

  it("shows active ban callout when user has an active ban", () => {
    const user = makeUser({
      activeBan: {
        id: 99,
        banType: "AVATAR",
        reasonText: "Shill bidding detected",
        expiresAt: null,
      },
    });
    renderWithProviders(<UserActionsRail user={user} onRefresh={vi.fn()} />);
    expect(screen.getByTestId("lift-ban-btn")).toBeInTheDocument();
    expect(screen.getByText("Shill bidding detected")).toBeInTheDocument();
  });

  it("does not show active ban callout when no active ban", () => {
    const user = makeUser({ activeBan: null });
    renderWithProviders(<UserActionsRail user={user} onRefresh={vi.fn()} />);
    expect(screen.queryByTestId("lift-ban-btn")).not.toBeInTheDocument();
  });

  it("shows Reset counter button when cancelledWithBids > 0", () => {
    const user = makeUser({ cancelledWithBids: 3 });
    renderWithProviders(<UserActionsRail user={user} onRefresh={vi.fn()} />);
    expect(screen.getByTestId("reset-frivolous-btn")).toBeInTheDocument();
  });

  it("does not show Reset counter button when cancelledWithBids is 0", () => {
    const user = makeUser({ cancelledWithBids: 0 });
    renderWithProviders(<UserActionsRail user={user} onRefresh={vi.fn()} />);
    expect(screen.queryByTestId("reset-frivolous-btn")).not.toBeInTheDocument();
  });

  it("shows Recent IPs button", () => {
    renderWithProviders(<UserActionsRail user={makeUser()} onRefresh={vi.fn()} />);
    expect(screen.getByTestId("recent-ips-btn")).toBeInTheDocument();
  });
});
