import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { Button } from "./Button";

describe("Button", () => {
  it("renders with the primary variant gradient and merges consumer className via cn", () => {
    renderWithProviders(<Button className="w-32">Bid</Button>);
    const button = screen.getByRole("button", { name: "Bid" });
    expect(button.className).toContain("bg-gradient-to-br");
    expect(button.className).toContain("from-primary");
    expect(button.className).toContain("to-primary-container");
    expect(button.className).toContain("text-on-primary");
    expect(button.className).toContain("w-32");
  });

  it("renders the secondary ghost variant", () => {
    renderWithProviders(<Button variant="secondary">Cancel</Button>);
    const button = screen.getByRole("button", { name: "Cancel" });
    expect(button.className).toContain("bg-surface-container-lowest");
    expect(button.className).toContain("text-on-surface");
    expect(button.className).toContain("shadow-soft");
  });

  it("renders the tertiary text-only variant", () => {
    renderWithProviders(<Button variant="tertiary">Forgot password?</Button>);
    const button = screen.getByRole("button", { name: "Forgot password?" });
    expect(button.className).toContain("text-primary");
    expect(button.className).toContain("hover:underline");
    expect(button.className).not.toContain("bg-gradient-to-br");
  });

  it("disables the button and renders a spinner when loading", () => {
    renderWithProviders(<Button loading leftIcon={<span data-testid="left-icon" />}>Save</Button>);
    const button = screen.getByRole("button", { name: /Save/ });
    expect(button).toBeDisabled();
    expect(screen.queryByTestId("left-icon")).toBeNull();
    expect(button.querySelector(".animate-spin")).not.toBeNull();
  });

  it("renders leftIcon and rightIcon in the correct slots", () => {
    renderWithProviders(
      <Button
        leftIcon={<span data-testid="left">L</span>}
        rightIcon={<span data-testid="right">R</span>}
      >
        With Icons
      </Button>
    );
    expect(screen.getByTestId("left")).toBeInTheDocument();
    expect(screen.getByTestId("right")).toBeInTheDocument();
  });

  it("fires onClick when not loading or disabled", async () => {
    const onClick = vi.fn();
    renderWithProviders(<Button onClick={onClick}>Click me</Button>);
    await userEvent.click(screen.getByRole("button", { name: "Click me" }));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it("applies w-full when fullWidth is true and omits it when false", () => {
    const { rerender } = renderWithProviders(<Button fullWidth>Full</Button>);
    expect(screen.getByRole("button", { name: "Full" })).toHaveClass("w-full");
    rerender(<Button>Auto</Button>);
    expect(screen.getByRole("button", { name: "Auto" })).not.toHaveClass("w-full");
  });
});
