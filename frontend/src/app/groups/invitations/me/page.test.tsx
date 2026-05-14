import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import MyInvitationsPage from "./page";

function seedInvitations(items: unknown[]) {
  server.use(
    http.get("*/api/v1/me/invitations", () => HttpResponse.json(items)),
  );
}

describe("/groups/invitations/me", () => {
  it("renders the invitations recipient page shell once loaded", async () => {
    seedInvitations([]);
    renderWithProviders(<MyInvitationsPage />, { auth: "authenticated" });
    await waitFor(() =>
      expect(
        screen.getByTestId("invitations-recipient-page"),
      ).toBeInTheDocument(),
    );
  });

  it("renders an invitation card per pending invitation", async () => {
    seedInvitations([
      {
        publicId: "22222222-2222-2222-2222-222222222222",
        groupPublicId: "00000000-0000-0000-0000-000000000001",
        groupName: "Mainland Realty",
        groupSlug: "mainland-realty",
        invitedByPublicId: "11111111-1111-1111-1111-111111111111",
        invitedByDisplayName: "Leader",
        permissions: ["INVITE_AGENTS"],
        status: "PENDING",
        expiresAt: "2026-05-18T10:00:00Z",
        createdAt: "2026-05-11T10:00:00Z",
        respondedAt: null,
      },
    ]);
    renderWithProviders(<MyInvitationsPage />, { auth: "authenticated" });
    await waitFor(() =>
      expect(screen.getByText("Mainland Realty")).toBeInTheDocument(),
    );
  });
});
