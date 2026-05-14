import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, userEvent, waitFor, within } from "@/test/render";
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

// The actual hook is created by Task 23 in parallel. We mock it so this test
// has no hard dependency on the hook's commit landing first.
const mockUseMyGroupInvitations = vi.fn();
vi.mock("@/hooks/realty/useMyGroupInvitations", () => ({
  useMyGroupInvitations: () => mockUseMyGroupInvitations(),
}));

const mockUserWithDisplayName: AuthUser = {
  publicId: "00000000-0000-0000-0000-000000000001",
  username: "alice",
  email: "alice@example.com",
  displayName: "Alice Smith",
  slAvatarUuid: null,
  verified: true,
  role: "USER",
};

const mockUserNullDisplayName: AuthUser = {
  publicId: "00000000-0000-0000-0000-000000000002",
  username: "alice",
  email: "alice@example.com",
  displayName: null,
  slAvatarUuid: null,
  verified: true,
  role: "USER",
};

describe("UserMenuDropdown", () => {
  beforeEach(() => {
    mockPush.mockReset();
    mockUseMyGroupInvitations.mockReset();
    mockUseMyGroupInvitations.mockReturnValue({ data: [], isPending: false });
  });

  it("renders trigger button with displayName when set", () => {
    renderWithProviders(<UserMenuDropdown user={mockUserWithDisplayName} />);
    expect(screen.getByRole("button", { name: /user menu/i })).toBeInTheDocument();
    expect(screen.getByText("Alice Smith")).toBeInTheDocument();
  });

  it("renders trigger label as username when displayName is null", () => {
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

  it("renders 'My groups' and 'Invitations' menu items with correct routes", async () => {
    mockUseMyGroupInvitations.mockReturnValue({ data: [], isPending: false });
    const user = userEvent.setup();

    renderWithProviders(<UserMenuDropdown user={mockUserWithDisplayName} />);
    await user.click(screen.getByRole("button", { name: /user menu/i }));

    const myGroups = screen.getByRole("menuitem", { name: "My groups" });
    expect(myGroups).toBeInTheDocument();
    await user.click(myGroups);
    expect(mockPush).toHaveBeenCalledWith("/groups/me");

    mockPush.mockReset();

    await user.click(screen.getByRole("button", { name: /user menu/i }));
    const invitations = screen.getByRole("menuitem", { name: /Invitations/ });
    expect(invitations).toBeInTheDocument();
    await user.click(invitations);
    expect(mockPush).toHaveBeenCalledWith("/groups/invitations/me");
  });

  it("renders invitations badge when pending count > 0", async () => {
    mockUseMyGroupInvitations.mockReturnValue({
      data: [{ publicId: "i1" }, { publicId: "i2" }, { publicId: "i3" }],
      isPending: false,
    });
    const user = userEvent.setup();

    renderWithProviders(<UserMenuDropdown user={mockUserWithDisplayName} />);
    await user.click(screen.getByRole("button", { name: /user menu/i }));

    const invites = screen.getByRole("menuitem", { name: /Invitations/ });
    const badge = within(invites).getByTestId("invitations-badge");
    expect(badge).toHaveTextContent("3");
  });

  it("hides invitations badge when pending count is 0", async () => {
    mockUseMyGroupInvitations.mockReturnValue({ data: [], isPending: false });
    const user = userEvent.setup();

    renderWithProviders(<UserMenuDropdown user={mockUserWithDisplayName} />);
    await user.click(screen.getByRole("button", { name: /user menu/i }));

    const invites = screen.getByRole("menuitem", { name: /Invitations/ });
    expect(within(invites).queryByTestId("invitations-badge")).toBeNull();
  });
});
