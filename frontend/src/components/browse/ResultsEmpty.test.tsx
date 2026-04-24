import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { ResultsEmpty } from "./ResultsEmpty";

describe("ResultsEmpty", () => {
  it("renders 'no active auctions yet' for reason=no-filters", () => {
    renderWithProviders(<ResultsEmpty reason="no-filters" />);
    expect(
      screen.getByText(/no active auctions yet/i),
    ).toBeInTheDocument();
  });

  it("renders 'no match' + CTA for reason=no-match", async () => {
    const onClear = vi.fn();
    renderWithProviders(
      <ResultsEmpty reason="no-match" onClearFilters={onClear} />,
    );
    expect(
      screen.getByText(/no auctions match your filters/i),
    ).toBeInTheDocument();
    await userEvent.click(
      screen.getByRole("button", { name: /clear all filters/i }),
    );
    expect(onClear).toHaveBeenCalled();
  });
});
