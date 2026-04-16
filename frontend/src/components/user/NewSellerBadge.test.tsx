import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { NewSellerBadge } from "./NewSellerBadge";

describe("NewSellerBadge", () => {
  it("renders 'New Seller' when completedSales < 3", () => {
    renderWithProviders(<NewSellerBadge completedSales={0} />);
    expect(screen.getByText("New Seller")).toBeInTheDocument();
  });

  it("renders nothing when completedSales >= 3", () => {
    renderWithProviders(<NewSellerBadge completedSales={3} />);
    expect(screen.queryByText("New Seller")).not.toBeInTheDocument();
  });
});
