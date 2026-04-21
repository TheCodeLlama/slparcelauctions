import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MyBidsFilterTabs } from "./MyBidsFilterTabs";

describe("MyBidsFilterTabs", () => {
  it("renders all four options as radio buttons", () => {
    render(<MyBidsFilterTabs value="all" onChange={() => {}} />);
    expect(screen.getByRole("radio", { name: "All" })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Active" })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Won" })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Lost" })).toBeInTheDocument();
  });

  it("marks the selected option as aria-checked", () => {
    render(<MyBidsFilterTabs value="active" onChange={() => {}} />);
    expect(screen.getByRole("radio", { name: "Active" })).toHaveAttribute(
      "aria-checked",
      "true",
    );
    expect(screen.getByRole("radio", { name: "All" })).toHaveAttribute(
      "aria-checked",
      "false",
    );
  });

  it("calls onChange with the option id when clicked", async () => {
    const onChange = vi.fn();
    render(<MyBidsFilterTabs value="all" onChange={onChange} />);
    await userEvent.click(screen.getByRole("radio", { name: "Won" }));
    expect(onChange).toHaveBeenCalledWith("won");
  });
});
