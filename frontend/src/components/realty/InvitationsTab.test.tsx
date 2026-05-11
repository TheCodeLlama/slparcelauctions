import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { InvitationsTab } from "./InvitationsTab";

const GROUP_ID = "00000000-0000-0000-0000-000000000001";
const INV_ID = "22222222-2222-2222-2222-222222222222";

function setupInvitations(items: unknown[]) {
  server.use(
    http.get(`*/api/v1/realty-groups/${GROUP_ID}/invitations`, () =>
      HttpResponse.json(items),
    ),
  );
}

describe("InvitationsTab", () => {
  it("renders an empty state when there are no invitations", async () => {
    setupInvitations([]);
    renderWithProviders(<InvitationsTab groupPublicId={GROUP_ID} />);
    await waitFor(() =>
      expect(screen.getByText(/No invitations yet/i)).toBeInTheDocument(),
    );
  });

  it("renders invitation rows with status badges", async () => {
    setupInvitations([
      {
        publicId: INV_ID,
        groupPublicId: GROUP_ID,
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
    renderWithProviders(<InvitationsTab groupPublicId={GROUP_ID} />);
    await waitFor(() =>
      expect(
        screen.getByTestId(`invitation-row-${INV_ID}`),
      ).toBeInTheDocument(),
    );
    expect(screen.getByText("PENDING")).toBeInTheDocument();
    expect(screen.getByText(/Invited by Leader/i)).toBeInTheDocument();
  });

  it("renders a Revoke button only on PENDING invitations", async () => {
    setupInvitations([
      {
        publicId: "aaaa1111-1111-1111-1111-111111111111",
        groupPublicId: GROUP_ID,
        groupName: "Mainland Realty",
        groupSlug: "mainland-realty",
        invitedByPublicId: "11111111-1111-1111-1111-111111111111",
        invitedByDisplayName: "Leader",
        permissions: [],
        status: "ACCEPTED",
        expiresAt: "2026-05-18T10:00:00Z",
        createdAt: "2026-05-11T10:00:00Z",
        respondedAt: "2026-05-12T10:00:00Z",
      },
    ]);
    renderWithProviders(<InvitationsTab groupPublicId={GROUP_ID} />);
    await waitFor(() =>
      expect(
        screen.getByTestId("invitation-row-aaaa1111-1111-1111-1111-111111111111"),
      ).toBeInTheDocument(),
    );
    expect(
      screen.queryByTestId(/invitation-revoke-/),
    ).not.toBeInTheDocument();
  });

  it("opens the send-invitation modal on click", async () => {
    setupInvitations([]);
    renderWithProviders(<InvitationsTab groupPublicId={GROUP_ID} />);
    await userEvent.click(screen.getByTestId("invitations-send-button"));
    expect(screen.getByTestId("invite-form-username")).toBeInTheDocument();
  });
});
