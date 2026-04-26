import { render, screen, fireEvent } from "@testing-library/react";
import { describe, expect, test, vi } from "vitest";
import { GroupToggleRow } from "./GroupToggleRow";

describe("GroupToggleRow", () => {
  test("renders label and subtext", () => {
    render(<GroupToggleRow
      group="bidding" label="Bidding" subtext="Outbid, proxy exhausted"
      value={true} mutedDisabled={false} onChange={() => {}} />);
    expect(screen.getByText("Bidding")).toBeInTheDocument();
    expect(screen.getByText("Outbid, proxy exhausted")).toBeInTheDocument();
  });

  test("aria-checked reflects value when not muted", () => {
    render(<GroupToggleRow
      group="bidding" label="Bidding" subtext="x"
      value={true} mutedDisabled={false} onChange={() => {}} />);
    expect(screen.getByRole("switch")).toHaveAttribute("aria-checked", "true");
  });

  test("click fires onChange when not muted", () => {
    const onChange = vi.fn();
    render(<GroupToggleRow
      group="bidding" label="Bidding" subtext="x"
      value={false} mutedDisabled={false} onChange={onChange} />);
    fireEvent.click(screen.getByRole("switch"));
    expect(onChange).toHaveBeenCalledWith(true);
  });

  test("disabled state preserves checked value (the load-bearing invariant)", () => {
    render(<GroupToggleRow
      group="bidding" label="Bidding" subtext="x"
      value={true} mutedDisabled={true} onChange={() => {}} />);
    const sw = screen.getByRole("switch");
    expect(sw).toHaveAttribute("aria-checked", "true");
    expect(sw).toBeDisabled();
  });

  test("click does NOT fire onChange when mutedDisabled", () => {
    const onChange = vi.fn();
    render(<GroupToggleRow
      group="bidding" label="Bidding" subtext="x"
      value={true} mutedDisabled={true} onChange={onChange} />);
    fireEvent.click(screen.getByRole("switch"));
    expect(onChange).not.toHaveBeenCalled();
  });
});
