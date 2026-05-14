import { describe, it, expect, vi, beforeEach } from "vitest";
import type { ReactNode } from "react";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

const replace = vi.fn();
const pathnameMock = vi.fn<() => string>(() => "/groups/sunset-realty/profile");

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

import GroupSlugLayout from "./layout";

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
    logoUrl: null,
    coverUrl: null,
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

describe("groups/[slug] layout", () => {
  beforeEach(() => {
    replace.mockReset();
    useRealtyGroupBySlug.mockReset();
    useCurrentUser.mockReset();
    pathnameMock.mockReset();
    pathnameMock.mockReturnValue("/groups/sunset-realty/profile");
  });

  it("renders all 8 sub-nav items for the leader", async () => {
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
      <GroupSlugLayout>
        <div>child</div>
      </GroupSlugLayout>,
    );

    await waitFor(() => {
      expect(
        screen.getByRole("link", { name: /^profile$/i }),
      ).toBeInTheDocument();
    });
    expect(screen.getByRole("link", { name: /^members$/i })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /^wallet$/i })).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /^sl groups$/i }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /^analytics$/i }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /^invitations$/i }),
    ).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /^reviews$/i })).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /^settings$/i }),
    ).toBeInTheDocument();
  });

  it("hides Settings for an agent (non-leader)", async () => {
    useRealtyGroupBySlug.mockReturnValue({
      data: group({
        leaderPublicId: "u-leader",
        agents: [
          {
            userPublicId: "u-me",
            // Agent with full perms except leader-only Settings — still no Settings.
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
      <GroupSlugLayout>
        <div>child</div>
      </GroupSlugLayout>,
    );

    await waitFor(() => {
      expect(
        screen.getByRole("link", { name: /^profile$/i }),
      ).toBeInTheDocument();
    });
    expect(
      screen.queryByRole("link", { name: /^settings$/i }),
    ).toBeNull();
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
      <GroupSlugLayout>
        <div>child</div>
      </GroupSlugLayout>,
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
      <GroupSlugLayout>
        <div>child</div>
      </GroupSlugLayout>,
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
      <GroupSlugLayout>
        <div>child</div>
      </GroupSlugLayout>,
    );

    await waitFor(() => {
      expect(
        screen.getByRole("link", { name: /^profile$/i }),
      ).toBeInTheDocument();
    });
    expect(screen.queryByRole("link", { name: /^invitations$/i })).toBeNull();
  });

  it("redirects non-member off /groups/[slug]/profile to /groups/[slug]", async () => {
    pathnameMock.mockReturnValue("/groups/sunset-realty/profile");
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
      <GroupSlugLayout>
        <div>child</div>
      </GroupSlugLayout>,
    );

    await waitFor(() => {
      expect(replace).toHaveBeenCalledWith("/groups/sunset-realty");
    });
  });

  it("does NOT redirect non-member visiting /groups/[slug]/reviews", async () => {
    pathnameMock.mockReturnValue("/groups/sunset-realty/reviews");
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
      <GroupSlugLayout>
        <div>child</div>
      </GroupSlugLayout>,
    );

    await waitFor(() => {
      expect(
        screen.getByRole("link", { name: /^reviews$/i }),
      ).toBeInTheDocument();
    });
    expect(replace).not.toHaveBeenCalled();
  });

  // Anonymous-visitor expectation: useCurrentUser returns { data: undefined }
  // (TanStack Query's idle state when the auth check has finished and the
  // caller is unauthenticated). With no caller publicId, the
  // (leader/agent) gate falls through and Reviews — the only publicly-
  // visible nav item — is the sole link rendered. The non-member redirect
  // still triggers from any member-only route (verified separately above).
  // We assert here on the public profile root, which is exempt from the
  // redirect, so the nav renders only the public link.
  it("anonymous visitor on /groups/[slug] sees only the Reviews sub-nav link", async () => {
    pathnameMock.mockReturnValue("/groups/sunset-realty");
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
      <GroupSlugLayout>
        <div>child</div>
      </GroupSlugLayout>,
    );

    await waitFor(() => {
      expect(
        screen.getByRole("link", { name: /^reviews$/i }),
      ).toBeInTheDocument();
    });
    expect(screen.queryByRole("link", { name: /^profile$/i })).toBeNull();
    expect(screen.queryByRole("link", { name: /^settings$/i })).toBeNull();
    expect(screen.queryByRole("link", { name: /^wallet$/i })).toBeNull();
    expect(screen.queryByRole("link", { name: /^invitations$/i })).toBeNull();
    expect(replace).not.toHaveBeenCalled();
  });
});
