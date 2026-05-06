import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { AdminUsersTable } from "./AdminUsersTable";
import type { AdminUserSummary } from "@/lib/admin/types";

vi.mock("next/navigation", () => ({
  usePathname: vi.fn(() => "/admin/users"),
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

function makeUser(overrides: Partial<AdminUserSummary> = {}): AdminUserSummary {
  return {
    publicId: "11111111-1111-1111-1111-111111111111",
    username: "testuser",
    email: "user@example.com",
    displayName: "Test User",
    slAvatarUuid: null,
    slDisplayName: null,
    role: "USER",
    verified: false,
    hasActiveBan: false,
    completedSales: 0,
    cancelledWithBids: 0,
    createdAt: "2026-04-01T10:00:00Z",
    ...overrides,
  };
}

describe("AdminUsersTable", () => {
  it("shows empty state when no rows", () => {
    renderWithProviders(<AdminUsersTable rows={[]} />);
    expect(screen.getByTestId("empty-state")).toBeInTheDocument();
  });

  it("renders rows for each user", () => {
    const users = [
      makeUser({ publicId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", displayName: "Alice" }),
      makeUser({ publicId: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", displayName: "Bob" }),
      makeUser({ publicId: "cccccccc-cccc-cccc-cccc-cccccccccccc", displayName: "Charlie" }),
    ];
    renderWithProviders(<AdminUsersTable rows={users} />);

    expect(screen.getByTestId("users-table")).toBeInTheDocument();
    expect(screen.getByTestId("user-row-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")).toBeInTheDocument();
    expect(screen.getByTestId("user-row-bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")).toBeInTheDocument();
    expect(screen.getByTestId("user-row-cccccccc-cccc-cccc-cccc-cccccccccccc")).toBeInTheDocument();
  });

  it("shows ADMIN chip for admin users", () => {
    renderWithProviders(<AdminUsersTable rows={[makeUser({ role: "ADMIN" })]} />);
    expect(screen.getByText("ADMIN")).toBeInTheDocument();
  });

  it("shows BANNED chip for users with active ban", () => {
    renderWithProviders(<AdminUsersTable rows={[makeUser({ hasActiveBan: true })]} />);
    expect(screen.getByText("BANNED")).toBeInTheDocument();
  });

  it("shows cancelled-with-bids count in error color when nonzero", () => {
    const publicId = "77777777-7777-7777-7777-777777777777";
    renderWithProviders(
      <AdminUsersTable rows={[makeUser({ publicId, cancelledWithBids: 3 })]} />
    );
    const row = screen.getByTestId(`user-row-${publicId}`);
    expect(row).toBeInTheDocument();
    expect(row.textContent).toContain("3 cancelled");
  });

  it("links to /admin/users/:publicId for each row", () => {
    const publicId = "42424242-4242-4242-4242-424242424242";
    renderWithProviders(<AdminUsersTable rows={[makeUser({ publicId, displayName: "Link Test" })]} />);
    const link = screen.getByTestId(`user-link-${publicId}`);
    expect(link).toHaveAttribute("href", `/admin/users/${publicId}`);
  });
});
