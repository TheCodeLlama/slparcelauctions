import { describe, expect, it, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { PermissionToggleRow } from "./PermissionToggleRow";

describe("PermissionToggleRow", () => {
  it("renders the permission label and description", () => {
    renderWithProviders(
      <PermissionToggleRow
        permission="INVITE_AGENTS"
        checked={false}
        onChange={() => {}}
      />,
    );
    expect(screen.getByText("Invite agents")).toBeInTheDocument();
    expect(
      screen.getByText(/Send invitations to new agents/i),
    ).toBeInTheDocument();
  });

  it("renders an unchecked checkbox when checked=false", () => {
    renderWithProviders(
      <PermissionToggleRow
        permission="REMOVE_AGENTS"
        checked={false}
        onChange={() => {}}
      />,
    );
    const checkbox = screen.getByTestId("permission-checkbox-REMOVE_AGENTS") as HTMLInputElement;
    expect(checkbox.checked).toBe(false);
  });

  it("renders a checked checkbox when checked=true", () => {
    renderWithProviders(
      <PermissionToggleRow
        permission="EDIT_GROUP_PROFILE"
        checked={true}
        onChange={() => {}}
      />,
    );
    const checkbox = screen.getByTestId("permission-checkbox-EDIT_GROUP_PROFILE") as HTMLInputElement;
    expect(checkbox.checked).toBe(true);
  });

  it("calls onChange with the new boolean when toggled", async () => {
    const onChange = vi.fn();
    renderWithProviders(
      <PermissionToggleRow
        permission="CONFIGURE_FEES"
        checked={false}
        onChange={onChange}
      />,
    );
    const checkbox = screen.getByTestId("permission-checkbox-CONFIGURE_FEES");
    await userEvent.click(checkbox);
    expect(onChange).toHaveBeenCalledWith(true);
  });

  it("disables the input and does not call onChange when disabled", async () => {
    const onChange = vi.fn();
    renderWithProviders(
      <PermissionToggleRow
        permission="INVITE_AGENTS"
        checked={false}
        onChange={onChange}
        disabled
      />,
    );
    const checkbox = screen.getByTestId("permission-checkbox-INVITE_AGENTS") as HTMLInputElement;
    expect(checkbox.disabled).toBe(true);
    await userEvent.click(checkbox);
    expect(onChange).not.toHaveBeenCalled();
  });
});
