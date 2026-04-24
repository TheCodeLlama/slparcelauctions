import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { Pagination } from "./Pagination";

describe("Pagination", () => {
  it("renders page numbers and prev/next", () => {
    renderWithProviders(
      <Pagination page={2} totalPages={5} onPageChange={() => {}} />,
    );
    expect(
      screen.getByRole("button", { name: /previous page/i }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /next page/i }),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /page 3/i })).toHaveAttribute(
      "aria-current",
      "page",
    );
  });

  it("disables prev on first page", () => {
    renderWithProviders(
      <Pagination page={0} totalPages={3} onPageChange={() => {}} />,
    );
    expect(screen.getByRole("button", { name: /previous page/i })).toBeDisabled();
  });

  it("disables next on last page", () => {
    renderWithProviders(
      <Pagination page={2} totalPages={3} onPageChange={() => {}} />,
    );
    expect(screen.getByRole("button", { name: /next page/i })).toBeDisabled();
  });

  it("calls onPageChange with page number", async () => {
    const onPageChange = vi.fn();
    renderWithProviders(
      <Pagination page={0} totalPages={5} onPageChange={onPageChange} />,
    );
    await userEvent.click(screen.getByRole("button", { name: /page 3/i }));
    expect(onPageChange).toHaveBeenCalledWith(2);
  });

  it("collapses with ellipsis when many pages", () => {
    renderWithProviders(
      <Pagination page={10} totalPages={50} onPageChange={() => {}} />,
    );
    expect(screen.getAllByText("…").length).toBeGreaterThan(0);
  });

  it("renders nothing when only one page", () => {
    renderWithProviders(
      <Pagination page={0} totalPages={1} onPageChange={() => {}} />,
    );
    // Component returns null for single-page lists — no nav landmark exists.
    expect(screen.queryByRole("navigation", { name: /pagination/i })).toBeNull();
  });
});
