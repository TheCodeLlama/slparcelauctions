import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { SellerHeader } from "./SellerHeader";
import { mockPublicProfile } from "@/test/msw/fixtures";

describe("SellerHeader", () => {
  it("renders display name + member-since + profile link", () => {
    renderWithProviders(<SellerHeader user={mockPublicProfile} />);
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(
      /verified tester/i,
    );
    expect(screen.getByText(/member since/i)).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /view full profile/i }),
    ).toHaveAttribute("href", `/users/${mockPublicProfile.id}`);
  });

  it("shows verified badge for verified users", () => {
    renderWithProviders(<SellerHeader user={mockPublicProfile} />);
    expect(screen.getByLabelText(/verified/i)).toBeInTheDocument();
  });

  it("falls back to 'Anonymous' when displayName is null", () => {
    renderWithProviders(
      <SellerHeader user={{ ...mockPublicProfile, displayName: null }} />,
    );
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(
      /anonymous/i,
    );
  });

  it("renders in dark mode", () => {
    renderWithProviders(<SellerHeader user={mockPublicProfile} />, {
      theme: "dark",
      forceTheme: true,
    });
    expect(screen.getByRole("heading", { level: 1 })).toBeInTheDocument();
  });
});
