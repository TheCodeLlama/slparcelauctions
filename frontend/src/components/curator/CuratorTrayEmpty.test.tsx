import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { CuratorTrayEmpty } from "./CuratorTrayEmpty";

describe("CuratorTrayEmpty", () => {
  it("renders the empty-state copy and a Browse CTA", () => {
    renderWithProviders(<CuratorTrayEmpty />);
    expect(
      screen.getByText(/save parcels to review them here/i),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /browse listings/i }),
    ).toHaveAttribute("href", "/browse");
  });

  it("renders a button CTA when an onBrowse handler is provided", async () => {
    const onBrowse = vi.fn();
    renderWithProviders(<CuratorTrayEmpty onBrowse={onBrowse} />);
    const btn = screen.getByRole("button", { name: /browse listings/i });
    await userEvent.click(btn);
    expect(onBrowse).toHaveBeenCalledTimes(1);
  });

  it("renders in dark mode without crashing", () => {
    renderWithProviders(<CuratorTrayEmpty />, {
      theme: "dark",
      forceTheme: true,
    });
    expect(
      screen.getByText(/save parcels to review them here/i),
    ).toBeInTheDocument();
  });
});
