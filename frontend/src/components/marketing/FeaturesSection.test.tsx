// frontend/src/components/marketing/FeaturesSection.test.tsx

import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { FeaturesSection } from "./FeaturesSection";

describe("FeaturesSection", () => {
  it("renders the heading and all six feature titles", () => {
    renderWithProviders(<FeaturesSection />);

    expect(
      screen.getByRole("heading", { name: /designed for performance/i })
    ).toBeInTheDocument();

    expect(
      screen.getByRole("heading", { name: "Real-Time Bidding" })
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Secure Escrow" })
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Snipe Protection" })
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Verified Listings" })
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Proxy Bidding" })
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Reputation System" })
    ).toBeInTheDocument();
  });
});
