import { describe, expect, it, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { EditableSettingsModal } from "./EditableSettingsModal";

describe("EditableSettingsModal", () => {
  it("opens the modal when trigger is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <EditableSettingsModal
        value={{
          startingBid: 100,
          reservePrice: null,
          buyNowPrice: null,
          durationHours: 24,
          snipeProtect: false,
          snipeWindowMin: null,
        }}
        onSave={vi.fn()}
      />,
    );
    await user.click(screen.getByTestId("editable-settings-trigger"));
    expect(await screen.findByRole("dialog")).toBeInTheDocument();
  });

  it("Save calls onSave with current draft and closes modal", async () => {
    const user = userEvent.setup();
    const onSave = vi.fn().mockResolvedValue(undefined);
    renderWithProviders(
      <EditableSettingsModal
        value={{
          startingBid: 100,
          reservePrice: null,
          buyNowPrice: null,
          durationHours: 24,
          snipeProtect: false,
          snipeWindowMin: null,
        }}
        onSave={onSave}
      />,
    );
    await user.click(screen.getByTestId("editable-settings-trigger"));
    await user.click(await screen.findByTestId("editable-settings-save"));
    expect(onSave).toHaveBeenCalledWith({
      startingBid: 100,
      reservePrice: null,
      buyNowPrice: null,
      durationHours: 24,
      snipeProtect: false,
      snipeWindowMin: null,
    });
  });
});
