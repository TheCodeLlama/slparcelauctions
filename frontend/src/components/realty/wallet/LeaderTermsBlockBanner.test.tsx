import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { LeaderTermsBlockBanner } from "./LeaderTermsBlockBanner";

describe("LeaderTermsBlockBanner", () => {
  it("renders the banner when leaderTermsAcceptedAt is null", () => {
    renderWithProviders(<LeaderTermsBlockBanner leaderTermsAcceptedAt={null} />);
    expect(screen.getByTestId("leader-terms-block-banner")).toBeInTheDocument();
    expect(
      screen.getByText(/Leader must accept Wallet Terms of Service/i),
    ).toBeInTheDocument();
  });

  it("renders the banner when leaderTermsAcceptedAt is undefined", () => {
    renderWithProviders(
      <LeaderTermsBlockBanner leaderTermsAcceptedAt={undefined} />,
    );
    expect(screen.getByTestId("leader-terms-block-banner")).toBeInTheDocument();
  });

  it("does not render when leaderTermsAcceptedAt is a timestamp", () => {
    renderWithProviders(
      <LeaderTermsBlockBanner leaderTermsAcceptedAt="2026-05-01T10:00:00Z" />,
    );
    expect(
      screen.queryByTestId("leader-terms-block-banner"),
    ).not.toBeInTheDocument();
  });
});
