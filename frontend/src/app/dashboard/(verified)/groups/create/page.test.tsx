import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import GroupCreatePage from "./page";

describe("GroupCreatePage", () => {
  it("renders the heading and the create form", () => {
    renderWithProviders(<GroupCreatePage />);
    const headings = screen.getAllByRole("heading", {
      name: /Create a realty group/i,
    });
    expect(headings.length).toBeGreaterThanOrEqual(1);
    expect(screen.getByTestId("group-create-submit")).toBeInTheDocument();
  });

  it("renders a back link to /dashboard/groups", () => {
    renderWithProviders(<GroupCreatePage />);
    const link = screen.getByRole("link", { name: /Back to my groups/i });
    expect(link).toHaveAttribute("href", "/dashboard/groups");
  });
});
