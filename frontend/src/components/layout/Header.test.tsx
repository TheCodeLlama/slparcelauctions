import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { Header } from "./Header";

vi.mock("@/lib/auth", () => ({
  useAuth: vi.fn(() => ({ status: "unauthenticated", user: null })),
}));

import { useAuth } from "@/lib/auth";
const mockedUseAuth = vi.mocked(useAuth);

describe("Header", () => {
  beforeEach(() => {
    mockedUseAuth.mockReset();
    mockedUseAuth.mockReturnValue({ status: "unauthenticated", user: null });
  });

  it("renders the SLPA wordmark linking to /", () => {
    renderWithProviders(<Header />);
    const logo = screen.getByRole("link", { name: "SLPA" });
    expect(logo.getAttribute("href")).toBe("/");
  });

  it("renders desktop nav links to Browse, Dashboard, Create Listing", () => {
    renderWithProviders(<Header />);
    expect(screen.getByRole("link", { name: "Browse" }).getAttribute("href")).toBe("/browse");
    expect(screen.getByRole("link", { name: "Dashboard" }).getAttribute("href")).toBe("/dashboard");
    expect(screen.getByRole("link", { name: "Create Listing" }).getAttribute("href")).toBe("/auction/new");
  });

  it("renders Sign in and Register buttons when unauthenticated", () => {
    renderWithProviders(<Header />);
    expect(screen.getByRole("link", { name: /Sign in/ })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Register/ })).toBeInTheDocument();
  });

  it("renders the avatar dropdown when authenticated", () => {
    mockedUseAuth.mockReturnValue({
      status: "authenticated",
      user: {
        id: 1,
        email: "heath@example.com",
        displayName: "Heath Barcus",
        slAvatarUuid: null,
        verified: true,
      },
    });
    renderWithProviders(<Header />);
    expect(screen.getByRole("img", { name: "Account menu" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /Sign in/ })).toBeNull();
  });

  it("opens the mobile menu when the hamburger is clicked", async () => {
    renderWithProviders(<Header />);
    expect(screen.queryByRole("button", { name: "Close menu" })).toBeNull();
    await userEvent.click(screen.getByRole("button", { name: "Open menu" }));
    expect(screen.getByRole("button", { name: "Close menu" })).toBeInTheDocument();
  });
});
