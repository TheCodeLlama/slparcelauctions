import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { Stepper } from "./Stepper";

describe("Stepper", () => {
  it("renders all step labels", () => {
    renderWithProviders(
      <Stepper steps={["Draft", "Paid", "Verifying", "Active"]} currentIndex={1} />,
    );
    expect(screen.getByText("Draft")).toBeInTheDocument();
    expect(screen.getByText("Paid")).toBeInTheDocument();
    expect(screen.getByText("Verifying")).toBeInTheDocument();
    expect(screen.getByText("Active")).toBeInTheDocument();
  });

  it("marks the current step with aria-current", () => {
    renderWithProviders(<Stepper steps={["A", "B", "C"]} currentIndex={1} />);
    const current = screen.getByText("B").closest("[aria-current]");
    expect(current).toHaveAttribute("aria-current", "step");
  });

  it("marks completed steps visually", () => {
    renderWithProviders(<Stepper steps={["A", "B", "C"]} currentIndex={2} />);
    const first = screen.getByText("A").closest("[data-state]");
    expect(first).toHaveAttribute("data-state", "complete");
  });
});
