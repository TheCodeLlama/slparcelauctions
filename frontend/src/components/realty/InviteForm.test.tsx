import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { InviteForm } from "./InviteForm";

const GROUP_ID = "00000000-0000-0000-0000-000000000001";

describe("InviteForm", () => {
  it("renders a username field and the four permission toggles", () => {
    renderWithProviders(<InviteForm groupPublicId={GROUP_ID} />);
    expect(screen.getByTestId("invite-form-username")).toBeInTheDocument();
    expect(
      screen.getByTestId("permission-row-INVITE_AGENTS"),
    ).toBeInTheDocument();
    expect(
      screen.getByTestId("permission-row-REMOVE_AGENTS"),
    ).toBeInTheDocument();
    expect(
      screen.getByTestId("permission-row-EDIT_GROUP_PROFILE"),
    ).toBeInTheDocument();
    expect(
      screen.getByTestId("permission-row-CONFIGURE_FEES"),
    ).toBeInTheDocument();
  });

  it("shows a required error when username is empty on submit", async () => {
    renderWithProviders(<InviteForm groupPublicId={GROUP_ID} />);
    await userEvent.click(screen.getByTestId("invite-form-submit"));
    expect(
      await screen.findByText(/Username is required/i),
    ).toBeInTheDocument();
  });

  it("submits with selected permissions", async () => {
    let captured: { invitedUsername?: string; permissions?: string[] } = {};
    server.use(
      http.post(`*/api/v1/realty-groups/${GROUP_ID}/invitations`, async ({ request }) => {
        captured = (await request.json()) as typeof captured;
        return HttpResponse.json({
          publicId: "22222222-2222-2222-2222-222222222222",
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
        });
      }),
    );
    renderWithProviders(<InviteForm groupPublicId={GROUP_ID} />);
    await userEvent.type(screen.getByTestId("invite-form-username"), "alice");
    await userEvent.click(
      screen.getByTestId("permission-checkbox-INVITE_AGENTS"),
    );
    await userEvent.click(screen.getByTestId("invite-form-submit"));
    await waitFor(() => expect(captured.invitedUsername).toBe("alice"));
    expect(captured.permissions).toEqual(["INVITE_AGENTS"]);
  });

  // Realty Groups: E §6.1 — leader can set the invited agent's per-member
  // commission rate at invite time. Wire shape is a 0..1 decimal; the UI
  // collects a percentage string and converts on submit.
  it("submits with agentCommissionRate when the leader fills in the rate field", async () => {
    let captured: {
      invitedUsername?: string;
      permissions?: string[];
      agentCommissionRate?: number;
    } = {};
    server.use(
      http.post(`*/api/v1/realty-groups/${GROUP_ID}/invitations`, async ({ request }) => {
        captured = (await request.json()) as typeof captured;
        return HttpResponse.json({
          publicId: "22222222-2222-2222-2222-222222222222",
          groupPublicId: GROUP_ID,
          groupName: "Mainland Realty",
          groupSlug: "mainland-realty",
          invitedByPublicId: "11111111-1111-1111-1111-111111111111",
          invitedByDisplayName: "Leader",
          permissions: [],
          status: "PENDING",
          expiresAt: "2026-05-18T10:00:00Z",
          createdAt: "2026-05-11T10:00:00Z",
          respondedAt: null,
        });
      }),
    );
    renderWithProviders(<InviteForm groupPublicId={GROUP_ID} />);
    await userEvent.type(screen.getByTestId("invite-form-username"), "alice");
    await userEvent.type(
      screen.getByTestId("invite-form-commission-rate"),
      "12.5",
    );
    await userEvent.click(screen.getByTestId("invite-form-submit"));
    await waitFor(() => expect(captured.invitedUsername).toBe("alice"));
    // 12.5% percentage → 0.125 decimal on the wire.
    expect(captured.agentCommissionRate).toBe(0.125);
  });

  it("omits agentCommissionRate when the field is left blank", async () => {
    let captured: {
      invitedUsername?: string;
      agentCommissionRate?: number;
    } = {};
    server.use(
      http.post(`*/api/v1/realty-groups/${GROUP_ID}/invitations`, async ({ request }) => {
        captured = (await request.json()) as typeof captured;
        return HttpResponse.json({
          publicId: "22222222-2222-2222-2222-222222222222",
          groupPublicId: GROUP_ID,
          groupName: "Mainland Realty",
          groupSlug: "mainland-realty",
          invitedByPublicId: "11111111-1111-1111-1111-111111111111",
          invitedByDisplayName: "Leader",
          permissions: [],
          status: "PENDING",
          expiresAt: "2026-05-18T10:00:00Z",
          createdAt: "2026-05-11T10:00:00Z",
          respondedAt: null,
        });
      }),
    );
    renderWithProviders(<InviteForm groupPublicId={GROUP_ID} />);
    await userEvent.type(screen.getByTestId("invite-form-username"), "alice");
    await userEvent.click(screen.getByTestId("invite-form-submit"));
    await waitFor(() => expect(captured.invitedUsername).toBe("alice"));
    // Blank → no agentCommissionRate field present at all.
    expect("agentCommissionRate" in captured).toBe(false);
  });
});
