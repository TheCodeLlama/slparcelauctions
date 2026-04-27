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
    id: 1,
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
      makeUser({ id: 1, displayName: "Alice" }),
      makeUser({ id: 2, displayName: "Bob" }),
      makeUser({ id: 3, displayName: "Charlie" }),
    ];
    renderWithProviders(<AdminUsersTable rows={users} />);

    expect(screen.getByTestId("users-table")).toBeInTheDocument();
    expect(screen.getByTestId("user-row-1")).toBeInTheDocument();
    expect(screen.getByTestId("user-row-2")).toBeInTheDocument();
    expect(screen.getByTestId("user-row-3")).toBeInTheDocument();
  });

  it("shows ADMIN chip for admin users", () => {
    renderWithProviders(<AdminUsersTable rows={[makeUser({ id: 5, role: "ADMIN" })]} />);
    expect(screen.getByText("ADMIN")).toBeInTheDocument();
  });

  it("shows BANNED chip for users with active ban", () => {
    renderWithProviders(<AdminUsersTable rows={[makeUser({ id: 6, hasActiveBan: true })]} />);
    expect(screen.getByText("BANNED")).toBeInTheDocument();
  });

  it("shows cancelled-with-bids count in error color when nonzero", () => {
    renderWithProviders(
      <AdminUsersTable rows={[makeUser({ id: 7, cancelledWithBids: 3 })]} />
    );
    const row = screen.getByTestId("user-row-7");
    expect(row).toBeInTheDocument();
    expect(row.textContent).toContain("3 cancelled");
  });

  it("links to /admin/users/:id for each row", () => {
    renderWithProviders(<AdminUsersTable rows={[makeUser({ id: 42, displayName: "Link Test" })]} />);
    const link = screen.getByTestId("user-link-42");
    expect(link).toHaveAttribute("href", "/admin/users/42");
  });
});
