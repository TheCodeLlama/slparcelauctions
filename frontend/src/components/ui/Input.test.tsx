import { useState } from "react";
import { describe, it, expect } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { Input } from "./Input";

describe("Input", () => {
  it("associates label with input via htmlFor/id and merges consumer className", () => {
    renderWithProviders(<Input label="Email" className="max-w-md" />);
    const input = screen.getByLabelText("Email");
    expect(input).toBeInTheDocument();
    expect(input.tagName).toBe("INPUT");
    expect(input.className).toContain("max-w-md");
    expect(input.className).toContain("bg-surface-container-low");
  });

  it("shows error text and applies the error ring when error prop is set", () => {
    renderWithProviders(<Input label="Email" error="must be a valid email" helperText="we never share this" />);
    expect(screen.getByText("must be a valid email")).toBeInTheDocument();
    expect(screen.queryByText("we never share this")).toBeNull();
    const input = screen.getByLabelText("Email");
    expect(input.className).toContain("ring-error");
  });

  it("renders leftIcon with appropriate padding offset", () => {
    renderWithProviders(
      <Input label="Search" leftIcon={<span data-testid="left">🔍</span>} />
    );
    expect(screen.getByTestId("left")).toBeInTheDocument();
    const input = screen.getByLabelText("Search");
    expect(input.className).toContain("pl-10");
  });

  it("works as a controlled input", async () => {
    function Wrapper() {
      const [value, setValue] = useState("hello");
      return <Input label="Name" value={value} onChange={(e) => setValue(e.target.value)} />;
    }
    renderWithProviders(<Wrapper />);
    const input = screen.getByLabelText("Name") as HTMLInputElement;
    expect(input.value).toBe("hello");
    await userEvent.clear(input);
    await userEvent.type(input, "world");
    expect(input.value).toBe("world");
  });

  it("works as an uncontrolled input with defaultValue", () => {
    renderWithProviders(<Input label="Name" defaultValue="default" />);
    const input = screen.getByLabelText("Name") as HTMLInputElement;
    expect(input.value).toBe("default");
  });
});
