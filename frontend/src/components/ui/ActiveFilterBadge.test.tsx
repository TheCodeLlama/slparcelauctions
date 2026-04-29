import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { ActiveFilterBadge } from "./ActiveFilterBadge";

describe("ActiveFilterBadge", () => {
  it("renders label and calls onRemove when X clicked", async () => {
    const onRemove = vi.fn();
    renderWithProviders(
      <ActiveFilterBadge label="Maturity: Adult" onRemove={onRemove} />,
    );
    expect(screen.getByText("Maturity: Adult")).toBeInTheDocument();
    await userEvent.click(
      screen.getByRole("button", { name: /remove filter/i }),
    );
    expect(onRemove).toHaveBeenCalledOnce();
  });

  it("renders in dark mode without error", () => {
    renderWithProviders(
      <ActiveFilterBadge label="Test" onRemove={() => {}} />,
      { theme: "dark", forceTheme: true },
    );
    expect(screen.getByText("Test")).toBeInTheDocument();
  });
});
