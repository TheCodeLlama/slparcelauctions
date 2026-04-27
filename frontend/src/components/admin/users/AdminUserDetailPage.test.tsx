import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { adminHandlers } from "@/test/msw/handlers";
import { AdminUserDetailPage } from "./AdminUserDetailPage";
import type { AdminUserDetail } from "@/lib/admin/types";

vi.mock("next/navigation", () => ({
  usePathname: vi.fn(() => "/admin/users/10"),
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
    id: 10,
    email: "detail@example.com",
    displayName: "Detail User",
    slAvatarUuid: "aaaabbbb-cccc-dddd-eeee-ffffaaaabbbb",
    slDisplayName: "DetailUser Resident",
    role: "USER",
    verified: true,
    verifiedAt: "2026-04-14T12:00:00Z",
    createdAt: "2026-04-01T10:00:00Z",
    completedSales: 3,
    cancelledWithBids: 1,
    escrowExpiredUnfulfilled: 0,
    dismissedReportsCount: 0,
    penaltyBalanceOwed: 500,
    listingSuspensionUntil: null,
    bannedFromListing: false,
    activeBan: null,
    ...overrides,
  };
}

describe("AdminUserDetailPage", () => {
  it("renders loading state before data arrives", () => {
    // Don't register the handler — let it hang
    renderWithProviders(<AdminUserDetailPage userId={10} />);
    // Should show loading indicator initially since query hasn't resolved
    expect(screen.getByTestId("user-detail-loading")).toBeInTheDocument();
  });

  it("renders profile header, stats, and tabs after data loads", async () => {
    const user = makeUser();
    server.use(adminHandlers.userDetailSuccess(user));
    server.use(adminHandlers.userListingsSuccess(10, []));

    renderWithProviders(<AdminUserDetailPage userId={10} />);

    await waitFor(() =>
      expect(screen.getByTestId("user-detail-page")).toBeInTheDocument()
    );

    expect(screen.getByTestId("user-profile-header")).toBeInTheDocument();
    expect(screen.getByTestId("user-stats-cards")).toBeInTheDocument();
    expect(screen.getByTestId("user-tabs-nav")).toBeInTheDocument();
    expect(screen.getByTestId("user-actions-rail")).toBeInTheDocument();
  });

  it("shows display name in profile header", async () => {
    server.use(adminHandlers.userDetailSuccess(makeUser({ displayName: "Specific Name" })));
    server.use(adminHandlers.userListingsSuccess(10, []));

    renderWithProviders(<AdminUserDetailPage userId={10} />);

    await waitFor(() =>
      expect(screen.getByText("Specific Name")).toBeInTheDocument()
    );
  });

  it("shows ADMIN chip when user is admin", async () => {
    server.use(adminHandlers.userDetailSuccess(makeUser({ role: "ADMIN" })));
    server.use(adminHandlers.userListingsSuccess(10, []));

    renderWithProviders(<AdminUserDetailPage userId={10} />);

    await waitFor(() =>
      expect(screen.getByTestId("admin-chip")).toBeInTheDocument()
    );
  });

  it("shows BANNED chip when user has active ban", async () => {
    server.use(
      adminHandlers.userDetailSuccess(
        makeUser({
          activeBan: {
            id: 1,
            banType: "AVATAR",
            reasonText: "Test ban",
            expiresAt: null,
          },
        })
      )
    );
    server.use(adminHandlers.userListingsSuccess(10, []));

    renderWithProviders(<AdminUserDetailPage userId={10} />);

    await waitFor(() =>
      expect(screen.getByTestId("banned-chip")).toBeInTheDocument()
    );
  });
});
