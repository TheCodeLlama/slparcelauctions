import { describe, it, expect, vi } from "vitest";
import {
  renderWithProviders,
  screen,
  fireEvent,
  userEvent,
} from "@/test/render";
import { DropZone } from "./DropZone";

describe("DropZone", () => {
  it("calls onFiles when files dropped", () => {
    const onFiles = vi.fn();
    renderWithProviders(<DropZone onFiles={onFiles} accept="image/*" />);
    const drop = screen.getByTestId("drop-zone");
    const file = new File(["x"], "a.png", { type: "image/png" });
    fireEvent.drop(drop, { dataTransfer: { files: [file] } });
    expect(onFiles).toHaveBeenCalledWith([file]);
  });

  it("calls onFiles when files selected via input", async () => {
    const onFiles = vi.fn();
    renderWithProviders(<DropZone onFiles={onFiles} accept="image/*" />);
    const input = screen.getByTestId("drop-zone-input") as HTMLInputElement;
    const file = new File(["x"], "a.png", { type: "image/png" });
    await userEvent.upload(input, file);
    expect(onFiles).toHaveBeenCalled();
  });

  it("is disabled when prop set", () => {
    renderWithProviders(
      <DropZone onFiles={vi.fn()} accept="image/*" disabled />,
    );
    expect(screen.getByTestId("drop-zone-input")).toBeDisabled();
  });
});
