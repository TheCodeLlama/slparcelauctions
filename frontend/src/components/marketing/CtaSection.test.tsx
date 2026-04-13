// frontend/src/components/marketing/CtaSection.test.tsx

import { describe, it, expect, beforeEach } from "vitest";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { authHandlers } from "@/test/msw/handlers";
import { CtaSection } from "./CtaSection";

describe("CtaSection", () => {
  beforeEach(() => {
    // Default to unauthenticated; individual tests override with refreshSuccess.
    server.use(authHandlers.refreshUnauthenticated());
  });

  it("renders the sign-up prompt for unauthenticated users", async () => {
    renderWithProviders(<CtaSection />);
    expect(
      screen.getByRole("heading", { name: /ready to acquire your next parcel/i })
    ).toBeInTheDocument();

    const createAccount = screen.getByRole("link", { name: /create free account/i });
    expect(createAccount).toHaveAttribute("href", "/register");

    const viewAuctions = screen.getByRole("link", { name: /view active auctions/i });
    expect(viewAuctions).toHaveAttribute("href", "/browse");
  });

  it("returns null for authenticated users (heading not in DOM)", async () => {
    server.use(authHandlers.refreshSuccess());
    renderWithProviders(<CtaSection />);

    // Wait for the bootstrap useAuth() to resolve, then assert the heading is gone.
    await waitFor(() => {
      expect(
        screen.queryByRole("heading", { name: /ready to acquire your next parcel/i })
      ).toBeNull();
    });
  });
});
