import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { LoadingSpinner } from "./LoadingSpinner";

describe("LoadingSpinner", () => {
  it("renders with role=status", () => {
    renderWithProviders(<LoadingSpinner />);
    expect(screen.getByRole("status")).toBeInTheDocument();
  });

  it("renders label when provided", () => {
    renderWithProviders(<LoadingSpinner label="Loading auctions..." />);
    expect(screen.getByText("Loading auctions...")).toBeInTheDocument();
  });
});
