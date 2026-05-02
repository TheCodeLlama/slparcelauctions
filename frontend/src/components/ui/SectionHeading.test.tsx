import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { SectionHeading } from "./SectionHeading";

describe("SectionHeading", () => {
  it("renders title as h2", () => {
    renderWithProviders(<SectionHeading title="Browse Auctions" />);
    const heading = screen.getByRole("heading", { level: 2 });
    expect(heading).toHaveTextContent("Browse Auctions");
  });

  it("renders optional sub text when provided", () => {
    renderWithProviders(<SectionHeading title="Browse" sub="Find your parcel" />);
    expect(screen.getByText("Find your parcel")).toBeInTheDocument();
  });

  it("renders optional right slot when provided", () => {
    renderWithProviders(
      <SectionHeading title="Browse" right={<a href="/browse">View all</a>} />
    );
    expect(screen.getByRole("link", { name: "View all" })).toBeInTheDocument();
  });

  it("merges consumer className", () => {
    renderWithProviders(
      <SectionHeading title="Browse" className="mt-8" />
    );
    const container = screen.getByRole("heading", { level: 2 }).closest("div");
    expect(container?.parentElement?.className).toContain("mt-8");
  });
});
