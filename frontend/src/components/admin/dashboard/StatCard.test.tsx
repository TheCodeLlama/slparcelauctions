import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { StatCard } from "./StatCard";

describe("StatCard", () => {
  it("renders label and string value", () => {
    render(<StatCard label="Test label" value="L$ 1,000" />);
    expect(screen.getByText("Test label")).toBeInTheDocument();
    expect(screen.getByText("L$ 1,000")).toBeInTheDocument();
  });

  it("renders numeric value", () => {
    render(<StatCard label="Count" value={42} />);
    expect(screen.getByText("42")).toBeInTheDocument();
  });
});
