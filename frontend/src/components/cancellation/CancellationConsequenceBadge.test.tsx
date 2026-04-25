import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { CancellationConsequenceBadge } from "./CancellationConsequenceBadge";

describe("CancellationConsequenceBadge", () => {
  it("renders 'No penalty' when kind is null", () => {
    renderWithProviders(
      <CancellationConsequenceBadge kind={null} amountL={null} />,
    );
    const badge = screen.getByTestId("cancellation-consequence-badge");
    expect(badge).toHaveTextContent("No penalty");
    // default tone uses on-surface-variant text
    expect(badge.className).toMatch(/text-on-surface-variant/);
  });

  it("renders 'No penalty' when kind is NONE", () => {
    renderWithProviders(
      <CancellationConsequenceBadge kind="NONE" amountL={null} />,
    );
    expect(screen.getByTestId("cancellation-consequence-badge")).toHaveTextContent(
      "No penalty",
    );
  });

  it("renders 'Warning' on WARNING with warning tone", () => {
    renderWithProviders(
      <CancellationConsequenceBadge kind="WARNING" amountL={null} />,
    );
    const badge = screen.getByTestId("cancellation-consequence-badge");
    expect(badge).toHaveTextContent("Warning");
    expect(badge.className).toMatch(/secondary-container/);
  });

  it("formats the L$ amount with locale grouping on PENALTY", () => {
    renderWithProviders(
      <CancellationConsequenceBadge kind="PENALTY" amountL={1000} />,
    );
    const badge = screen.getByTestId("cancellation-consequence-badge");
    expect(badge).toHaveTextContent("L$1,000 penalty");
    expect(badge.className).toMatch(/error-container/);
  });

  it("renders the combined copy on PENALTY_AND_30D", () => {
    renderWithProviders(
      <CancellationConsequenceBadge kind="PENALTY_AND_30D" amountL={2500} />,
    );
    const badge = screen.getByTestId("cancellation-consequence-badge");
    expect(badge).toHaveTextContent("L$2,500 + 30-day suspension");
    expect(badge.className).toMatch(/error-container/);
  });

  it("renders 'Permanent ban' on PERMANENT_BAN", () => {
    renderWithProviders(
      <CancellationConsequenceBadge kind="PERMANENT_BAN" amountL={null} />,
    );
    const badge = screen.getByTestId("cancellation-consequence-badge");
    expect(badge).toHaveTextContent("Permanent ban");
    expect(badge.className).toMatch(/error-container/);
  });

  it("falls back to '0' when amountL is null on a PENALTY", () => {
    // Defensive — backend should always populate amountL on the L$
    // rungs, but the UI mustn't crash if a stray null arrives.
    renderWithProviders(
      <CancellationConsequenceBadge kind="PENALTY" amountL={null} />,
    );
    expect(screen.getByTestId("cancellation-consequence-badge")).toHaveTextContent(
      "L$0 penalty",
    );
  });
});
