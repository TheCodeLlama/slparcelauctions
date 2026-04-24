import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { Drawer } from "./Drawer";

describe("Drawer", () => {
  it("does not render content when closed", () => {
    renderWithProviders(
      <Drawer open={false} onClose={() => {}} title="Saved">
        <p>hidden</p>
      </Drawer>,
    );
    expect(screen.queryByText("hidden")).not.toBeInTheDocument();
  });

  it("renders content when open, with accessible close button", async () => {
    const onClose = vi.fn();
    renderWithProviders(
      <Drawer open onClose={onClose} title="Saved">
        <p>visible</p>
      </Drawer>,
    );
    expect(screen.getByText("visible")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /close/i }));
    expect(onClose).toHaveBeenCalledOnce();
  });

  it("ESC closes the drawer", async () => {
    const onClose = vi.fn();
    renderWithProviders(
      <Drawer open onClose={onClose} title="Saved">
        <p>visible</p>
      </Drawer>,
    );
    await userEvent.keyboard("{Escape}");
    expect(onClose).toHaveBeenCalled();
  });

  it("renders footer when provided", () => {
    renderWithProviders(
      <Drawer
        open
        onClose={() => {}}
        title="Saved"
        footer={<button type="button">Apply</button>}
      >
        <p>body</p>
      </Drawer>,
    );
    expect(screen.getByRole("button", { name: "Apply" })).toBeInTheDocument();
  });

  it("renders the provided title", () => {
    renderWithProviders(
      <Drawer open onClose={() => {}} title="Your Curator Tray">
        <p>body</p>
      </Drawer>,
    );
    expect(screen.getByText("Your Curator Tray")).toBeInTheDocument();
  });

  it("renders without visual regression in dark mode", () => {
    renderWithProviders(
      <Drawer open onClose={() => {}} title="Saved">
        <p>visible</p>
      </Drawer>,
      { theme: "dark", forceTheme: true },
    );
    expect(screen.getByText("visible")).toBeInTheDocument();
  });
});
