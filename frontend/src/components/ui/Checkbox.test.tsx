// frontend/src/components/ui/Checkbox.test.tsx
import { describe, it, expect } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { Checkbox } from "./Checkbox";

describe("Checkbox", () => {
  it("renders with a label", () => {
    renderWithProviders(<Checkbox label="I agree to the terms" />);
    expect(screen.getByLabelText("I agree to the terms")).toBeInTheDocument();
  });

  it("toggles checked state when clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Checkbox label="Accept" />);
    const checkbox = screen.getByRole("checkbox") as HTMLInputElement;

    expect(checkbox.checked).toBe(false);
    await user.click(checkbox);
    expect(checkbox.checked).toBe(true);
  });

  it("displays an error message when error prop is set", () => {
    renderWithProviders(<Checkbox label="Accept" error="You must accept" />);
    expect(screen.getByText("You must accept")).toBeInTheDocument();
  });

  it("supports React node as label (e.g., embedded link)", () => {
    renderWithProviders(
      <Checkbox label={<>I agree to the <a href="/terms">Terms</a></>} />
    );
    expect(screen.getByText("Terms")).toBeInTheDocument();
  });
});
