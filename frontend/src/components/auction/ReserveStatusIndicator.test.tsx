import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { ReserveStatusIndicator } from "./ReserveStatusIndicator";

describe("ReserveStatusIndicator", () => {
  it("renders nothing when reservePrice is null", () => {
    renderWithProviders(
      <ReserveStatusIndicator reservePrice={null} currentBid={1000} />,
    );
    expect(screen.queryByTestId("reserve-status-indicator")).toBeNull();
  });

  it("renders 'Reserve not met' when currentBid is null", () => {
    renderWithProviders(
      <ReserveStatusIndicator reservePrice={5000} currentBid={null} />,
    );
    const badge = screen.getByTestId("reserve-status-indicator");
    expect(badge).toHaveAttribute("data-state", "not-met");
    expect(badge).toHaveTextContent("Reserve not met");
    expect(badge.className).toContain("bg-warning-bg");
  });

  it("renders 'Reserve not met' when currentBid is below reservePrice", () => {
    renderWithProviders(
      <ReserveStatusIndicator reservePrice={5000} currentBid={4999} />,
    );
    expect(screen.getByTestId("reserve-status-indicator")).toHaveTextContent(
      "Reserve not met",
    );
  });

  it("renders 'Reserve met' when currentBid equals reservePrice", () => {
    renderWithProviders(
      <ReserveStatusIndicator reservePrice={5000} currentBid={5000} />,
    );
    const badge = screen.getByTestId("reserve-status-indicator");
    expect(badge).toHaveAttribute("data-state", "met");
    expect(badge).toHaveTextContent("Reserve met");
    expect(badge.className).toContain("bg-success-bg");
  });

  it("renders 'Reserve met' when currentBid exceeds reservePrice", () => {
    renderWithProviders(
      <ReserveStatusIndicator reservePrice={5000} currentBid={9999} />,
    );
    expect(screen.getByTestId("reserve-status-indicator")).toHaveTextContent(
      "Reserve met",
    );
  });

  it("never renders the reserve amount when below", () => {
    renderWithProviders(
      <ReserveStatusIndicator reservePrice={5000} currentBid={1000} />,
    );
    expect(
      screen.queryByText(/5000|5,000|L\$5000|L\$ 5000/),
    ).not.toBeInTheDocument();
  });

  it("never renders the reserve amount when met", () => {
    renderWithProviders(
      <ReserveStatusIndicator reservePrice={5000} currentBid={6000} />,
    );
    expect(
      screen.queryByText(/5000|5,000|L\$5000|L\$ 5000/),
    ).not.toBeInTheDocument();
  });
});
