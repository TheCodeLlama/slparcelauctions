import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import {
  SellerProfileCard,
  type SellerProfileCardSeller,
} from "./SellerProfileCard";

function makeSeller(
  overrides: Partial<SellerProfileCardSeller> = {},
): SellerProfileCardSeller {
  return {
    id: 42,
    displayName: "Carol Seller",
    avatarUrl: null,
    averageRating: 4.6,
    reviewCount: 12,
    completedSales: 8,
    completionRate: 0.92,
    memberSince: "2025-11-03",
    ...overrides,
  };
}

describe("SellerProfileCard", () => {
  it("renders display name, member since, rating, and sales count", () => {
    renderWithProviders(<SellerProfileCard seller={makeSeller()} />);
    expect(screen.getByText("Carol Seller")).toBeInTheDocument();
    expect(
      screen.getByTestId("seller-profile-card-member-since"),
    ).toHaveTextContent("Member since Nov 2025");
    expect(screen.getByText("4.6")).toBeInTheDocument();
    expect(screen.getByText("(12 reviews)")).toBeInTheDocument();
    expect(screen.getByText("8 completed sales")).toBeInTheDocument();
  });

  it("renders completion rate as a rounded percentage", () => {
    renderWithProviders(
      <SellerProfileCard seller={makeSeller({ completionRate: 0.9247 })} />,
    );
    expect(
      screen.getByTestId("seller-profile-card-completion-rate"),
    ).toHaveTextContent("Completion rate: 92%");
  });

  it("rounds a 100% completion rate to 100%", () => {
    renderWithProviders(
      <SellerProfileCard seller={makeSeller({ completionRate: 1 })} />,
    );
    expect(
      screen.getByTestId("seller-profile-card-completion-rate"),
    ).toHaveTextContent("Completion rate: 100%");
  });

  it("accepts completionRate as a string (BigDecimal wire format)", () => {
    renderWithProviders(
      <SellerProfileCard seller={makeSeller({ completionRate: "0.75" })} />,
    );
    expect(
      screen.getByTestId("seller-profile-card-completion-rate"),
    ).toHaveTextContent("Completion rate: 75%");
  });

  it("renders 'Too new to calculate' when completionRate is null", () => {
    renderWithProviders(
      <SellerProfileCard
        seller={makeSeller({ completionRate: null, completedSales: 5 })}
      />,
    );
    expect(
      screen.getByTestId("seller-profile-card-completion-rate"),
    ).toHaveTextContent("Completion rate: Too new to calculate");
  });

  it("singularizes 'completed sale' when there is exactly one", () => {
    renderWithProviders(
      <SellerProfileCard seller={makeSeller({ completedSales: 1 })} />,
    );
    expect(screen.getByText("1 completed sale")).toBeInTheDocument();
  });

  it("renders 'No ratings yet' when averageRating is null", () => {
    renderWithProviders(
      <SellerProfileCard
        seller={makeSeller({ averageRating: null, reviewCount: 0 })}
      />,
    );
    expect(screen.getByText("No ratings yet")).toBeInTheDocument();
  });

  it("shows the NewSellerBadge when completedSales < 3", () => {
    renderWithProviders(
      <SellerProfileCard seller={makeSeller({ completedSales: 1 })} />,
    );
    expect(screen.getByText("New Seller")).toBeInTheDocument();
  });

  it("hides the NewSellerBadge once completedSales >= 3 and completionRate is set", () => {
    renderWithProviders(
      <SellerProfileCard
        seller={makeSeller({ completedSales: 3, completionRate: 0.8 })}
      />,
    );
    expect(screen.queryByText("New Seller")).not.toBeInTheDocument();
  });

  it("shows the NewSellerBadge when completionRate is null even with completedSales >= 3", () => {
    renderWithProviders(
      <SellerProfileCard
        seller={makeSeller({ completedSales: 10, completionRate: null })}
      />,
    );
    expect(screen.getByText("New Seller")).toBeInTheDocument();
  });

  it("omits the member-since line when memberSince is null", () => {
    renderWithProviders(
      <SellerProfileCard seller={makeSeller({ memberSince: null })} />,
    );
    expect(
      screen.queryByTestId("seller-profile-card-member-since"),
    ).toBeNull();
  });

  it("links the whole card to /users/{id}", () => {
    renderWithProviders(
      <SellerProfileCard seller={makeSeller({ id: 77 })} />,
    );
    expect(screen.getByTestId("seller-profile-card")).toHaveAttribute(
      "href",
      "/users/77",
    );
  });

  it("renders under the dark theme without crashing", () => {
    renderWithProviders(<SellerProfileCard seller={makeSeller()} />, {
      theme: "dark",
      forceTheme: true,
    });
    expect(screen.getByTestId("seller-profile-card")).toBeInTheDocument();
  });
});
