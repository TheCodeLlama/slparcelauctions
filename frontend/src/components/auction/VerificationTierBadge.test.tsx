import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { VerificationTierBadge } from "./VerificationTierBadge";

describe("VerificationTierBadge", () => {
  it("renders SCRIPT tier with default tone and script label", () => {
    renderWithProviders(<VerificationTierBadge tier="SCRIPT" />);
    const badge = screen.getByTestId("verification-tier-badge");
    expect(badge).toHaveAttribute("data-tier", "SCRIPT");
    expect(badge).toHaveTextContent("Script verified");
    expect(badge.className).toContain("bg-surface-container-high");
  });

  it("renders BOT tier with success tone and bot label", () => {
    renderWithProviders(<VerificationTierBadge tier="BOT" />);
    const badge = screen.getByTestId("verification-tier-badge");
    expect(badge).toHaveAttribute("data-tier", "BOT");
    expect(badge).toHaveTextContent("Bot verified");
    expect(badge.className).toContain("bg-tertiary-container");
  });

  it("renders OWNERSHIP_TRANSFER tier with warning tone", () => {
    renderWithProviders(<VerificationTierBadge tier="OWNERSHIP_TRANSFER" />);
    const badge = screen.getByTestId("verification-tier-badge");
    expect(badge).toHaveAttribute("data-tier", "OWNERSHIP_TRANSFER");
    expect(badge).toHaveTextContent("Ownership transfer verified");
    expect(badge.className).toContain("bg-secondary-container");
  });

  it("renders nothing when tier is null", () => {
    renderWithProviders(<VerificationTierBadge tier={null} />);
    expect(screen.queryByTestId("verification-tier-badge")).toBeNull();
  });
});
