import { describe, expect, it, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { FilterChipsRow } from "./FilterChipsRow";

describe("FilterChipsRow", () => {
  it("renders the core filter chips and marks the selected one aria-selected", () => {
    renderWithProviders(
      <FilterChipsRow
        value="Active"
        onChange={vi.fn()}
        suspendedCount={0}
        totalCount={5}
      />,
    );
    const active = screen.getByRole("tab", { name: /Active/ });
    const drafts = screen.getByRole("tab", { name: /Drafts/ });
    expect(active).toHaveAttribute("aria-selected", "true");
    expect(drafts).toHaveAttribute("aria-selected", "false");
  });

  it("hides the Suspended chip when suspendedCount is 0", () => {
    renderWithProviders(
      <FilterChipsRow
        value="All"
        onChange={vi.fn()}
        suspendedCount={0}
        totalCount={3}
      />,
    );
    expect(
      screen.queryByRole("tab", { name: /Suspended/ }),
    ).not.toBeInTheDocument();
  });

  it("shows the Suspended chip with a count badge when suspendedCount > 0", () => {
    renderWithProviders(
      <FilterChipsRow
        value="All"
        onChange={vi.fn()}
        suspendedCount={2}
        totalCount={10}
      />,
    );
    const suspended = screen.getByRole("tab", { name: /Suspended/ });
    expect(suspended).toBeInTheDocument();
    expect(suspended.textContent).toContain("2");
  });

  it("shows the total count badge on the All chip", () => {
    renderWithProviders(
      <FilterChipsRow
        value="All"
        onChange={vi.fn()}
        suspendedCount={0}
        totalCount={7}
      />,
    );
    const all = screen.getByRole("tab", { name: /All/ });
    expect(all.textContent).toContain("7");
  });

  it("calls onChange with the clicked filter", async () => {
    const onChange = vi.fn();
    renderWithProviders(
      <FilterChipsRow
        value="All"
        onChange={onChange}
        suspendedCount={0}
        totalCount={5}
      />,
    );
    await userEvent.click(screen.getByRole("tab", { name: /Drafts/ }));
    expect(onChange).toHaveBeenCalledWith("Drafts");
  });
});
