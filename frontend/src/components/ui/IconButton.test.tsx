import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { IconButton } from "./IconButton";
import { Bell } from "./icons";

describe("IconButton", () => {
  it("requires an aria-label and renders it on the button", () => {
    renderWithProviders(
      <IconButton aria-label="Notifications">
        <Bell />
      </IconButton>
    );
    expect(screen.getByRole("button", { name: "Notifications" })).toBeInTheDocument();
  });

  it("fires onClick", async () => {
    const onClick = vi.fn();
    renderWithProviders(
      <IconButton aria-label="Toggle theme" onClick={onClick}>
        <Bell />
      </IconButton>
    );
    await userEvent.click(screen.getByRole("button", { name: "Toggle theme" }));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it("renders the secondary variant by default and merges consumer className", () => {
    renderWithProviders(
      <IconButton aria-label="Search" className="md:hidden">
        <Bell />
      </IconButton>
    );
    const button = screen.getByRole("button", { name: "Search" });
    expect(button.className).toContain("rounded-full");
    expect(button.className).toContain("md:hidden");
  });

  it("bakes the 1.5 stroke width into the SVG via the [&_svg]:stroke-[1.5] selector class", () => {
    renderWithProviders(
      <IconButton aria-label="Bell">
        <Bell />
      </IconButton>
    );
    const button = screen.getByRole("button", { name: "Bell" });
    expect(button.className).toContain("[&_svg]:stroke-[1.5]");
  });
});
