import { render, screen, fireEvent } from "@testing-library/react";
import { describe, expect, test, vi } from "vitest";
import { MasterMuteRow } from "./MasterMuteRow";

describe("MasterMuteRow", () => {
  test("renders aria-checked reflecting value=false", () => {
    render(<MasterMuteRow value={false} onChange={() => {}} />);
    expect(screen.getByRole("switch")).toHaveAttribute("aria-checked", "false");
  });

  test("renders aria-checked reflecting value=true", () => {
    render(<MasterMuteRow value={true} onChange={() => {}} />);
    expect(screen.getByRole("switch")).toHaveAttribute("aria-checked", "true");
  });

  test("calls onChange with the inverted value on click", () => {
    const onChange = vi.fn();
    render(<MasterMuteRow value={false} onChange={onChange} />);
    fireEvent.click(screen.getByRole("switch"));
    expect(onChange).toHaveBeenCalledWith(true);
  });
});
