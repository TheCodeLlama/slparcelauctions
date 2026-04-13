// frontend/src/components/marketing/Hero.test.tsx

import { describe, it, expect, beforeEach } from "vitest";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { authHandlers } from "@/test/msw/handlers";
import { Hero } from "./Hero";

describe("Hero", () => {
  beforeEach(() => {
    // Default state: unauthenticated. The setup file already registers
    // refreshUnauthenticated as the default; explicit here for clarity.
    server.use(authHandlers.refreshUnauthenticated());
  });

  it("renders the headline and primary CTA", async () => {
    renderWithProviders(<Hero />);
    expect(
      screen.getByRole("heading", { name: /buy & sell second life land at auction/i })
    ).toBeInTheDocument();

    const browseLink = screen.getByRole("link", { name: /browse listings/i });
    expect(browseLink).toHaveAttribute("href", "/browse");
  });

  it("renders 'Start Selling → /register' for unauthenticated users", async () => {
    renderWithProviders(<Hero />);
    await waitFor(() => {
      const startSellingLink = screen.getByRole("link", { name: /start selling/i });
      expect(startSellingLink).toHaveAttribute("href", "/register");
    });
  });

  it("renders 'Go to Dashboard → /dashboard' for authenticated users", async () => {
    server.use(authHandlers.refreshSuccess());
    renderWithProviders(<Hero />);
    await waitFor(() => {
      const dashboardLink = screen.getByRole("link", { name: /go to dashboard/i });
      expect(dashboardLink).toHaveAttribute("href", "/dashboard");
    });
  });

  it("renders the LIVE AUCTIONS ACTIVE pill", () => {
    renderWithProviders(<Hero />);
    expect(screen.getByText(/live auctions active/i)).toBeInTheDocument();
  });
});
