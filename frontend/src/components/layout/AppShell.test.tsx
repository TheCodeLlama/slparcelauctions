import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { AppShell } from "./AppShell";

vi.mock("@/lib/auth", () => ({
  useAuth: vi.fn(() => ({ status: "unauthenticated", user: null })),
}));

describe("AppShell", () => {
  it("renders Header, the children inside <main>, and Footer in order", () => {
    renderWithProviders(
      <AppShell>
        <div data-testid="page-content">page</div>
      </AppShell>
    );
    expect(screen.getByRole("link", { name: "SLPA" })).toBeInTheDocument(); // Header
    expect(screen.getByTestId("page-content")).toBeInTheDocument();          // children inside main
    expect(screen.getByText(/SLPA. Not affiliated/)).toBeInTheDocument();    // Footer
  });
});
