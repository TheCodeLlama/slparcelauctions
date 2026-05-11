import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";

const mockPush = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({
    push: mockPush,
    replace: vi.fn(),
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  usePathname: () => "/admin/realty-groups/00000000-0000-0000-0000-000000000001",
  useSearchParams: () => new URLSearchParams(),
}));

import { AdminRealtyGroupDetailPage } from "./AdminRealtyGroupDetailPage";

const GROUP_ID = "00000000-0000-0000-0000-000000000001";
const LEADER_ID = "11111111-1111-1111-1111-111111111111";
const AGENT_ID = "44444444-4444-4444-4444-444444444444";

function seedGroup(overrides: Record<string, unknown> = {}) {
  server.use(
    http.get(`*/api/v1/admin/realty-groups/${GROUP_ID}`, () =>
      HttpResponse.json({
        publicId: GROUP_ID,
        name: "Mainland Realty",
        slug: "mainland-realty",
        description: "We sell mainland.",
        website: null,
        logoUrl: null,
        coverUrl: null,
        memberSince: "2026-04-01T10:00:00Z",
        leader: {
          userPublicId: LEADER_ID,
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
            permissions: [],
            joinedAt: "2026-04-15T10:00:00Z",
          },
        ],
        agentFeeRate: "0.0000",
        agentFeeSplit: "0.5000",
        memberSeatLimit: 50,
        memberCount: 2,
        ...overrides,
      }),
    ),
  );
}

describe("AdminRealtyGroupDetailPage", () => {
  it("renders a loading state then the group hero", async () => {
    seedGroup();
    renderWithProviders(<AdminRealtyGroupDetailPage publicId={GROUP_ID} />);
    await waitFor(() =>
      expect(
        screen.getByRole("heading", { name: "Mainland Realty" }),
      ).toBeInTheDocument(),
    );
  });

  it("shows an error state if the group can't be fetched", async () => {
    server.use(
      http.get(`*/api/v1/admin/realty-groups/${GROUP_ID}`, () =>
        HttpResponse.json(
          {
            type: "https://slpa.example/problems/realty/not-found",
            title: "Not found",
            status: 404,
            code: "REALTY_GROUP_NOT_FOUND",
          },
          { status: 404 },
        ),
      ),
    );
    renderWithProviders(<AdminRealtyGroupDetailPage publicId={GROUP_ID} />);
    await waitFor(() =>
      expect(
        screen.getByTestId("admin-realty-detail-error"),
      ).toBeInTheDocument(),
    );
  });

  it("renders the admin cooldown bypass banner on the profile form", async () => {
    seedGroup();
    renderWithProviders(<AdminRealtyGroupDetailPage publicId={GROUP_ID} />);
    await waitFor(() =>
      expect(screen.getByTestId("admin-edit-cooldown-banner")).toBeInTheDocument(),
    );
  });

  it("lists the leader and each agent row with force-remove affordances", async () => {
    seedGroup();
    renderWithProviders(<AdminRealtyGroupDetailPage publicId={GROUP_ID} />);
    await waitFor(() =>
      expect(
        screen.getByTestId("admin-group-members-list"),
      ).toBeInTheDocument(),
    );
    expect(
      screen.getByTestId(`admin-member-remove-${LEADER_ID}`),
    ).toBeInTheDocument();
    expect(
      screen.getByTestId(`admin-member-remove-${AGENT_ID}`),
    ).toBeInTheDocument();
  });

  it("opens the leader force-remove modal with a replacement picker", async () => {
    seedGroup();
    renderWithProviders(<AdminRealtyGroupDetailPage publicId={GROUP_ID} />);
    await waitFor(() =>
      expect(
        screen.getByTestId(`admin-member-remove-${LEADER_ID}`),
      ).toBeInTheDocument(),
    );
    await userEvent.click(
      screen.getByTestId(`admin-member-remove-${LEADER_ID}`),
    );
    expect(
      screen.getByTestId("admin-member-replacement-select"),
    ).toBeInTheDocument();
  });

  it("blocks leader force-remove when no other agents exist", async () => {
    seedGroup({ agents: [], memberCount: 1 });
    renderWithProviders(<AdminRealtyGroupDetailPage publicId={GROUP_ID} />);
    await waitFor(() =>
      expect(
        screen.getByTestId(`admin-member-remove-${LEADER_ID}`),
      ).toBeInTheDocument(),
    );
    await userEvent.click(
      screen.getByTestId(`admin-member-remove-${LEADER_ID}`),
    );
    expect(
      screen.getByTestId("admin-member-remove-no-replacement"),
    ).toBeInTheDocument();
  });

  it("links to the global admin audit log as the placeholder surface", async () => {
    seedGroup();
    renderWithProviders(<AdminRealtyGroupDetailPage publicId={GROUP_ID} />);
    await waitFor(() =>
      expect(
        screen.getByTestId("admin-realty-audit-log-link"),
      ).toBeInTheDocument(),
    );
    const link = screen.getByTestId("admin-realty-audit-log-link");
    expect(link).toHaveAttribute("href", "/admin/audit-log");
  });

  it("shows the invitations placeholder copy", async () => {
    seedGroup();
    renderWithProviders(<AdminRealtyGroupDetailPage publicId={GROUP_ID} />);
    await waitFor(() =>
      expect(
        screen.getByTestId("admin-realty-invitations-placeholder"),
      ).toBeInTheDocument(),
    );
  });

  it("requires typing the group name before the header dissolve button confirms", async () => {
    seedGroup();
    renderWithProviders(<AdminRealtyGroupDetailPage publicId={GROUP_ID} />);
    await waitFor(() =>
      expect(
        screen.getByTestId("admin-realty-detail-dissolve"),
      ).toBeInTheDocument(),
    );
    await userEvent.click(screen.getByTestId("admin-realty-detail-dissolve"));
    const confirm = screen.getByTestId("admin-realty-detail-dissolve-confirm");
    expect(confirm).toBeDisabled();
    await userEvent.type(
      screen.getByTestId("admin-realty-detail-dissolve-input"),
      "Mainland Realty",
    );
    expect(confirm).not.toBeDisabled();
  });
});
