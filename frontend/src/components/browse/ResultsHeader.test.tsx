import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { ResultsHeader } from "./ResultsHeader";

describe("ResultsHeader", () => {
  it("renders title + result count", () => {
    renderWithProviders(
      <ResultsHeader
        total={12}
        sort="newest"
        onSortChange={() => {}}
        nearestEnabled={false}
      />,
    );
    expect(screen.getByText("Browse")).toBeInTheDocument();
    expect(screen.getByText(/12 results/i)).toBeInTheDocument();
  });

  it("pluralizes 1 result correctly", () => {
    renderWithProviders(
      <ResultsHeader
        total={1}
        sort="newest"
        onSortChange={() => {}}
        nearestEnabled={false}
      />,
    );
    expect(screen.getByText(/1 result$/i)).toBeInTheDocument();
  });

  it("fires onOpenMobile when the filter trigger is clicked", async () => {
    const onOpen = vi.fn();
    renderWithProviders(
      <ResultsHeader
        total={0}
        sort="newest"
        onSortChange={() => {}}
        onOpenMobile={onOpen}
        nearestEnabled={false}
      />,
    );
    await userEvent.click(screen.getByRole("button", { name: /filters/i }));
    expect(onOpen).toHaveBeenCalled();
  });

  it("propagates sort changes", async () => {
    const onSortChange = vi.fn();
    renderWithProviders(
      <ResultsHeader
        total={10}
        sort="newest"
        onSortChange={onSortChange}
        nearestEnabled={false}
      />,
    );
    await userEvent.selectOptions(
      screen.getByLabelText(/sort/i),
      "ending_soonest",
    );
    expect(onSortChange).toHaveBeenCalledWith("ending_soonest");
  });

  it("renders in dark mode", () => {
    renderWithProviders(
      <ResultsHeader
        total={3}
        sort="newest"
        onSortChange={() => {}}
        nearestEnabled={false}
      />,
      { theme: "dark", forceTheme: true },
    );
    expect(screen.getByText("Browse")).toBeInTheDocument();
  });
});
