// frontend/src/components/marketing/HowItWorksSection.test.tsx

import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { HowItWorksSection } from "./HowItWorksSection";

describe("HowItWorksSection", () => {
  it("renders the heading and all four step titles", () => {
    renderWithProviders(<HowItWorksSection />);

    expect(
      screen.getByRole("heading", { name: /simple, secure, curated/i })
    ).toBeInTheDocument();

    expect(screen.getByRole("heading", { name: "Verify" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "List" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Auction" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Settle" })).toBeInTheDocument();
  });
});
