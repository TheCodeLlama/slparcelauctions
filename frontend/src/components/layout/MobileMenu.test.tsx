import { describe, it, expect, vi, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent, within, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { mockUser } from "@/test/msw/fixtures";
import { MobileMenu } from "./MobileMenu";

vi.mock("@/lib/auth", () => ({
  useAuth: vi.fn(() => ({ status: "unauthenticated", user: null })),
}));

import { useAuth } from "@/lib/auth";
const mockedUseAuth = vi.mocked(useAuth);

describe("MobileMenu", () => {
  beforeEach(() => {
    mockedUseAuth.mockReset();
    mockedUseAuth.mockReturnValue({ status: "unauthenticated", user: null });
  });

  it("renders the drawer when open is true", () => {
    renderWithProviders(<MobileMenu open onClose={() => {}} />);
    expect(screen.getByRole("link", { name: "Browse" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Dashboard" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Sell parcel" })).toBeInTheDocument();
  });

  it("does not render content when open is false", () => {
    renderWithProviders(<MobileMenu open={false} onClose={() => {}} />);
    expect(screen.queryByRole("link", { name: "Browse" })).toBeNull();
  });

  it("calls onClose when the close button is clicked", async () => {
    const onClose = vi.fn();
    renderWithProviders(<MobileMenu open onClose={onClose} />);
    await userEvent.click(screen.getByRole("button", { name: "Close menu" }));
    expect(onClose).toHaveBeenCalled();
  });

  it("calls onClose when escape is pressed (Headless UI Dialog handles this)", async () => {
    const onClose = vi.fn();
    renderWithProviders(<MobileMenu open onClose={onClose} />);
    await userEvent.keyboard("{Escape}");
    expect(onClose).toHaveBeenCalled();
  });

  it("renders the top-level Groups link in mobile menu", () => {
    renderWithProviders(<MobileMenu open onClose={() => {}} />);
    expect(screen.getByRole("link", { name: /^Groups$/ })).toHaveAttribute(
      "href",
      "/groups",
    );
  });

  it("does not render the authenticated block when unauthenticated", () => {
    renderWithProviders(<MobileMenu open onClose={() => {}} />);
    expect(screen.queryByRole("link", { name: "My groups" })).toBeNull();
    expect(screen.queryByRole("link", { name: /Invitations/ })).toBeNull();
  });

  it("renders My groups + Invitations in the authenticated block", async () => {
    mockedUseAuth.mockReturnValue({
      status: "authenticated",
      user: { ...mockUser, verified: true },
    });
    server.use(
      http.get("*/api/v1/me/invitations", () => HttpResponse.json([])),
    );

    renderWithProviders(<MobileMenu open onClose={() => {}} />);

    expect(screen.getByRole("link", { name: "My groups" })).toHaveAttribute(
      "href",
      "/groups/me",
    );
    expect(
      screen.getByRole("link", { name: /Invitations/ }),
    ).toHaveAttribute("href", "/groups/invitations/me");
  });

  it("renders an invitations badge with the pending count", async () => {
    mockedUseAuth.mockReturnValue({
      status: "authenticated",
      user: { ...mockUser, verified: true },
    });
    server.use(
      http.get("*/api/v1/me/invitations", () =>
        HttpResponse.json([
          { publicId: "i1" },
          { publicId: "i2" },
          { publicId: "i3" },
        ]),
      ),
    );

    renderWithProviders(<MobileMenu open onClose={() => {}} />);

    const invitesLink = screen.getByRole("link", { name: /Invitations/ });
    await waitFor(() => {
      expect(
        within(invitesLink).getByTestId("invitations-badge"),
      ).toHaveTextContent("3");
    });
  });

  it("omits the invitations badge when there are no pending invitations", async () => {
    mockedUseAuth.mockReturnValue({
      status: "authenticated",
      user: { ...mockUser, verified: true },
    });
    server.use(
      http.get("*/api/v1/me/invitations", () => HttpResponse.json([])),
    );

    renderWithProviders(<MobileMenu open onClose={() => {}} />);

    const invitesLink = screen.getByRole("link", { name: /Invitations/ });
    await waitFor(() => {
      expect(invitesLink).toHaveAttribute("href", "/groups/invitations/me");
    });
    expect(within(invitesLink).queryByTestId("invitations-badge")).toBeNull();
  });
});
