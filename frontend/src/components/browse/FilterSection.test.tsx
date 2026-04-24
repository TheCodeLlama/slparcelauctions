import { describe, it, expect } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { FilterSection } from "./FilterSection";

describe("FilterSection", () => {
  it("renders the title in uppercase styling", () => {
    renderWithProviders(
      <FilterSection title="Price">
        <p>content</p>
      </FilterSection>,
    );
    const header = screen.getByRole("button", { name: /price/i });
    expect(header).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByText("content")).toBeInTheDocument();
  });

  it("collapses when the header is clicked", async () => {
    renderWithProviders(
      <FilterSection title="Price">
        <p>content</p>
      </FilterSection>,
    );
    await userEvent.click(screen.getByRole("button", { name: /price/i }));
    expect(screen.queryByText("content")).toBeNull();
  });

  it("honors defaultOpen={false}", () => {
    renderWithProviders(
      <FilterSection title="Price" defaultOpen={false}>
        <p>content</p>
      </FilterSection>,
    );
    expect(screen.queryByText("content")).toBeNull();
    const header = screen.getByRole("button", { name: /price/i });
    expect(header).toHaveAttribute("aria-expanded", "false");
  });
});
