import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { mockVerifiedCurrentUser } from "@/test/msw/fixtures";

vi.mock("next/navigation", () => ({
  useParams: () => ({ slug: "mainland-realty" }),
  useRouter: () => ({
    push: vi.fn(),
    replace: vi.fn(),
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  usePathname: () => "/dashboard/groups/mainland-realty/manage",
  useSearchParams: () => new URLSearchParams(),
}));

import GroupManagePage from "./page";

const LEADER_USER_ID = mockVerifiedCurrentUser.publicId;
const AGENT_ID = "44444444-4444-4444-4444-444444444444";

function seedGroup(overrides: Record<string, unknown> = {}) {
  server.use(
    http.get("*/api/v1/users/me", () =>
      HttpResponse.json(mockVerifiedCurrentUser),
    ),
    http.get("*/api/v1/me/realty-groups", () => HttpResponse.json([])),
    http.get("*/api/v1/realty-groups/by-slug/mainland-realty", () =>
      HttpResponse.json({
        publicId: "00000000-0000-0000-0000-000000000001",
        name: "Mainland Realty",
        slug: "mainland-realty",
        description: null,
        website: null,
        logoUrl: null,
        coverUrl: null,
        memberSince: "2026-04-01T10:00:00Z",
        leader: {
          userPublicId: LEADER_USER_ID,
          displayName: "Leader Lee",
          avatarUrl: null,
        },
        agents: [
          {
            memberPublicId: "33333333-3333-3333-3333-333333333333",
            userPublicId: AGENT_ID,
            displayName: "Agent Alpha",
            avatarUrl: null,
            role: "AGENT",
            permissions: ["INVITE_AGENTS"],
            joinedAt: "2026-04-15T10:00:00Z",
          },
        ],
        memberSeatLimit: 50,
        memberCount: 2,
        ...overrides,
      }),
    ),
  );
}

describe("GroupManagePage (leader caller)", () => {
  it("renders the leader tabs including Settings", async () => {
    seedGroup();
    renderWithProviders(<GroupManagePage />, { auth: "authenticated" });
    await waitFor(() =>
      expect(
        screen.getByRole("heading", { name: /Manage Mainland Realty/i }),
      ).toBeInTheDocument(),
    );
    expect(screen.getByTestId("manage-tab-profile")).toBeInTheDocument();
    expect(screen.getByTestId("manage-tab-members")).toBeInTheDocument();
    expect(screen.getByTestId("manage-tab-invitations")).toBeInTheDocument();
    expect(screen.getByTestId("manage-tab-settings")).toBeInTheDocument();
  });

  it("renders the profile tab by default and switches to members on click", async () => {
    seedGroup();
    renderWithProviders(<GroupManagePage />, { auth: "authenticated" });
    await waitFor(() =>
      expect(screen.getByTestId("group-profile-name")).toBeInTheDocument(),
    );
    await userEvent.click(screen.getByTestId("manage-tab-members"));
    expect(screen.getByTestId("members-list")).toBeInTheDocument();
  });

  it("shows the role label as Leader when caller is the leader", async () => {
    seedGroup();
    renderWithProviders(<GroupManagePage />, { auth: "authenticated" });
    await waitFor(() => expect(screen.getByText("Leader")).toBeInTheDocument());
  });
});

describe("GroupManagePage (agent caller without INVITE_AGENTS)", () => {
  it("hides the Invitations tab and the Settings tab", async () => {
    // Caller is the AGENT (no invite perm), not the leader.
    server.use(
      http.get("*/api/v1/users/me", () =>
        HttpResponse.json({
          ...mockVerifiedCurrentUser,
          publicId: AGENT_ID,
        }),
      ),
      http.get("*/api/v1/me/realty-groups", () => HttpResponse.json([])),
      http.get("*/api/v1/realty-groups/by-slug/mainland-realty", () =>
        HttpResponse.json({
          publicId: "00000000-0000-0000-0000-000000000001",
          name: "Mainland Realty",
          slug: "mainland-realty",
          description: null,
          website: null,
          logoUrl: null,
          coverUrl: null,
          memberSince: "2026-04-01T10:00:00Z",
          leader: {
            userPublicId: LEADER_USER_ID,
            displayName: "Leader Lee",
            avatarUrl: null,
          },
          agents: [
            {
              memberPublicId: "33333333-3333-3333-3333-333333333333",
              userPublicId: AGENT_ID,
              displayName: "Agent Alpha",
              avatarUrl: null,
              role: "AGENT",
              permissions: [], // no INVITE_AGENTS
              joinedAt: "2026-04-15T10:00:00Z",
            },
          ],
          memberSeatLimit: 50,
          memberCount: 2,
        }),
      ),
    );
    renderWithProviders(<GroupManagePage />, {
      auth: "authenticated",
      authUser: {
        publicId: AGENT_ID,
        username: "agent-alpha",
        email: null,
        displayName: "Agent Alpha",
        slAvatarUuid: null,
        verified: true,
        role: "USER",
      },
    });
    await waitFor(() =>
      expect(screen.getByTestId("manage-tab-profile")).toBeInTheDocument(),
    );
    expect(screen.queryByTestId("manage-tab-invitations")).not.toBeInTheDocument();
    expect(screen.queryByTestId("manage-tab-settings")).not.toBeInTheDocument();
  });
});
