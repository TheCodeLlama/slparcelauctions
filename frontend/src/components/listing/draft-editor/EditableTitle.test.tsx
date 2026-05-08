import { describe, expect, it, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { EditableTitle } from "./EditableTitle";

describe("EditableTitle", () => {
  it("idle render exposes the value as a button", () => {
    renderWithProviders(<EditableTitle value="Old" onSave={vi.fn()} />);
    expect(screen.getByTestId("editable-title")).toHaveTextContent("Old");
  });

  it("clicks to edit, saves on blur, calls onSave with the new value", async () => {
    const user = userEvent.setup();
    const onSave = vi.fn().mockResolvedValue(undefined);
    renderWithProviders(<EditableTitle value="Old" onSave={onSave} />);
    await user.click(screen.getByTestId("editable-title"));
    const input = await screen.findByTestId("editable-title-input");
    await user.clear(input);
    await user.type(input, "New title");
    (input as HTMLInputElement).blur();
    await vi.waitFor(() => expect(onSave).toHaveBeenCalledWith("New title"));
  });

  it("Esc cancels and discards changes", async () => {
    const user = userEvent.setup();
    const onSave = vi.fn();
    renderWithProviders(<EditableTitle value="Old" onSave={onSave} />);
    await user.click(screen.getByTestId("editable-title"));
    const input = await screen.findByTestId("editable-title-input");
    await user.clear(input);
    await user.type(input, "Garbage{Escape}");
    await screen.findByTestId("editable-title");
    expect(screen.getByTestId("editable-title")).toHaveTextContent("Old");
    expect(onSave).not.toHaveBeenCalled();
  });

  it("renders inline error on save failure, keeps editor open", async () => {
    const user = userEvent.setup();
    const onSave = vi.fn().mockRejectedValue(new Error("Title too long"));
    renderWithProviders(<EditableTitle value="Old" onSave={onSave} />);
    await user.click(screen.getByTestId("editable-title"));
    const input = await screen.findByTestId("editable-title-input");
    await user.clear(input);
    await user.type(input, "x");
    (input as HTMLInputElement).blur();
    expect(await screen.findByText(/Title too long/)).toBeInTheDocument();
    expect(screen.getByTestId("editable-title-input")).toBeInTheDocument();
  });
});
