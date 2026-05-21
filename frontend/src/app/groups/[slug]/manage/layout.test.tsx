import { describe, it, expect, vi, beforeEach } from "vitest";
import type { ReactNode } from "react";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

const replace = vi.fn();
const pathnameMock = vi.fn<() => string>(() =>
  "/groups/sunset-realty/manage/profile",
);

vi.mock("next/navigation", () => ({
  useParams: () => ({ slug: "sunset-realty" }),
  usePathname: () => pathnameMock(),
  useRouter: () => ({ replace }),
}));

const useRealtyGroupBySlug = vi.fn();
vi.mock("@/hooks/realty/useRealtyGroups", () => ({
  useRealtyGroupBySlug: (slug: string | undefined) =>
    useRealtyGroupBySlug(slug),
}));

const useCurrentUser = vi.fn();
vi.mock("@/lib/user", () => ({
  useCurrentUser: () => useCurrentUser(),
}));

import GroupManageLayout from "./layout";

function wrap(node: ReactNode) {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>{node}</QueryClientProvider>,
  );
}

function group({
  leaderPublicId,
  agents = [],
}: {
  leaderPublicId: string;
  agents?: Array<{ userPublicId: string; permissions: string[] }>;
}) {
  return {
    publicId: "g-1",
    slug: "sunset-realty",
    name: "Sunset Realty",
    description: null,
    website: null,
    logoLightUrl: null, logoDarkUrl: null,
    coverLightUrl: null, coverDarkUrl: null,
    memberSince: "2026-01-01T00:00:00Z",
    memberSeatLimit: 50,
    memberCount: 1 + agents.length,
    leader: {
      userPublicId: leaderPublicId,
      displayName: "Leader",
      avatarUrl: null,
    },
    agents: agents.map((a, i) => ({
      memberPublicId: `m-${i}`,
      userPublicId: a.userPublicId,
      displayName: `Agent ${i}`,
      avatarUrl: null,
      role: "AGENT" as const,
      permissions: a.permissions,
      joinedAt: "2026-01-15T00:00:00Z",
      agentCommissionRate: 0,
    })),
  };
}

