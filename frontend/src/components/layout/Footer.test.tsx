import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { Footer } from "./Footer";

describe("Footer", () => {
  beforeEach(() => {
    // Pin time per testing principle #5 — copyright year must be deterministic.
    vi.useFakeTimers({ now: new Date("2026-06-15T12:00:00Z") });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("renders all four legal/site-map links with correct hrefs", () => {
    renderWithProviders(<Footer />);
    expect(screen.getByRole("link", { name: "About" }).getAttribute("href")).toBe("/about");
    expect(screen.getByRole("link", { name: "Terms" }).getAttribute("href")).toBe("/terms");
    expect(screen.getByRole("link", { name: "Contact" }).getAttribute("href")).toBe("/contact");
    expect(screen.getByRole("link", { name: "Partners" }).getAttribute("href")).toBe("/partners");
  });

  it("renders the current year in the copyright text", () => {
    renderWithProviders(<Footer />);
    expect(screen.getByText(/© 2026 SLParcels/)).toBeInTheDocument();
  });
});
