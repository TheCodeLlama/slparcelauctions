import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { StatusChip } from "./StatusChip";

describe("StatusChip", () => {
  it("renders label with tone class", () => {
    renderWithProviders(<StatusChip label="LIVE" tone="live" />);
    const chip = screen.getByText("LIVE");
    expect(chip).toBeInTheDocument();
    expect(chip).toHaveAttribute("data-tone", "live");
  });

  it("pulses for ending_soon", () => {
    renderWithProviders(
      <StatusChip label="ENDING SOON" tone="ending_soon" />,
    );
    expect(screen.getByText("ENDING SOON")).toHaveClass("animate-pulse");
  });

  it("renders in dark mode", () => {
    renderWithProviders(<StatusChip label="SOLD" tone="sold" />, {
      theme: "dark",
      forceTheme: true,
    });
    expect(screen.getByText("SOLD")).toBeInTheDocument();
  });
});
