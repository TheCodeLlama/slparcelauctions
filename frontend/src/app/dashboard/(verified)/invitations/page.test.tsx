import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import InvitationsPage from "./page";

function seed(items: unknown[]) {
  server.use(
    http.get("*/api/v1/me/invitations", () => HttpResponse.json(items)),
  );
}

describe("InvitationsPage (/dashboard/invitations)", () => {
  it("renders the heading", async () => {
    seed([]);
    renderWithProviders(<InvitationsPage />);
    expect(
      await screen.findByRole("heading", { name: /Invitations/i }),
    ).toBeInTheDocument();
  });

  it("renders the empty state when there are none", async () => {
    seed([]);
    renderWithProviders(<InvitationsPage />);
    await waitFor(() =>
      expect(
        screen.getByText(/No pending invitations/i),
      ).toBeInTheDocument(),
    );
  });

  it("renders an invitation card with accept/decline buttons", async () => {
    seed([
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
    renderWithProviders(<InvitationsPage />);
    await waitFor(() =>
      expect(screen.getByTestId("invitations-list")).toBeInTheDocument(),
    );
    expect(screen.getByText("Mainland Realty")).toBeInTheDocument();
    expect(screen.getByText("Invite agents")).toBeInTheDocument();
    expect(screen.getByTestId(/invitation-accept-/)).toBeInTheDocument();
    expect(screen.getByTestId(/invitation-decline-/)).toBeInTheDocument();
  });
});
