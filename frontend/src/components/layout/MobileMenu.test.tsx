import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { MobileMenu } from "./MobileMenu";

describe("MobileMenu", () => {
  it("renders the drawer when open is true", () => {
    renderWithProviders(<MobileMenu open onClose={() => {}} />);
    expect(screen.getByRole("link", { name: "Browse" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Dashboard" })).toBeInTheDocument();
  });

  it("does not render content when open is false", () => {
    renderWithProviders(<MobileMenu open={false} onClose={() => {}} />);
    expect(screen.queryByRole("link", { name: "Browse" })).toBeNull();
  });

  it("calls onClose when the close button is clicked", async () => {
    const onClose = vi.fn();
    renderWithProviders(<MobileMenu open onClose={onClose} />);
    await userEvent.click(screen.getByRole("button", { name: "Close menu" }));
    expect(onClose).toHaveBeenCalled();
  });

  it("calls onClose when escape is pressed (Headless UI Dialog handles this)", async () => {
    const onClose = vi.fn();
    renderWithProviders(<MobileMenu open onClose={onClose} />);
    await userEvent.keyboard("{Escape}");
    expect(onClose).toHaveBeenCalled();
  });
});
