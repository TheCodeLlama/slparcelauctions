import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { Tabs, type TabItem } from "./Tabs";

vi.mock("next/navigation", () => ({
  usePathname: vi.fn(),
}));

import { usePathname } from "next/navigation";
const mockUsePathname = vi.mocked(usePathname);

const tabs: TabItem[] = [
  { id: "overview", label: "Overview", href: "/dashboard" },
  { id: "bids", label: "My Bids", href: "/dashboard/bids" },
  { id: "sales", label: "My Sales", href: "/dashboard/sales" },
];

describe("Tabs", () => {
  it("renders all tab labels", () => {
    mockUsePathname.mockReturnValue("/dashboard");
    renderWithProviders(<Tabs tabs={tabs} />);
    expect(screen.getByText("Overview")).toBeInTheDocument();
    expect(screen.getByText("My Bids")).toBeInTheDocument();
    expect(screen.getByText("My Sales")).toBeInTheDocument();
  });

  it("marks active tab with aria-selected based on pathname", () => {
    mockUsePathname.mockReturnValue("/dashboard/bids");
    renderWithProviders(<Tabs tabs={tabs} />);
    expect(screen.getByText("My Bids").closest("a")).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(screen.getByText("Overview").closest("a")).toHaveAttribute(
      "aria-selected",
      "false",
    );
  });

  it("supports nested route prefix matching", () => {
    mockUsePathname.mockReturnValue("/dashboard/bids/123");
    renderWithProviders(<Tabs tabs={tabs} />);
    expect(screen.getByText("My Bids").closest("a")).toHaveAttribute(
      "aria-selected",
      "true",
    );
  });

  it("renders tablist nav with aria-label", () => {
    mockUsePathname.mockReturnValue("/dashboard");
    renderWithProviders(<Tabs tabs={tabs} />);
    const nav = screen.getByRole("tablist");
    expect(nav).toHaveAttribute("aria-label", "Dashboard sections");
  });
});
