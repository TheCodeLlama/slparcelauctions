import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { EmptyState } from "./EmptyState";
import { Search } from "@/components/ui/icons";

describe("EmptyState", () => {
  it("renders icon + headline + description", () => {
    renderWithProviders(
      <EmptyState
        icon={Search}
        headline="No results"
        description="Try adjusting your search."
      />,
    );
    expect(screen.getByRole("heading", { name: "No results" })).toBeInTheDocument();
    expect(screen.getByText("Try adjusting your search.")).toBeInTheDocument();
    // Icon renders as SVG
    expect(document.querySelector("svg")).toBeInTheDocument();
  });

  it("renders without description", () => {
    renderWithProviders(<EmptyState icon={Search} headline="Nothing here" />);
    expect(screen.getByRole("heading", { name: "Nothing here" })).toBeInTheDocument();
    expect(screen.queryByText("Try adjusting your search.")).not.toBeInTheDocument();
  });

  it("uses h3 for headline", () => {
    renderWithProviders(<EmptyState icon={Search} headline="Empty" />);
    const heading = screen.getByRole("heading", { name: "Empty" });
    expect(heading.tagName).toBe("H3");
  });
});
