import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import GroupsListPage from "./page";

function seedMyGroups(items: unknown[]) {
  server.use(
    http.get("*/api/v1/me/realty-groups", () => HttpResponse.json(items)),
  );
}

function seedMyInvitations(items: unknown[]) {
  server.use(
    http.get("*/api/v1/me/invitations", () => HttpResponse.json(items)),
  );
}

describe("GroupsListPage (/dashboard/groups)", () => {
  it("renders the header and create CTA", async () => {
    seedMyGroups([]);
    seedMyInvitations([]);
    renderWithProviders(<GroupsListPage />);
    expect(
      await screen.findByRole("heading", { name: /My realty groups/i }),
    ).toBeInTheDocument();
    expect(screen.getByTestId("groups-create-cta")).toBeInTheDocument();
  });

  it("renders the empty state when there are no groups and no invitations", async () => {
    seedMyGroups([]);
    seedMyInvitations([]);
    renderWithProviders(<GroupsListPage />);
    await waitFor(() =>
      expect(
        screen.getByText(/You're not part of any realty groups yet/i),
      ).toBeInTheDocument(),
    );
    expect(screen.getByTestId("groups-empty-create-cta")).toBeInTheDocument();
  });

  it("renders the pending invitations strip when invitations exist", async () => {
    seedMyGroups([]);
    seedMyInvitations([
      {
        publicId: "22222222-2222-2222-2222-222222222222",
        groupPublicId: "00000000-0000-0000-0000-000000000001",
        groupName: "Mainland Realty",
        groupSlug: "mainland-realty",
        invitedByPublicId: "11111111-1111-1111-1111-111111111111",
        invitedByDisplayName: "Leader",
        permissions: [],
        status: "PENDING",
        expiresAt: "2026-05-18T10:00:00Z",
        createdAt: "2026-05-11T10:00:00Z",
        respondedAt: null,
      },
    ]);
    renderWithProviders(<GroupsListPage />);
    await waitFor(() =>
      expect(
        screen.getByTestId("pending-invitations-strip"),
      ).toBeInTheDocument(),
    );
    expect(screen.getByText("Mainland Realty")).toBeInTheDocument();
    expect(screen.getByTestId(/invitation-accept-/)).toBeInTheDocument();
  });

  it("renders rows for each group the caller belongs to", async () => {
    seedMyInvitations([]);
    seedMyGroups([
      {
        publicId: "00000000-0000-0000-0000-000000000001",
        name: "Mainland Realty",
        slug: "mainland-realty",
        logoUrl: null,
        memberCount: 4,
        memberSince: "2026-04-01T10:00:00Z",
      },
    ]);
    renderWithProviders(<GroupsListPage />);
    await waitFor(() =>
      expect(screen.getByTestId("my-groups-list")).toBeInTheDocument(),
    );
    expect(screen.getByText("Mainland Realty")).toBeInTheDocument();
    expect(screen.getByText(/4 members/i)).toBeInTheDocument();
  });
});
