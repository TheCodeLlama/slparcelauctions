import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { AuthGateMessage } from "./AuthGateMessage";

describe("AuthGateMessage", () => {
  it("renders unauth copy and a login link with next= for the auction", () => {
    renderWithProviders(<AuthGateMessage kind="unauth" auctionPublicId="00000000-0000-0000-0000-00000000002a" />);
    const node = screen.getByTestId("auth-gate-message");
    expect(node).toHaveAttribute("data-kind", "unauth");
    expect(node).toHaveTextContent(/Sign in to bid on this auction/i);
    const link = screen.getByRole("link", { name: /sign in/i });
    expect(link).toHaveAttribute(
      "href",
      `/login?next=${encodeURIComponent("/auction/00000000-0000-0000-0000-00000000002a")}`,
    );
  });

  it("falls back to /login without next= when auctionId is missing", () => {
    renderWithProviders(<AuthGateMessage kind="unauth" />);
    const link = screen.getByRole("link", { name: /sign in/i });
    expect(link).toHaveAttribute("href", "/login");
  });

  it("renders unverified copy and a link to /dashboard/overview", () => {
    renderWithProviders(<AuthGateMessage kind="unverified" />);
    const node = screen.getByTestId("auth-gate-message");
    expect(node).toHaveAttribute("data-kind", "unverified");
    expect(node).toHaveTextContent(/Verify your Second Life avatar/i);
    const link = screen.getByRole("link", { name: /verification/i });
    expect(link).toHaveAttribute("href", "/dashboard/overview");
  });

  it("renders seller copy with no CTA link", () => {
    renderWithProviders(<AuthGateMessage kind="seller" />);
    const node = screen.getByTestId("auth-gate-message");
    expect(node).toHaveAttribute("data-kind", "seller");
    expect(node).toHaveTextContent(/This is your auction/i);
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });
});
