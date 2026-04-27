import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueueCard } from "./QueueCard";

describe("QueueCard", () => {
  it("renders label, value, subtext", () => {
    render(<QueueCard label="Test" value={7} tone="fraud" subtext="Click me" />);
    expect(screen.getByText("Test")).toBeInTheDocument();
    expect(screen.getByText("7")).toBeInTheDocument();
    expect(screen.getByText("Click me")).toBeInTheDocument();
  });

  it("wraps in a Link when href is provided", () => {
    render(<QueueCard label="X" value={1} tone="fraud" subtext="y" href="/admin/fraud-flags" />);
    expect(screen.getByRole("link")).toHaveAttribute("href", "/admin/fraud-flags");
  });

  it("does not render link when no href", () => {
    render(<QueueCard label="X" value={1} tone="warning" subtext="y" />);
    expect(screen.queryByRole("link")).toBeNull();
  });
});
