import { describe, it, expect } from "vitest";
import { act } from "react";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { ThemeToggle } from "./ThemeToggle";

describe("ThemeToggle", () => {
  it("renders the sun icon when theme is dark", async () => {
    renderWithProviders(<ThemeToggle />, { theme: "dark" });
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Toggle theme" })).toBeInTheDocument();
    });
    const button = screen.getByRole("button", { name: "Toggle theme" });
    const svg = button.querySelector("svg");
    expect(svg?.getAttribute("class") ?? "").toContain("lucide-sun");
  });

  it("renders the moon icon when theme is light", async () => {
    renderWithProviders(<ThemeToggle />, { theme: "light" });
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Toggle theme" })).toBeInTheDocument();
    });
    const button = screen.getByRole("button", { name: "Toggle theme" });
    const svg = button.querySelector("svg");
    expect(svg?.getAttribute("class") ?? "").toContain("lucide-moon");
  });

  it("flips the documentElement class on click (integration test, not forced theme)", async () => {
    renderWithProviders(<ThemeToggle />, { theme: "dark" });
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Toggle theme" })).toBeInTheDocument();
    });
    expect(document.documentElement.classList.contains("dark")).toBe(true);
    await act(async () => {
      await userEvent.click(screen.getByRole("button", { name: "Toggle theme" }));
    });
    await waitFor(() => {
      expect(document.documentElement.classList.contains("light")).toBe(true);
    });
  });
});
