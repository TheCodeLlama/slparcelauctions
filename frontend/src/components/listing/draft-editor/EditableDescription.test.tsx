import { describe, expect, it, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { EditableDescription } from "./EditableDescription";

describe("EditableDescription", () => {
  it("clicks to edit, types into the textarea, saves on blur", async () => {
    const user = userEvent.setup();
    const onSave = vi.fn().mockResolvedValue(undefined);
    renderWithProviders(
      <EditableDescription value="Old desc" onSave={onSave} />,
    );
    await user.click(screen.getByTestId("editable-description"));
    const ta = await screen.findByTestId("editable-description-input");
    await user.clear(ta);
    await user.type(ta, "Lovely view");
    (ta as HTMLTextAreaElement).blur();
    await vi.waitFor(() => expect(onSave).toHaveBeenCalledWith("Lovely view"));
  });

  it("Enter inserts a newline (does not save)", async () => {
    const user = userEvent.setup();
    const onSave = vi.fn().mockResolvedValue(undefined);
    renderWithProviders(
      <EditableDescription value="Line 1" onSave={onSave} />,
    );
    await user.click(screen.getByTestId("editable-description"));
    const ta = (await screen.findByTestId(
      "editable-description-input",
    )) as HTMLTextAreaElement;
    await user.click(ta);
    await user.keyboard("{Enter}Line 2");
    expect(ta.value).toContain("\n");
    expect(onSave).not.toHaveBeenCalled();
  });

  it("Esc cancels", async () => {
    const user = userEvent.setup();
    const onSave = vi.fn();
    renderWithProviders(
      <EditableDescription value="Old" onSave={onSave} />,
    );
    await user.click(screen.getByTestId("editable-description"));
    const ta = await screen.findByTestId("editable-description-input");
    await user.click(ta);
    await user.keyboard("{Escape}");
    await screen.findByTestId("editable-description");
    expect(onSave).not.toHaveBeenCalled();
  });
});
