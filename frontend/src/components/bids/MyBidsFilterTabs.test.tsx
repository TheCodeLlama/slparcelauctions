import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MyBidsFilterTabs } from "./MyBidsFilterTabs";

describe("MyBidsFilterTabs", () => {
  it("renders all four tabs", () => {
    render(<MyBidsFilterTabs value="all" onChange={() => {}} />);
    expect(screen.getByRole("tab", { name: "All" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Active" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Won" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Lost" })).toBeInTheDocument();
  });

  it("marks the selected tab as aria-selected", () => {
    render(<MyBidsFilterTabs value="active" onChange={() => {}} />);
    expect(screen.getByRole("tab", { name: "Active" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(screen.getByRole("tab", { name: "All" })).toHaveAttribute(
      "aria-selected",
      "false",
    );
  });

  it("calls onChange with the tab id when clicked", async () => {
    const onChange = vi.fn();
    render(<MyBidsFilterTabs value="all" onChange={onChange} />);
    await userEvent.click(screen.getByRole("tab", { name: "Won" }));
    expect(onChange).toHaveBeenCalledWith("won");
  });
});
