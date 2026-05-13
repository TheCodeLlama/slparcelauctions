import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import GroupInvitationsPage from "./page";

vi.mock("next/navigation", () => ({
  useParams: () => ({ slug: "sunset-realty" }),
}));

const groupWithLeader = (
  leaderId: string,
  agents: Array<{ userPublicId: string; permissions: string[] }> = [],
) => ({
  publicId: "g-1",
  slug: "sunset-realty",
  name: "Sunset Realty",
  description: null,
  website: null,
  memberSince: "2026-01-01T00:00:00Z",
  memberCount: 1 + agents.length,
  coverUrl: null,
  logoUrl: null,
  leader: { userPublicId: leaderId, displayName: "L", avatarUrl: null },
  agents: agents.map((a) => ({
    userPublicId: a.userPublicId,
    displayName: "A",
    avatarUrl: null,
    permissions: a.permissions,
    role: "AGENT",
  })),
});

let currentGroup: ReturnType<typeof groupWithLeader> = groupWithLeader("u-me");

vi.mock("@/hooks/realty/useRealtyGroups", () => ({
  useRealtyGroupBySlug: () => ({ data: currentGroup, isPending: false }),
  useRealtyGroupInvitations: () => ({ data: [], isPending: false }),
  useRevokeInvitation: () => ({ mutate: vi.fn(), isPending: false }),
}));

vi.mock("@/lib/user", () => ({
  useCurrentUser: () => ({ data: { publicId: "u-me" }, isPending: false }),
}));

function wrap(node: React.ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}>{node}</QueryClientProvider>);
}

describe("/groups/[slug]/invitations", () => {
  beforeEach(() => {
    currentGroup = groupWithLeader("u-me");
  });

  it("renders the invite form for the leader", () => {
    currentGroup = groupWithLeader("u-me");
    wrap(<GroupInvitationsPage />);
    expect(screen.getByTestId("invitations-send-button")).toBeInTheDocument();
  });

  it("renders the invite form for an agent holding INVITE_AGENTS", () => {
    currentGroup = groupWithLeader("u-leader", [
      { userPublicId: "u-me", permissions: ["INVITE_AGENTS"] },
    ]);
    wrap(<GroupInvitationsPage />);
    expect(screen.getByTestId("invitations-send-button")).toBeInTheDocument();
  });

  it("renders forbidden notice for an agent without INVITE_AGENTS", () => {
    currentGroup = groupWithLeader("u-leader", [
      { userPublicId: "u-me", permissions: [] },
    ]);
    wrap(<GroupInvitationsPage />);
    expect(screen.getByText(/permission/i)).toBeInTheDocument();
  });
});
