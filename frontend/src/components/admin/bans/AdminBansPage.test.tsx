import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { adminHandlers } from "@/test/msw/handlers";
import { AdminBansPage } from "./AdminBansPage";
import type { AdminBanRow } from "@/lib/admin/types";

const mockReplace = vi.fn();

vi.mock("next/navigation", () => ({
  usePathname: vi.fn(() => "/admin/bans"),
  useRouter: () => ({
    push: vi.fn(),
    replace: mockReplace,
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  useSearchParams: vi.fn(() => new URLSearchParams()),
}));

function makeBan(overrides: Partial<AdminBanRow> = {}): AdminBanRow {
  return {
    id: 1,
    banType: "IP",
    ipAddress: "10.0.0.1",
    slAvatarUuid: null,
    avatarLinkedUserId: null,
    avatarLinkedDisplayName: null,
    firstSeenIp: null,
    reasonCategory: "TOS_ABUSE",
    reasonText: "Repeated ToS violations",
    bannedByUserId: 1,
    bannedByDisplayName: "AdminUser",
    expiresAt: null,
    createdAt: "2026-04-01T10:00:00Z",
    liftedAt: null,
    liftedByUserId: null,
    liftedByDisplayName: null,
    liftedReason: null,
    ...overrides,
  };
}

describe("AdminBansPage", () => {
  beforeEach(() => {
    mockReplace.mockReset();
  });

  it("renders rows from MSW seed", async () => {
    server.use(
      adminHandlers.bansListSuccess([
        makeBan({ id: 1, ipAddress: "10.0.0.1" }),
        makeBan({ id: 2, ipAddress: "10.0.0.2" }),
      ])
    );

    renderWithProviders(<AdminBansPage />);

    await waitFor(() =>
      expect(screen.getByTestId("bans-table")).toBeInTheDocument()
    );
    expect(screen.getByTestId("ban-row-1")).toBeInTheDocument();
    expect(screen.getByTestId("ban-row-2")).toBeInTheDocument();
  });

  it("tab toggle updates URL", async () => {
    server.use(adminHandlers.bansListSuccess([]));
    renderWithProviders(<AdminBansPage />);

    await waitFor(() =>
      expect(screen.getByTestId("tab-history")).toBeInTheDocument()
    );

    screen.getByTestId("tab-history").click();

    expect(mockReplace).toHaveBeenCalledWith(
      expect.stringContaining("tab=history"),
      expect.anything()
    );
  });

  it("type filter pill narrows rows client-side", async () => {
    server.use(
      adminHandlers.bansListSuccess([
        makeBan({ id: 1, banType: "IP", ipAddress: "10.0.0.1" }),
        makeBan({ id: 2, banType: "AVATAR", slAvatarUuid: "aaaa-bbbb", ipAddress: null }),
      ])
    );

    const { useSearchParams } = await import("next/navigation");
    vi.mocked(useSearchParams).mockReturnValue(new URLSearchParams("type=IP") as ReturnType<typeof useSearchParams>);

    renderWithProviders(<AdminBansPage />);

    await waitFor(() =>
      expect(screen.getByTestId("bans-table")).toBeInTheDocument()
    );

    expect(screen.getByTestId("ban-row-1")).toBeInTheDocument();
    expect(screen.queryByTestId("ban-row-2")).not.toBeInTheDocument();
  });
});