describe("groups/[slug]/manage layout", () => {
  beforeEach(() => {
    replace.mockReset();
    useRealtyGroupBySlug.mockReset();
    useCurrentUser.mockReset();
    pathnameMock.mockReset();
    pathnameMock.mockReturnValue("/groups/sunset-realty/manage/profile");
  });

  it("renders all 7 management sub-nav items for the leader (Reviews lives outside /manage)", async () => {
    useRealtyGroupBySlug.mockReturnValue({
      data: group({ leaderPublicId: "u-me" }),
      isPending: false,
      isError: false,
    });
    useCurrentUser.mockReturnValue({
      data: { publicId: "u-me" },
      isPending: false,
    });

    wrap(
      <GroupManageLayout>
        <div>child</div>
      </GroupManageLayout>,
    );

    await waitFor(() => {
      expect(
        screen.getByRole("link", { name: /^profile$/i }),
      ).toHaveAttribute("href", "/groups/sunset-realty/manage/profile");
    });
    expect(screen.getByRole("link", { name: /^members$/i })).toHaveAttribute(
      "href",
      "/groups/sunset-realty/manage/members",
    );
    expect(screen.getByRole("link", { name: /^wallet$/i })).toHaveAttribute(
      "href",
      "/groups/sunset-realty/manage/wallet",
    );
    expect(
      screen.getByRole("link", { name: /^sl groups$/i }),
    ).toHaveAttribute("href", "/groups/sunset-realty/manage/sl-groups");
    expect(screen.getByRole("link", { name: /^analytics$/i })).toHaveAttribute(
      "href",
      "/groups/sunset-realty/manage/analytics/commissions",
    );
    expect(
      screen.getByRole("link", { name: /^invitations$/i }),
    ).toHaveAttribute("href", "/groups/sunset-realty/manage/invitations");
    expect(screen.getByRole("link", { name: /^settings$/i })).toHaveAttribute(
      "href",
      "/groups/sunset-realty/manage/settings",
    );
    // Reviews moved outside the management subtree (it stays public at
    // /groups/[slug]/reviews) — must not appear in the manage sub-nav.
    expect(screen.queryByRole("link", { name: /^reviews$/i })).toBeNull();
  });

  it("hides Settings for an agent (non-leader)", async () => {
    useRealtyGroupBySlug.mockReturnValue({
      data: group({
        leaderPublicId: "u-leader",
        agents: [
          {
            userPublicId: "u-me",
            permissions: [
              "INVITE_AGENTS",
              "VIEW_GROUP_TRANSACTIONS",
              "REGISTER_SL_GROUP",
              "MANAGE_MEMBERS",
            ],
          },
        ],
      }),
      isPending: false,
      isError: false,
    });
    useCurrentUser.mockReturnValue({
      data: { publicId: "u-me" },
      isPending: false,
    });

    wrap(
      <GroupManageLayout>
        <div>child</div>
      </GroupManageLayout>,
    );

    await waitFor(() => {
      expect(
        screen.getByRole("link", { name: /^profile$/i }),
      ).toBeInTheDocument();
    });
    expect(screen.queryByRole("link", { name: /^settings$/i })).toBeNull();
  });

  it("hides Wallet when agent lacks VIEW_GROUP_TRANSACTIONS", async () => {
    useRealtyGroupBySlug.mockReturnValue({
      data: group({
        leaderPublicId: "u-leader",
        agents: [{ userPublicId: "u-me", permissions: [] }],
      }),
      isPending: false,
      isError: false,
    });
    useCurrentUser.mockReturnValue({
      data: { publicId: "u-me" },
      isPending: false,
    });

    wrap(
      <GroupManageLayout>
        <div>child</div>
      </GroupManageLayout>,
    );

    await waitFor(() => {
      expect(
        screen.getByRole("link", { name: /^profile$/i }),
      ).toBeInTheDocument();
    });
    expect(screen.queryByRole("link", { name: /^wallet$/i })).toBeNull();
  });

  it("hides Analytics when agent lacks MANAGE_MEMBERS", async () => {
    useRealtyGroupBySlug.mockReturnValue({
      data: group({
        leaderPublicId: "u-leader",
        agents: [{ userPublicId: "u-me", permissions: [] }],
      }),
      isPending: false,
      isError: false,
    });
    useCurrentUser.mockReturnValue({
      data: { publicId: "u-me" },
      isPending: false,
    });

    wrap(
      <GroupManageLayout>
        <div>child</div>
      </GroupManageLayout>,
    );

    await waitFor(() => {
      expect(
        screen.getByRole("link", { name: /^profile$/i }),
      ).toBeInTheDocument();
    });
    expect(screen.queryByRole("link", { name: /^analytics$/i })).toBeNull();
  });

  it("hides Invitations when agent lacks INVITE_AGENTS", async () => {
    useRealtyGroupBySlug.mockReturnValue({
      data: group({
        leaderPublicId: "u-leader",
        agents: [{ userPublicId: "u-me", permissions: [] }],
      }),
      isPending: false,
      isError: false,
    });
    useCurrentUser.mockReturnValue({
      data: { publicId: "u-me" },
      isPending: false,
    });

    wrap(
      <GroupManageLayout>
        <div>child</div>
      </GroupManageLayout>,
    );

    await waitFor(() => {
      expect(
        screen.getByRole("link", { name: /^profile$/i }),
      ).toBeInTheDocument();
    });
    expect(screen.queryByRole("link", { name: /^invitations$/i })).toBeNull();
  });

  it("redirects non-member off /groups/[slug]/manage/profile to the public profile", async () => {
    pathnameMock.mockReturnValue("/groups/sunset-realty/manage/profile");
    useRealtyGroupBySlug.mockReturnValue({
      data: group({ leaderPublicId: "u-someone-else" }),
      isPending: false,
      isError: false,
    });
    useCurrentUser.mockReturnValue({
      data: { publicId: "u-me" },
      isPending: false,
    });

    wrap(
      <GroupManageLayout>
        <div>child</div>
      </GroupManageLayout>,
    );

    await waitFor(() => {
      expect(replace).toHaveBeenCalledWith("/groups/sunset-realty");
    });
  });

  it("redirects an anonymous visitor (no caller publicId) off the management subtree", async () => {
    pathnameMock.mockReturnValue("/groups/sunset-realty/manage/profile");
    useRealtyGroupBySlug.mockReturnValue({
      data: group({ leaderPublicId: "u-leader" }),
      isPending: false,
      isError: false,
    });
    useCurrentUser.mockReturnValue({
      data: undefined,
      isPending: false,
    });

    wrap(
      <GroupManageLayout>
        <div>child</div>
      </GroupManageLayout>,
    );

    await waitFor(() => {
      expect(replace).toHaveBeenCalledWith("/groups/sunset-realty");
    });
  });
});
