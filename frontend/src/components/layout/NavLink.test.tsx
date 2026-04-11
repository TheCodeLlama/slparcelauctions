import { describe, it, expect, vi, beforeEach } from "vitest";
import { usePathname } from "next/navigation";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { NavLink } from "./NavLink";

const mockedUsePathname = vi.mocked(usePathname);

describe("NavLink", () => {
  beforeEach(() => {
    mockedUsePathname.mockReset();
    mockedUsePathname.mockReturnValue("/");
  });

  it("renders a link with the correct href", () => {
    renderWithProviders(
      <NavLink variant="header" href="/browse">Browse</NavLink>
    );
    const link = screen.getByRole("link", { name: "Browse" });
    expect(link.getAttribute("href")).toBe("/browse");
  });

  it("marks the link active and sets aria-current when pathname matches", () => {
    mockedUsePathname.mockReturnValue("/browse");
    renderWithProviders(
      <NavLink variant="header" href="/browse">Browse</NavLink>
    );
    const link = screen.getByRole("link", { name: "Browse" });
    expect(link.className).toContain("text-primary");
    expect(link.getAttribute("aria-current")).toBe("page");
  });

  it("renders inactive when pathname does not match", () => {
    mockedUsePathname.mockReturnValue("/dashboard");
    renderWithProviders(
      <NavLink variant="header" href="/browse">Browse</NavLink>
    );
    const link = screen.getByRole("link", { name: "Browse" });
    expect(link.className).toContain("text-on-surface-variant");
    expect(link.getAttribute("aria-current")).toBeNull();
  });

  it("fires the onClick handler when provided (mobile variant uses this to close)", async () => {
    const onClick = vi.fn();
    renderWithProviders(
      <NavLink variant="mobile" href="/browse" onClick={onClick}>Browse</NavLink>
    );
    await userEvent.click(screen.getByRole("link", { name: "Browse" }));
    expect(onClick).toHaveBeenCalledTimes(1);
  });
});
