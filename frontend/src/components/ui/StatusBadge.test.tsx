import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { StatusBadge } from "./StatusBadge";

describe("StatusBadge", () => {
  it("renders the active status with info-bg tone and default label", () => {
    renderWithProviders(<StatusBadge status="active" data-testid="badge" />);
    const badge = screen.getByText("Active");
    expect(badge.className).toContain("bg-info-bg");
    expect(badge.className).toContain("text-info");
  });

  it("renders the ending-soon status with danger-bg tone", () => {
    renderWithProviders(<StatusBadge status="ending-soon" />);
    const badge = screen.getByText("Ending Soon");
    expect(badge.className).toContain("bg-danger-bg");
  });

  it("renders the warning tone in generic mode and merges consumer className", () => {
    renderWithProviders(<StatusBadge tone="warning" className="ml-auto">Custom</StatusBadge>);
    const badge = screen.getByText("Custom");
    expect(badge.className).toContain("bg-warning-bg");
    expect(badge.className).toContain("ml-auto");
  });

  it("returns null when no status, tone, or children are provided", () => {
    const { container } = renderWithProviders(<StatusBadge />);
    expect(container.querySelector("span")).toBeNull();
  });

  it("uses children to override the default status label", () => {
    renderWithProviders(<StatusBadge status="active">12 bids</StatusBadge>);
    const badge = screen.getByText("12 bids");
    expect(badge.className).toContain("bg-info-bg");
  });
});
