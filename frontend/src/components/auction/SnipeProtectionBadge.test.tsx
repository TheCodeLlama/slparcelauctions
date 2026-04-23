import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { SnipeProtectionBadge } from "./SnipeProtectionBadge";

describe("SnipeProtectionBadge", () => {
  it("renders the window duration label", () => {
    renderWithProviders(<SnipeProtectionBadge minutes={15} />);
    expect(screen.getByTestId("snipe-protection-badge")).toHaveTextContent(
      "Snipe 15m",
    );
  });

  it("renders a shield icon", () => {
    const { container } = renderWithProviders(
      <SnipeProtectionBadge minutes={10} />,
    );
    // lucide-react renders an SVG with lucide-shield class
    expect(container.querySelector("svg")).not.toBeNull();
  });

  it("renders different duration values verbatim", () => {
    renderWithProviders(<SnipeProtectionBadge minutes={5} />);
    expect(screen.getByTestId("snipe-protection-badge")).toHaveTextContent(
      "Snipe 5m",
    );
  });
});
