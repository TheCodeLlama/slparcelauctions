import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { authHandlers } from "@/test/msw/handlers";
import type { AuthUser } from "@/lib/auth";
import { UserMenuDropdown } from "./UserMenuDropdown";

const mockPush = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: mockPush }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/",
}));

const mockUserWithDisplayName: AuthUser = {
  id: 1,
  email: "alice@example.com",
  displayName: "Alice Smith",
  slAvatarUuid: null,
  verified: true,
};

const mockUserNullDisplayName: AuthUser = {
  id: 2,
  email: "alice@example.com",
  displayName: null,
  slAvatarUuid: null,
  verified: true,
};

describe("UserMenuDropdown", () => {
  beforeEach(() => {
    mockPush.mockReset();
  });

  it("renders trigger button with displayName when set", () => {
    renderWithProviders(<UserMenuDropdown user={mockUserWithDisplayName} />);
    expect(screen.getByRole("button", { name: /user menu/i })).toBeInTheDocument();
    expect(screen.getByText("Alice Smith")).toBeInTheDocument();
  });

  it("renders trigger label as email local-part when displayName is null", () => {
    renderWithProviders(<UserMenuDropdown user={mockUserNullDisplayName} />);
    expect(screen.getByText("alice")).toBeInTheDocument();
  });

  it("calls logout and redirects to / when Sign Out is clicked", async () => {
    server.use(authHandlers.logoutSuccess());
    server.use(authHandlers.refreshUnauthenticated());
    const user = userEvent.setup();

    renderWithProviders(<UserMenuDropdown user={mockUserWithDisplayName} />);

    await user.click(screen.getByRole("button", { name: /user menu/i }));
    await user.click(screen.getByText(/sign out/i));

    await waitFor(() => {
      expect(mockPush).toHaveBeenCalledWith("/");
    });
  });
});
