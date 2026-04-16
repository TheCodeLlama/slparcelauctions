import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { ReputationStars } from "./ReputationStars";

describe("ReputationStars", () => {
  it("shows 'No ratings yet' when rating is null", () => {
    renderWithProviders(
      <ReputationStars rating={null} reviewCount={0} label="Seller" />,
    );

    expect(screen.getByText("No ratings yet")).toBeInTheDocument();
    expect(screen.getByText("Seller")).toBeInTheDocument();
  });

  it("shows numeric rating with plural reviews", () => {
    renderWithProviders(
      <ReputationStars rating={4.7} reviewCount={12} />,
    );

    expect(screen.getByText("4.7")).toBeInTheDocument();
    expect(screen.getByText("(12 reviews)")).toBeInTheDocument();
  });

  it("shows singular 'review' for reviewCount of 1", () => {
    renderWithProviders(
      <ReputationStars rating={5.0} reviewCount={1} />,
    );

    expect(screen.getByText("5.0")).toBeInTheDocument();
    expect(screen.getByText("(1 review)")).toBeInTheDocument();
  });
});
