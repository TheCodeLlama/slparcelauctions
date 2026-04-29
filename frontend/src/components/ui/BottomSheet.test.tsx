import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { BottomSheet } from "./BottomSheet";

describe("BottomSheet", () => {
  it("does not render content when closed", () => {
    renderWithProviders(
      <BottomSheet open={false} onClose={() => {}} title="Filters">
        <p>hidden</p>
      </BottomSheet>,
    );
    expect(screen.queryByText("hidden")).not.toBeInTheDocument();
  });

  it("renders content when open, with accessible close button", async () => {
    const onClose = vi.fn();
    renderWithProviders(
      <BottomSheet open onClose={onClose} title="Filters">
        <p>visible</p>
      </BottomSheet>,
    );
    expect(screen.getByText("visible")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /close/i }));
    expect(onClose).toHaveBeenCalledOnce();
  });

  it("ESC closes the sheet", async () => {
    const onClose = vi.fn();
    renderWithProviders(
      <BottomSheet open onClose={onClose} title="Filters">
        <p>visible</p>
      </BottomSheet>,
    );
    await userEvent.keyboard("{Escape}");
    expect(onClose).toHaveBeenCalled();
  });

  it("renders footer when provided", () => {
    renderWithProviders(
      <BottomSheet
        open
        onClose={() => {}}
        title="Filters"
        footer={<button type="button">Apply</button>}
      >
        <p>body</p>
      </BottomSheet>,
    );
    expect(screen.getByRole("button", { name: "Apply" })).toBeInTheDocument();
  });
});
