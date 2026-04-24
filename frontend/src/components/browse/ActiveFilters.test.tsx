import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { ActiveFilters } from "./ActiveFilters";
import { defaultAuctionSearchQuery } from "@/lib/search/url-codec";
import type { AuctionSearchQuery } from "@/types/search";

describe("ActiveFilters", () => {
  it("renders nothing when query matches the baseline", () => {
    renderWithProviders(
      <ActiveFilters query={defaultAuctionSearchQuery} onChange={() => {}} />,
    );
    // No chips, no "Clear all" button.
    expect(screen.queryByRole("button", { name: /clear all/i })).toBeNull();
    expect(
      screen.queryByRole("button", { name: /remove filter/i }),
    ).toBeNull();
  });

  it("renders a region chip and removes it on click", async () => {
    const onChange = vi.fn();
    const q: AuctionSearchQuery = { ...defaultAuctionSearchQuery, region: "Tula" };
    renderWithProviders(<ActiveFilters query={q} onChange={onChange} />);
    expect(screen.getByText("Tula")).toBeInTheDocument();
    await userEvent.click(
      screen.getByRole("button", { name: /remove filter: tula/i }),
    );
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ region: undefined, page: 0 }),
    );
  });

  it("combines min/max price into a single chip", () => {
    const q: AuctionSearchQuery = {
      ...defaultAuctionSearchQuery,
      minPrice: 100,
      maxPrice: 500,
    };
    renderWithProviders(<ActiveFilters query={q} onChange={() => {}} />);
    expect(screen.getByText("L$100–500")).toBeInTheDocument();
  });

  it("renders maturity csv chip", () => {
    const q: AuctionSearchQuery = {
      ...defaultAuctionSearchQuery,
      maturity: ["GENERAL", "MODERATE"],
    };
    renderWithProviders(<ActiveFilters query={q} onChange={() => {}} />);
    expect(screen.getByText("General, Moderate")).toBeInTheDocument();
  });

  it("renders near_region chip with distance", () => {
    const q: AuctionSearchQuery = {
      ...defaultAuctionSearchQuery,
      nearRegion: "Tula",
      distance: 5,
    };
    renderWithProviders(<ActiveFilters query={q} onChange={() => {}} />);
    expect(screen.getByText(/tula.*5 regions/i)).toBeInTheDocument();
  });

  it("renders in dark mode", () => {
    const q: AuctionSearchQuery = {
      ...defaultAuctionSearchQuery,
      region: "Tula",
    };
    renderWithProviders(<ActiveFilters query={q} onChange={() => {}} />, {
      theme: "dark",
      forceTheme: true,
    });
    expect(screen.getByText("Tula")).toBeInTheDocument();
  });

  it("Clear all resets to the default query while preserving fixedFilters", async () => {
    const onChange = vi.fn();
    const q: AuctionSearchQuery = {
      ...defaultAuctionSearchQuery,
      region: "Tula",
      sellerId: 42,
    };
    renderWithProviders(
      <ActiveFilters
        query={q}
        onChange={onChange}
        fixedFilters={{ sellerId: 42 }}
      />,
    );
    await userEvent.click(screen.getByRole("button", { name: /clear all/i }));
    expect(onChange).toHaveBeenCalledWith({
      ...defaultAuctionSearchQuery,
      sellerId: 42,
    });
  });
});
