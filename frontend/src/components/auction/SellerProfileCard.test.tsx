import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { SellerProfileCard, type SellerProfileCardSeller } from "./SellerProfileCard";

function makeSeller(
  overrides: Partial<SellerProfileCardSeller> = {},
): SellerProfileCardSeller {
  return {
    id: 42,
    displayName: "Carol Seller",
    slAvatarName: "carol.resident",
    profilePicUrl: null,
    avgSellerRating: 4.6,
    totalSellerReviews: 12,
    completedSales: 8,
    ...overrides,
  };
}

describe("SellerProfileCard", () => {
  it("renders display name, SL avatar name, rating, and sales count", () => {
    renderWithProviders(<SellerProfileCard seller={makeSeller()} />);
    expect(screen.getByText("Carol Seller")).toBeInTheDocument();
    expect(screen.getByTestId("seller-profile-card-sl-name")).toHaveTextContent(
      "carol.resident",
    );
    expect(screen.getByText("4.6")).toBeInTheDocument();
    expect(screen.getByText("(12 reviews)")).toBeInTheDocument();
    expect(screen.getByText("8 completed sales")).toBeInTheDocument();
  });

  it("singularizes 'completed sale' when there is exactly one", () => {
    renderWithProviders(
      <SellerProfileCard seller={makeSeller({ completedSales: 1 })} />,
    );
    expect(screen.getByText("1 completed sale")).toBeInTheDocument();
  });

  it("renders 'No ratings yet' when avgSellerRating is null", () => {
    renderWithProviders(
      <SellerProfileCard
        seller={makeSeller({ avgSellerRating: null, totalSellerReviews: 0 })}
      />,
    );
    expect(screen.getByText("No ratings yet")).toBeInTheDocument();
  });

  it("includes the NewSellerBadge when completedSales < 3", () => {
    renderWithProviders(
      <SellerProfileCard seller={makeSeller({ completedSales: 1 })} />,
    );
    expect(screen.getByText("New Seller")).toBeInTheDocument();
  });

  it("hides the NewSellerBadge once completedSales >= 3", () => {
    renderWithProviders(
      <SellerProfileCard seller={makeSeller({ completedSales: 3 })} />,
    );
    expect(screen.queryByText("New Seller")).not.toBeInTheDocument();
  });

  it("treats missing enrichment fields as zero/null and still renders", () => {
    renderWithProviders(
      <SellerProfileCard
        seller={{
          id: 9,
          displayName: "Dave",
        }}
      />,
    );
    expect(screen.getByText("Dave")).toBeInTheDocument();
    expect(screen.getByText("No ratings yet")).toBeInTheDocument();
    expect(screen.getByText("0 completed sales")).toBeInTheDocument();
    expect(screen.getByText("New Seller")).toBeInTheDocument();
    expect(screen.queryByTestId("seller-profile-card-sl-name")).toBeNull();
  });

  it("links to /users/{id}", () => {
    renderWithProviders(<SellerProfileCard seller={makeSeller({ id: 77 })} />);
    const link = screen.getByTestId("seller-profile-card-link");
    expect(link).toHaveAttribute("href", "/users/77");
  });
});
