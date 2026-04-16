import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { server } from "@/test/msw/server";
import { userHandlers } from "@/test/msw/handlers";
import { VerifiedOverview } from "./VerifiedOverview";

describe("VerifiedOverview", () => {
  it("renders identity card, uploader, and edit form when /me resolves", async () => {
    server.use(userHandlers.meVerified());
    renderWithProviders(<VerifiedOverview />, { auth: "authenticated" });

    // Wait for loading to finish and identity card to render
    expect(
      await screen.findByText("Second Life Identity"),
    ).toBeInTheDocument();
    expect(screen.getByText("Profile Picture")).toBeInTheDocument();
    expect(screen.getByText("Edit Profile")).toBeInTheDocument();
  });

  it("shows loading spinner while /me is pending", () => {
    // Don't register a handler for /me — the query will stay pending
    // because MSW's onUnhandledRequest is "error", so we need a handler
    // that never resolves.
    server.use(
      userHandlers.meVerified(),
    );
    // Render without auth so the query doesn't fire (enabled: session === "authenticated")
    renderWithProviders(<VerifiedOverview />, { auth: "anonymous" });

    expect(screen.getByText("Loading profile...")).toBeInTheDocument();
  });
});
