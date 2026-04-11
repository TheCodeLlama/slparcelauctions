import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { Dropdown } from "./Dropdown";

describe("Dropdown", () => {
  it("opens the menu when the trigger is clicked", async () => {
    const onProfile = vi.fn();
    renderWithProviders(
      <Dropdown
        trigger={<button>Open</button>}
        items={[{ label: "Profile", onSelect: onProfile }]}
      />
    );
    expect(screen.queryByText("Profile")).toBeNull();
    await userEvent.click(screen.getByRole("button", { name: "Open" }));
    expect(screen.getByText("Profile")).toBeInTheDocument();
  });

  it("fires onSelect and closes the menu when an item is clicked", async () => {
    const onProfile = vi.fn();
    renderWithProviders(
      <Dropdown
        trigger={<button>Open</button>}
        items={[{ label: "Profile", onSelect: onProfile }]}
      />
    );
    await userEvent.click(screen.getByRole("button", { name: "Open" }));
    await userEvent.click(screen.getByText("Profile"));
    expect(onProfile).toHaveBeenCalledTimes(1);
  });

  it("does not fire onSelect for disabled items", async () => {
    const onSelect = vi.fn();
    renderWithProviders(
      <Dropdown
        trigger={<button>Open</button>}
        items={[{ label: "Disabled", onSelect, disabled: true }]}
      />
    );
    await userEvent.click(screen.getByRole("button", { name: "Open" }));
    await userEvent.click(screen.getByText("Disabled"));
    expect(onSelect).not.toHaveBeenCalled();
  });

  it("renders danger items with text-error", async () => {
    renderWithProviders(
      <Dropdown
        trigger={<button>Open</button>}
        items={[{ label: "Sign out", onSelect: () => {}, danger: true }]}
      />
    );
    await userEvent.click(screen.getByRole("button", { name: "Open" }));
    const item = screen.getByText("Sign out");
    expect(item.className).toContain("text-error");
  });

  it("closes the menu when escape is pressed", async () => {
    renderWithProviders(
      <Dropdown
        trigger={<button>Open</button>}
        items={[{ label: "Profile", onSelect: () => {} }]}
      />
    );
    await userEvent.click(screen.getByRole("button", { name: "Open" }));
    expect(screen.getByText("Profile")).toBeInTheDocument();
    await userEvent.keyboard("{Escape}");
    expect(screen.queryByText("Profile")).toBeNull();
  });
});
