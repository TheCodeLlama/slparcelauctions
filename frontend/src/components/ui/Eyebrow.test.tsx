import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { Eyebrow } from "./Eyebrow";

describe("Eyebrow", () => {
  it("renders children as text content", () => {
    renderWithProviders(<Eyebrow>Featured Auctions</Eyebrow>);
    expect(screen.getByText("Featured Auctions")).toBeInTheDocument();
  });

  it("applies brand color and uppercase styling", () => {
    renderWithProviders(<Eyebrow data-testid="eyebrow">Label</Eyebrow>);
    const el = screen.getByText("Label");
    expect(el.className).toContain("text-brand");
    expect(el.className).toContain("uppercase");
  });

  it("merges consumer className", () => {
    renderWithProviders(<Eyebrow className="mb-2">Label</Eyebrow>);
    const el = screen.getByText("Label");
    expect(el.className).toContain("mb-2");
  });
});
