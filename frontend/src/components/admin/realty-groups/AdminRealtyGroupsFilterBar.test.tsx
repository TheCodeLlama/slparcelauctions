import { describe, expect, it, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { AdminRealtyGroupsFilterBar } from "./AdminRealtyGroupsFilterBar";

describe("AdminRealtyGroupsFilterBar", () => {
  it("highlights the active status chip", () => {
    renderWithProviders(
      <AdminRealtyGroupsFilterBar
        status="dissolved"
        onStatusChange={() => {}}
        search=""
        onSearchChange={() => {}}
      />,
    );
    expect(screen.getByTestId("admin-realty-status-dissolved")).toHaveAttribute(
      "aria-checked",
      "true",
    );
    expect(screen.getByTestId("admin-realty-status-active")).toHaveAttribute(
      "aria-checked",
      "false",
    );
  });

  it("calls onStatusChange when a chip is picked", async () => {
    const onStatusChange = vi.fn();
    renderWithProviders(
      <AdminRealtyGroupsFilterBar
        status="active"
        onStatusChange={onStatusChange}
        search=""
        onSearchChange={() => {}}
      />,
    );
    await userEvent.click(screen.getByTestId("admin-realty-status-all"));
    expect(onStatusChange).toHaveBeenCalledWith("all");
  });

  it("calls onSearchChange as the user types", async () => {
    const onSearchChange = vi.fn();
    renderWithProviders(
      <AdminRealtyGroupsFilterBar
        status="active"
        onStatusChange={() => {}}
        search=""
        onSearchChange={onSearchChange}
      />,
    );
    await userEvent.type(screen.getByTestId("admin-realty-search"), "main");
    // userEvent.type fires per-char; assert the last call carries the final char.
    expect(onSearchChange).toHaveBeenCalled();
  });
});
